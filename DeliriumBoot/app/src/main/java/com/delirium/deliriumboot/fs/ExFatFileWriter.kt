package com.delirium.deliriumboot.fs

import com.delirium.deliriumboot.usb.UsbWriter
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

data class ExFatInfo(
    val partitionStartLba: Long,
    val sectorSize: Int,
    val sectorsPerClusterShift: Int,
    val clusterHeapOffset: Long,
    val clusterCount: Long,
    val rootCluster: Long
) {
    val clusterSizeBytes get() = sectorSize shl sectorsPerClusterShift
    fun clusterToLba(cluster: Long) = partitionStartLba + clusterHeapOffset + ((cluster - 2) shl sectorsPerClusterShift)
    fun bitmapLba() = clusterToLba(2)
}

/**
 * Добавление файлов и каталогов в том exFAT, используя упрощённую (но
 * валидную) схему: каждый файл/каталог занимает непрерывный диапазон
 * кластеров, что позволяет выставить флаг NoFatChain и не вести отдельную
 * цепочку в таблице FAT — это заметно упрощает реализацию и остаётся
 * полностью совместимым со спецификацией exFAT.
 */
object ExFatFileWriter {

    fun readInfo(writer: UsbWriter, partitionStartLba: Long): ExFatInfo {
        val boot = writer.readAt(partitionStartLba, 1)
        val bb = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        val clusterHeapOffset = bb.getInt(88).toLong() and 0xFFFFFFFFL
        val clusterCount = bb.getInt(92).toLong() and 0xFFFFFFFFL
        val rootCluster = bb.getInt(96).toLong() and 0xFFFFFFFFL
        val bpsShift = boot[108].toInt() and 0xFF
        val spcShift = boot[109].toInt() and 0xFF
        return ExFatInfo(partitionStartLba, 1 shl bpsShift, spcShift, clusterHeapOffset, clusterCount, rootCluster)
    }

