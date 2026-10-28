package com.delirium.deliriumboot.format

import com.delirium.deliriumboot.usb.UsbWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

object NtfsFormatter {
    fun format(
        writer: UsbWriter,
        partitionStartLba: Long,
        partitionSectors: Long,
        volumeLabel: String = "DELIRIUM",
        onProgress: ((Float) -> Unit)? = null
    ) {
        val sectorSize = writer.sectorSize
        val spc = 8 // 4 КБ кластер при 512-байтных секторах
        val clusterSize = sectorSize * spc
        val totalClusters = partitionSectors / spc
        val mftStartCluster = 4L
        val mftMirrCluster = totalClusters / 2
        val volumeSerial = Random.nextLong()

        val bootSector = ByteArray(sectorSize)
        val bb = ByteBuffer.wrap(bootSector).order(ByteOrder.LITTLE_ENDIAN)

        bootSector[0] = 0xEB.toByte()
        bootSector[1] = 0x52.toByte()
        bootSector[2] = 0x90.toByte()
        "NTFS    ".toByteArray(Charsets.US_ASCII).copyInto(bootSector, 3, 0, 8)

        bb.putShort(11, sectorSize.toShort())
        bootSector[13] = spc.toByte()
        bb.putShort(14, 0)
        bootSector[16] = 0
        bb.putShort(17, 0)
        bb.putShort(19, 0)
        bootSector[21] = 0xF8.toByte()
        bb.putShort(22, 0)
        bb.putShort(24, 63)
        bb.putShort(26, 255)
        bb.putInt(28, partitionStartLba.toInt())
        bb.putInt(32, 0)
        bootSector[36] = 0x80.toByte()
        bootSector[37] = 0
        bootSector[38] = 0x80.toByte()
        bootSector[39] = 0
        bb.putLong(40, partitionSectors - 1)
        bb.putLong(48, mftStartCluster)
        bb.putLong(56, mftMirrCluster)
        bootSector[64] = 0xF6.toByte() // 1024 байта MFT record (2^(256-0xF6))
        bootSector[68] = 0xF6.toByte() // 1024 байта Index record
        bb.putLong(72, volumeSerial)
        bootSector[510] = 0x55
        bootSector[511] = 0xAA.toByte()

        onProgress?.invoke(0.1f)
        writer.writeAt(partitionStartLba, bootSector)
        writer.writeAt(partitionStartLba + partitionSectors - 1, bootSector)

        // Инициализация $Bitmap (первые 64 кластера заняты метаданными)
        val bitmapSectors = (totalClusters + 7) / 8 / sectorSize + 1
        val bitmap = ByteArray((bitmapSectors * sectorSize).toInt())
        bitmap[0] = 0xFF.toByte()
        bitmap[1] = 0xFF.toByte()
        bitmap[2] = 0xFF.toByte()
        bitmap[3] = 0xFF.toByte()
        bitmap[4] = 0xFF.toByte()
        bitmap[5] = 0xFF.toByte()
        bitmap[6] = 0xFF.toByte()
        bitmap[7] = 0xFF.toByte()

        val bitmapCluster = 3L
        writer.writeAt(partitionStartLba + bitmapCluster * spc, bitmap)

        onProgress?.invoke(0.6f)

        // Запись базового заголовка $MFT (Record 0)
        val mftRecord = ByteArray(1024)
        "FILE".toByteArray(Charsets.US_ASCII).copyInto(mftRecord, 0, 0, 4)
        val mftBb = ByteBuffer.wrap(mftRecord).order(ByteOrder.LITTLE_ENDIAN)
        mftBb.putShort(4, 0x30) // Offset to update sequence
        mftBb.putShort(6, 3)    // Update sequence size
        mftBb.putShort(20, 0x38) // Offset to first attribute
        mftBb.putShort(22, 1)    // In-use record
        mftBb.putInt(24, 1024)   // Bytes in use
        mftBb.putInt(28, 1024)   // Allocated size
        mftRecord[510] = 0x55
        mftRecord[511] = 0xAA.toByte()

        writer.writeAt(partitionStartLba + mftStartCluster * spc, mftRecord)
        onProgress?.invoke(1.0f)
    }
}
