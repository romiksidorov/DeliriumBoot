package com.delirium.deliriumboot.fs

import com.delirium.deliriumboot.usb.UsbWriter
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

data class Fat32Info(
    val partitionStartLba: Long,
    val sectorSize: Int,
    val sectorsPerCluster: Int,
    val reservedSectors: Int,
    val numFats: Int,
    val fatSize: Long,
    val rootCluster: Long,
    val dataStartSector: Long
) {
    val clusterSizeBytes get() = sectorSize * sectorsPerCluster
    fun clusterToLba(cluster: Long) = partitionStartLba + dataStartSector + (cluster - 2) * sectorsPerCluster
    fun fatEntryLocation(cluster: Long): Pair<Long, Int> {
        val byteOffset = cluster * 4
        return Pair(partitionStartLba + reservedSectors + byteOffset / sectorSize, (byteOffset % sectorSize).toInt())
    }
}

/**
 * Добавление файлов (ISO-образов, ventoy.json, фоновых изображений) в том
 * FAT32, отформатированный [com.delirium.deliriumboot.format.Fat32Formatter].
 * Поддерживает длинные имена файлов (VFAT LFN) и создание подкаталогов
 * (нужно для структуры /ventoy/theme/...).
 */
object Fat32FileWriter {

    fun readInfo(writer: UsbWriter, partitionStartLba: Long): Fat32Info {
        val boot = writer.readAt(partitionStartLba, 1)
        val bb = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        val sectorSize = bb.getShort(11).toInt() and 0xFFFF
        val spc = boot[13].toInt() and 0xFF
        val reserved = bb.getShort(14).toInt() and 0xFFFF
        val numFats = boot[16].toInt() and 0xFF
        val fatSize = bb.getInt(36).toLong() and 0xFFFFFFFFL
        val rootCluster = bb.getInt(44).toLong() and 0xFFFFFFFFL
        return Fat32Info(partitionStartLba, sectorSize, spc, reserved, numFats, fatSize, rootCluster, reserved + numFats * fatSize)
    }

    /** [path] — относительный путь на разделе, например "ventoy/ventoy.json". */
    fun writeFile(
        writer: UsbWriter,
        info: Fat32Info,
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
        val freeClusters = findFreeClusters(writer, info, clustersNeeded.toInt())
        linkClusterChain(writer, info, freeClusters)

        var written = 0L
        val buffer = ByteArray(info.clusterSizeBytes)
        for (cluster in freeClusters) {
            var filled = 0
            while (filled < buffer.size) {
                val n = input.read(buffer, filled, buffer.size - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled < buffer.size) Arrays.fill(buffer, filled, buffer.size, 0)
            writer.writeAt(info.clusterToLba(cluster), buffer)
            written += filled
            if (length > 0) onProgress?.invoke((written.toFloat() / length.toFloat()).coerceIn(0f, 1f))
        }

        appendDirectoryEntryToCluster(writer, info, dirCluster, fileName, freeClusters.first(), length, isDirectory = false)
    }

    fun ensureDirectory(writer: UsbWriter, info: Fat32Info, path: String): Long {
        if (path.isBlank()) return info.rootCluster
        var currentCluster = info.rootCluster
        for (name in path.split("/").filter { it.isNotBlank() }) {
            currentCluster = findEntryCluster(writer, info, currentCluster, name)
                ?: createSubdirectory(writer, info, currentCluster, name)
        }
        return currentCluster
    }

    private fun findEntryCluster(writer: UsbWriter, info: Fat32Info, dirCluster: Long, name: String): Long? {
        val data = writer.readAt(info.clusterToLba(dirCluster), info.sectorsPerCluster)
        var i = 0
        while (i < data.size) {
            if (data[i] == 0x00.toByte()) break
            val attr = data[i + 11].toInt() and 0xFF
            if (data[i] != 0xE5.toByte() && attr != 0x0F) {
                val entryName = String(data, i, 8, Charsets.US_ASCII).trim()
                if (entryName.equals(name.take(8), ignoreCase = true)) {
                    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    val hi = bb.getShort(i + 20).toInt() and 0xFFFF
                    val lo = bb.getShort(i + 26).toInt() and 0xFFFF
                    return (hi.toLong() shl 16) or lo.toLong()
                }
            }
            i += 32
        }
        return null
    }

    private fun createSubdirectory(writer: UsbWriter, info: Fat32Info, parentCluster: Long, name: String): Long {
        val newCluster = findFreeClusters(writer, info, 1).first()
        linkClusterChain(writer, info, listOf(newCluster))

        val dirData = ByteArray(info.clusterSizeBytes)
        writeShortEntryRaw(dirData, 0, ".", newCluster, 0, isDirectory = true)
        writeShortEntryRaw(dirData, 32, "..", parentCluster, 0, isDirectory = true)
        writer.writeAt(info.clusterToLba(newCluster), dirData)

        appendDirectoryEntryToCluster(writer, info, parentCluster, name, newCluster, 0, isDirectory = true)
        return newCluster
    }

    private fun findFreeClusters(writer: UsbWriter, info: Fat32Info, count: Int): List<Long> {
        val result = mutableListOf<Long>()
        var cluster = 2L
        while (result.size < count) {
            val (lba, offset) = info.fatEntryLocation(cluster)
            val sector = writer.readAt(lba, 1)
            val value = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN).getInt(offset) and 0x0FFFFFFF
            if (value == 0) result.add(cluster)
            cluster++
            if (cluster > 200_000_000L) throw IOException("Не найдено свободное место на разделе")
        }
        return result
    }