    fun writeFile(
        writer: UsbWriter,
        info: ExFatInfo,
        path: String,
        length: Long,
        input: InputStream,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val parts = path.split("/").filter { it.isNotBlank() }
        val dirPath = parts.dropLast(1).joinToString("/")
        val fileName = parts.last()
        val dirCluster = ensureDirectory(writer, info, dirPath)

        val clustersNeeded = ((length + info.clusterSizeBytes - 1) / info.clusterSizeBytes).coerceAtLeast(1)
        val bitmap = readBitmap(writer, info)
        val firstCluster = findContiguousFree(bitmap, clustersNeeded, info.clusterCount)
        markAllocated(bitmap, firstCluster, clustersNeeded)
        writer.writeAt(info.bitmapLba(), bitmap)

        var written = 0L
        val buffer = ByteArray(info.clusterSizeBytes)
        for (i in 0 until clustersNeeded) {
            var filled = 0
            while (filled < buffer.size) {
                val n = input.read(buffer, filled, buffer.size - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled < buffer.size) Arrays.fill(buffer, filled, buffer.size, 0)
            writer.writeAt(info.clusterToLba(firstCluster + i), buffer)
            written += filled
            if (length > 0) onProgress?.invoke((written.toFloat() / length.toFloat()).coerceIn(0f, 1f))
        }

        appendEntrySet(writer, info, dirCluster, fileName, firstCluster, length, isDirectory = false)
    }

    fun ensureDirectory(writer: UsbWriter, info: ExFatInfo, path: String): Long {
        if (path.isBlank()) return info.rootCluster
        var currentCluster = info.rootCluster
        for (name in path.split("/").filter { it.isNotBlank() }) {
            currentCluster = findEntryCluster(writer, info, currentCluster, name)
                ?: createSubdirectory(writer, info, currentCluster, name)
        }
        return currentCluster
    }

    private fun findEntryCluster(writer: UsbWriter, info: ExFatInfo, dirCluster: Long, name: String): Long? {
        val data = writer.readAt(info.clusterToLba(dirCluster), 1 shl info.sectorsPerClusterShift)
        var i = 0
        while (i < data.size) {
            val type = data[i].toInt() and 0xFF
            if (type == 0x00) break
            if (type == 0x85) {
                val secondaryCount = data[i + 1].toInt() and 0xFF
                val streamOffset = i + 32
                if (streamOffset < data.size && (data[streamOffset].toInt() and 0xFF) == 0xC0) {
                    val nameLen = data[streamOffset + 3].toInt() and 0xFF
                    val sb = StringBuilder()
                    var nameEntryOffset = streamOffset + 32
                    var remaining = nameLen
                    while (remaining > 0 && nameEntryOffset < data.size) {
                        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                        val take = minOf(15, remaining)
                        for (c in 0 until take) sb.append(bb.getShort(nameEntryOffset + 2 + c * 2).toInt().toChar())
                        remaining -= take
                        nameEntryOffset += 32
                    }
                    if (sb.toString().equals(name, ignoreCase = true)) {
                        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                        return bb.getInt(streamOffset + 20).toLong() and 0xFFFFFFFFL
                    }
                }
                i += 32 * (secondaryCount + 1)
                continue
            }
            i += 32
        }
        return null
    }

    private fun createSubdirectory(writer: UsbWriter, info: ExFatInfo, parentCluster: Long, name: String): Long {
        val bitmap = readBitmap(writer, info)
        val newCluster = findContiguousFree(bitmap, 1, info.clusterCount)
        markAllocated(bitmap, newCluster, 1)
        writer.writeAt(info.bitmapLba(), bitmap)

        // Новый каталог изначально пуст (все записи = 0x00)
        writer.writeAt(info.clusterToLba(newCluster), ByteArray(info.clusterSizeBytes))

        appendEntrySet(writer, info, parentCluster, name, newCluster, 0, isDirectory = true)
        return newCluster
    }

    private fun readBitmap(writer: UsbWriter, info: ExFatInfo): ByteArray =
        writer.readAt(info.bitmapLba(), 1 shl info.sectorsPerClusterShift)

    private fun findContiguousFree(bitmap: ByteArray, count: Long, clusterCount: Long): Long {
        var run = 0L
        var runStart = 2L
        for (cluster in 2 until (clusterCount + 2)) {
            val idx = cluster - 2
            val byteIdx = (idx / 8).toInt()
            val bitIdx = (idx % 8).toInt()
            val used = (bitmap[byteIdx].toInt() shr bitIdx) and 1 == 1
            if (used) { run = 0; runStart = cluster + 1 } else {
                run++
                if (run >= count) return runStart
            }
        }
        throw IOException("Недостаточно свободного места на exFAT-разделе")
    }

    private fun markAllocated(bitmap: ByteArray, firstCluster: Long, count: Long) {
        for (i in 0 until count) {
            val idx = firstCluster + i - 2
            val byteIdx = (idx / 8).toInt()
            val bitIdx = (idx % 8).toInt()
            bitmap[byteIdx] = (bitmap[byteIdx].toInt() or (1 shl bitIdx)).toByte()
        }
    }

    private fun appendEntrySet(
        writer: UsbWriter, info: ExFatInfo, dirCluster: Long, name: String,
        firstCluster: Long, length: Long, isDirectory: Boolean
    ) {
        val lba = info.clusterToLba(dirCluster)
        val data = writer.readAt(lba, 1 shl info.sectorsPerClusterShift)
        val nameEntries = (name.length + 14) / 15
        val totalEntries = 2 + nameEntries
        val offset = findFreeSlot(data, totalEntries)
            ?: throw IOException("Недостаточно места в каталоге exFAT (упрощённая реализация не расширяет каталог автоматически)")

        val nameHash = computeNameHash(name)
        data[offset] = 0x85.toByte()
        data[offset + 1] = (nameEntries + 1).toByte()

        val streamOffset = offset + 32
        data[streamOffset] = 0xC0.toByte()
        data[streamOffset + 1] = 0x03 // AllocationPossible | NoFatChain
        data[streamOffset + 3] = name.length.toByte()
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(streamOffset + 4, nameHash.toShort())
        bb.putLong(streamOffset + 8, length)
        bb.putInt(streamOffset + 20, firstCluster.toInt())
        bb.putLong(streamOffset + 24, length)

        if (isDirectory) {
            data[offset + 4] = 0x10 // FileAttributes = DIRECTORY (в поле File entry)
        }

        var pos = streamOffset + 32
        for (chunk in name.chunked(15)) {
            data[pos] = 0xC1.toByte()
            val padded = chunk.padEnd(15, '\u0000')
            for (c in 0 until 15) bb.putShort(pos + 2 + c * 2, padded[c].code.toShort())
            pos += 32
        }

        val totalLen = 32 * totalEntries
        val setBytes = data.copyOfRange(offset, offset + totalLen)
        val checksum = entrySetChecksum(setBytes)
        bb.putShort(offset + 2, checksum.toShort())

        writer.writeAt(lba, data)
    }

    private fun findFreeSlot(data: ByteArray, entries: Int): Int? {
        var free = 0
        for (i in data.indices step 32) {
            if (data[i] == 0x00.toByte()) {
                free++
                if (free >= entries) return i - (entries - 1) * 32
            } else free = 0
        }
        return null
    }

    private fun entrySetChecksum(bytes: ByteArray): Int {
        var checksum = 0
        for (i in bytes.indices) {
            if (i == 2 || i == 3) continue
            checksum = ((((checksum shl 15) or (checksum shr 1)) and 0xFFFF) + (bytes[i].toInt() and 0xFF)) and 0xFFFF
        }
        return checksum
    }

    private fun computeNameHash(name: String): Int {
        var hash = 0
        for (c in name) {
            val code = c.code
            hash = ((((hash shl 15) or (hash shr 1)) and 0xFFFF) + (code and 0xFF)) and 0xFFFF
            hash = ((((hash shl 15) or (hash shr 1)) and 0xFFFF) + ((code shr 8) and 0xFF)) and 0xFFFF
        }
        return hash
    }
}
