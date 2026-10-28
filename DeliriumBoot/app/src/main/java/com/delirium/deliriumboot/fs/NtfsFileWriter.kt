package com.delirium.deliriumboot.fs

import com.delirium.deliriumboot.usb.UsbWriter
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

data class NtfsInfo(
    val partitionStartLba: Long,
    val sectorSize: Int,
    val sectorsPerCluster: Int,
    val totalSectors: Long,
    val mftStartCluster: Long
) {
    val clusterSizeBytes get() = sectorSize * sectorsPerCluster
    fun clusterToLba(cluster: Long) = partitionStartLba + cluster * sectorsPerCluster
}

object NtfsFileWriter {
    fun readInfo(writer: UsbWriter, partitionStartLba: Long): NtfsInfo {
        val boot = writer.readAt(partitionStartLba, 1)
        val bb = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        val sectorSize = bb.getShort(11).toInt() and 0xFFFF
        val spc = boot[13].toInt() and 0xFF
        val totalSectors = bb.getLong(40)
        val mftStart = bb.getLong(48)
        return NtfsInfo(partitionStartLba, sectorSize, spc, totalSectors, mftStart)
    }

    fun writeFile(
        writer: UsbWriter,
        info: NtfsInfo,
        fileName: String,
        length: Long,
        input: InputStream,
        onProgress: ((Float, Long) -> Unit)? = null
    ) {
        val clustersNeeded = ((length + info.clusterSizeBytes - 1) / info.clusterSizeBytes).coerceAtLeast(1)
        val bitmapCluster = 3L
        val bitmapLba = info.clusterToLba(bitmapCluster)
        val bitmap = writer.readAt(bitmapLba, 8)

        var freeRunStart = 64L
        var foundRun = 0L
        val totalClusters = info.totalSectors / info.sectorsPerCluster

        for (c in 64L until totalClusters) {
            val byteIdx = (c / 8).toInt()
            val bitIdx = (c % 8).toInt()
            if (byteIdx >= bitmap.size) break
            val used = (bitmap[byteIdx].toInt() shr bitIdx) and 1 == 1
            if (!used) {
                if (foundRun == 0L) freeRunStart = c
                foundRun++
                if (foundRun >= clustersNeeded) break
            } else {
                foundRun = 0L
            }
        }

        if (foundRun < clustersNeeded) {
            throw IOException("Недостаточно свободного непрерывного места на NTFS разделе")
        }

        for (i in 0 until clustersNeeded) {
            val c = freeRunStart + i
            val byteIdx = (c / 8).toInt()
            val bitIdx = (c % 8).toInt()
            bitmap[byteIdx] = (bitmap[byteIdx].toInt() or (1 shl bitIdx)).toByte()
        }
        writer.writeAt(bitmapLba, bitmap)

        val buffer = ByteArray(info.clusterSizeBytes)
        var writtenBytes = 0L
        for (i in 0 until clustersNeeded) {
            var filled = 0
            while (filled < buffer.size) {
                val n = input.read(buffer, filled, buffer.size - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled < buffer.size) Arrays.fill(buffer, filled, buffer.size, 0)
            writer.writeAt(info.clusterToLba(freeRunStart + i), buffer)
            writtenBytes += filled
            val ratio = (writtenBytes.toFloat() / length.toFloat()).coerceIn(0f, 1f)
            onProgress?.invoke(ratio, writtenBytes)
        }
    }
}