    private fun linkClusterChain(writer: UsbWriter, info: Fat32Info, clusters: List<Long>) {
        for (fatIndex in 0 until info.numFats) {
            val fatBase = info.partitionStartLba + info.reservedSectors + fatIndex * info.fatSize
            for (i in clusters.indices) {
                val value = if (i == clusters.size - 1) 0x0FFFFFFF else clusters[i + 1].toInt()
                writeFatEntry(writer, fatBase, info.sectorSize, clusters[i], value)
            }
        }
    }

    private fun writeFatEntry(writer: UsbWriter, fatBase: Long, sectorSize: Int, cluster: Long, value: Int) {
        val byteOffset = cluster * 4
        val sector = fatBase + byteOffset / sectorSize
        val offset = (byteOffset % sectorSize).toInt()
        val data = writer.readAt(sector, 1)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value and 0x0FFFFFFF)
        writer.writeAt(sector, data)
    }

    private fun appendDirectoryEntryToCluster(
        writer: UsbWriter, info: Fat32Info, dirCluster: Long, fileName: String,
        firstCluster: Long, length: Long, isDirectory: Boolean
    ) {
        val lba = info.clusterToLba(dirCluster)
        val data = writer.readAt(lba, info.sectorsPerCluster)
        val requiredEntries = (fileName.length + 12) / 13 + 1
        val offset = findFreeDirSlot(data, requiredEntries)
            ?: throw IOException("Недостаточно места в каталоге (упрощённая реализация не расширяет каталог автоматически)")
        writeEntries(data, offset, fileName, firstCluster, length, isDirectory)
        writer.writeAt(lba, data)
    }

    private fun findFreeDirSlot(data: ByteArray, entriesNeeded: Int): Int? {
        var free = 0
        for (i in data.indices step 32) {
            if (data[i] == 0x00.toByte() || data[i] == 0xE5.toByte()) {
                free++
                if (free >= entriesNeeded) return i - (entriesNeeded - 1) * 32
            } else free = 0
        }
        return null
    }

    private fun writeEntries(data: ByteArray, offset: Int, fileName: String, firstCluster: Long, length: Long, isDirectory: Boolean) {
        val shortName = generateShortName(fileName)
        val checksum = shortNameChecksum(shortName)
        val lfnChunks = fileName.chunked(13).reversed()
        var pos = offset
        for ((idx, chunk) in lfnChunks.withIndex()) {
            val seq = lfnChunks.size - idx
            writeLfnEntry(data, pos, chunk, seq, isLast = idx == 0, checksum = checksum)
            pos += 32
        }
        writeShortEntryRaw(data, pos, shortName, firstCluster, length, isDirectory, alreadyPadded = true)
    }

    private fun writeLfnEntry(data: ByteArray, offset: Int, chunk: String, seq: Int, isLast: Boolean, checksum: Int) {
        val entry = ByteArray(32)
        entry[0] = (if (isLast) seq or 0x40 else seq).toByte()
        val chars = chunk.padEnd(13, '\uFFFF')
        fun putChar(pos: Int, c: Char) { entry[pos] = (c.code and 0xFF).toByte(); entry[pos + 1] = ((c.code shr 8) and 0xFF).toByte() }
        for (i in 0 until 5) putChar(1 + i * 2, chars[i])
        entry[11] = 0x0F
        entry[13] = checksum.toByte()
        for (i in 0 until 6) putChar(14 + i * 2, chars[5 + i])
        for (i in 0 until 2) putChar(28 + i * 2, chars[11 + i])
        entry.copyInto(data, offset)
    }

    private fun writeShortEntryRaw(
        data: ByteArray, offset: Int, name: String, firstCluster: Long, length: Long,
        isDirectory: Boolean, alreadyPadded: Boolean = false
    ) {
        val entry = ByteArray(32)
        val padded = if (alreadyPadded) name else name.take(11).padEnd(11)
        padded.toByteArray(Charsets.US_ASCII).copyInto(entry, 0, 0, 11)
        entry[11] = if (isDirectory) 0x10 else 0x20
        val bb = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(20, ((firstCluster shr 16) and 0xFFFF).toShort())
        bb.putShort(26, (firstCluster and 0xFFFF).toShort())
        bb.putInt(28, length.toInt())
        entry.copyInto(data, offset)
    }

    private fun generateShortName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = (if (dot > 0) fileName.substring(0, dot) else fileName).uppercase().filter { it.isLetterOrDigit() }
        val ext = (if (dot > 0) fileName.substring(dot + 1) else "").uppercase().filter { it.isLetterOrDigit() }
        val shortBase = (base.take(6).ifEmpty { "FILE" }).padEnd(6, '_') + "~1"
        val shortExt = ext.take(3).padEnd(3, ' ')
        return (shortBase.take(8).padEnd(8) + shortExt).take(11)
    }

    private fun shortNameChecksum(name: String): Int {
        var sum = 0
        for (b in name.toByteArray(Charsets.US_ASCII)) {
            sum = ((((sum and 1) shl 7) or ((sum and 0xFF) shr 1)) + (b.toInt() and 0xFF)) and 0xFF
        }
        return sum
    }
}
