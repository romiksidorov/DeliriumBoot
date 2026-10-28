package com.delirium.deliriumboot.format

import com.delirium.deliriumboot.usb.UsbWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Форматирование раздела в FAT32 "с нуля" по алгоритму из официального
 * документа Microsoft "FAT32 File System Specification" (FATGEN103).
 */
object Fat32Formatter {

    fun format(
        writer: UsbWriter,
        partitionStartLba: Long,
        partitionSectors: Long,
        volumeLabel: String = "DELIRIUM",
        onProgress: ((Float) -> Unit)? = null
    ) {
        val sectorSize = writer.sectorSize
        val reservedSectors = 32
        val numFats = 2
        val spc = chooseSectorsPerCluster(partitionSectors, sectorSize)
        val fatSize = computeFatSize(partitionSectors, reservedSectors, numFats, spc)
        val dataStartSector = reservedSectors + numFats * fatSize
        val volumeId = Random.nextInt()

        val boot = buildBootSector(sectorSize, spc, reservedSectors, numFats, partitionSectors, fatSize, volumeId, volumeLabel)
        val fsInfo = buildFsInfoSector(sectorSize)

        onProgress?.invoke(0.05f)
        writer.writeAt(partitionStartLba, boot)
        writer.writeAt(partitionStartLba + 1, fsInfo)
        writer.writeAt(partitionStartLba + 6, boot)   // резервная копия загрузочного сектора
        writer.writeAt(partitionStartLba + 7, fsInfo)
        onProgress?.invoke(0.15f)

        val fatHeader = ByteArray(sectorSize)
        val bb = ByteBuffer.wrap(fatHeader).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, 0x0FFFFFF8.toInt())
        bb.putInt(4, 0x0FFFFFFF.toInt())
        bb.putInt(8, 0x0FFFFFFF.toInt()) // единственный кластер корневого каталога — конец цепочки

        for (fatIndex in 0 until numFats) {
            val fatStart = partitionStartLba + reservedSectors + fatIndex.toLong() * fatSize
            writer.writeAt(fatStart, fatHeader)
            zeroFillRange(writer, fatStart + 1, fatSize - 1)
            onProgress?.invoke(0.15f + 0.3f * (fatIndex + 1) / numFats)
        }

        val rootDir = ByteArray(sectorSize * spc)
        writeVolumeLabelEntry(rootDir, volumeLabel)
        writer.writeAt(partitionStartLba + dataStartSector, rootDir)
        onProgress?.invoke(1f)
    }

    private fun zeroFillRange(writer: UsbWriter, startLba: Long, sectors: Long) {
        val chunk = 2048L
        var remaining = sectors
        var lba = startLba
        while (remaining > 0) {
            val n = minOf(chunk, remaining)
            writer.writeAt(lba, ByteArray((n * writer.sectorSize).toInt()))
            lba += n
            remaining -= n
        }
    }

    fun chooseSectorsPerCluster(totalSectors: Long, sectorSize: Int): Int {
        val sizeMb = (totalSectors * sectorSize) / (1024 * 1024)
        return when {
            sizeMb < 260 -> 1
            sizeMb < 8192 -> 8
            sizeMb < 16384 -> 16
            sizeMb < 32768 -> 32
            else -> 64
        }
    }

    private fun computeFatSize(totalSectors: Long, reserved: Int, numFats: Int, spc: Int): Long {
        val tmpVal1 = totalSectors - reserved
        val tmpVal2 = (256L * spc + numFats) / 2
        return (tmpVal1 + tmpVal2 - 1) / tmpVal2
    }

    private fun buildBootSector(
        sectorSize: Int, spc: Int, reserved: Int, numFats: Int,
        totalSectors: Long, fatSize: Long, volumeId: Int, label: String
    ): ByteArray {
        val b = ByteArray(sectorSize)
        b[0] = 0xEB.toByte(); b[1] = 0x58; b[2] = 0x90.toByte()
        "DELIRIUM".toByteArray(Charsets.US_ASCII).copyInto(b, 3, 0, 8)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(11, sectorSize.toShort())
        b[13] = spc.toByte()
        bb.putShort(14, reserved.toShort())
        b[16] = numFats.toByte()
        b[21] = 0xF8.toByte()
        bb.putShort(24, 32)
        bb.putShort(26, 64)
        bb.putInt(32, totalSectors.toInt())
        bb.putInt(36, fatSize.toInt())
        bb.putInt(44, 2) // RootCluster
        bb.putShort(48, 1) // FSInfo
        bb.putShort(50, 6) // BkBootSec
        b[64] = 0x80.toByte()
        b[66] = 0x29
        bb.putInt(67, volumeId)
        label.take(11).padEnd(11).toByteArray(Charsets.US_ASCII).copyInto(b, 71, 0, 11)
        "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(b, 82, 0, 8)
        b[510] = 0x55; b[511] = 0xAA.toByte()
        return b
    }

    private fun buildFsInfoSector(sectorSize: Int): ByteArray {
        val b = ByteArray(sectorSize)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, 0x41615252.toInt())
        bb.putInt(484, 0x61417272)
        bb.putInt(488, -1)
        bb.putInt(492, -1)
        b[508] = 0x00; b[509] = 0x00; b[510] = 0x55; b[511] = 0xAA.toByte()
        return b
    }

    private fun writeVolumeLabelEntry(dir: ByteArray, label: String) {
        label.take(11).padEnd(11).toByteArray(Charsets.US_ASCII).copyInto(dir, 0, 0, 11)
        dir[11] = 0x08 // ATTR_VOLUME_ID
    }
}
