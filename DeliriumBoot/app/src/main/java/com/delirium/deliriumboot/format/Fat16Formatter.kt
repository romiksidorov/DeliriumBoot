package com.delirium.deliriumboot.format

import com.delirium.deliriumboot.usb.UsbWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Форматирование служебного скрытого раздела VTOYBOOT в FAT16.
 * Раздел небольшой (по умолчанию 32 МиБ), поэтому используется простая и
 * предсказуемая геометрия: кластер 1 КиБ, 512 корневых записей.
 */
object Fat16Formatter {

    private const val SECTORS_PER_CLUSTER = 2
    private const val ROOT_ENTRY_COUNT = 512
    private const val RESERVED_SECTORS = 1
    private const val NUM_FATS = 2

    fun format(
        writer: UsbWriter,
        partitionStartLba: Long,
        partitionSectors: Long,
        volumeLabel: String = "VTOYBOOT",
        onProgress: ((Float) -> Unit)? = null
    ) {
        val sectorSize = writer.sectorSize
        val rootDirSectors = (ROOT_ENTRY_COUNT * 32 + sectorSize - 1) / sectorSize
        val tmpVal1 = partitionSectors - (RESERVED_SECTORS + rootDirSectors)
        val tmpVal2 = 256L * SECTORS_PER_CLUSTER + NUM_FATS
        val fatSize = (tmpVal1 + tmpVal2 - 1) / tmpVal2
        val volumeId = Random.nextInt()

        val boot = buildBootSector(sectorSize, partitionSectors, fatSize, volumeId, volumeLabel)
        onProgress?.invoke(0.1f)
        writer.writeAt(partitionStartLba, boot)

        val fatHeader = ByteArray(sectorSize)
        val bb = ByteBuffer.wrap(fatHeader).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0, 0xFFF8.toShort())
        bb.putShort(2, 0xFFFF.toShort())

        for (i in 0 until NUM_FATS) {
            val fatStart = partitionStartLba + RESERVED_SECTORS + i.toLong() * fatSize
            writer.writeAt(fatStart, fatHeader)
            zeroFillRange(writer, fatStart + 1, fatSize - 1)
            onProgress?.invoke(0.1f + 0.7f * (i + 1) / NUM_FATS)
        }

        val rootDirStart = partitionStartLba + RESERVED_SECTORS + NUM_FATS * fatSize
        val rootDir = ByteArray(rootDirSectors * sectorSize)
        volumeLabel.take(11).padEnd(11).toByteArray(Charsets.US_ASCII).copyInto(rootDir, 0, 0, 11)
        rootDir[11] = 0x08
        writer.writeAt(rootDirStart, rootDir)
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

    private fun buildBootSector(
        sectorSize: Int, totalSectors: Long, fatSize: Long,
        volumeId: Int, label: String
    ): ByteArray {
        val b = ByteArray(sectorSize)
        b[0] = 0xEB.toByte(); b[1] = 0x3C; b[2] = 0x90.toByte()
        "DELIRIUM".toByteArray(Charsets.US_ASCII).copyInto(b, 3, 0, 8)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(11, sectorSize.toShort())
        b[13] = SECTORS_PER_CLUSTER.toByte()
        bb.putShort(14, RESERVED_SECTORS.toShort())
        b[16] = NUM_FATS.toByte()
        bb.putShort(17, ROOT_ENTRY_COUNT.toShort())
        if (totalSectors < 65536) bb.putShort(19, totalSectors.toShort()) else bb.putShort(19, 0)
        b[21] = 0xF8.toByte()
        bb.putShort(22, fatSize.toShort())
        bb.putShort(24, 32)
        bb.putShort(26, 64)
        if (totalSectors >= 65536) bb.putInt(32, totalSectors.toInt()) else bb.putInt(32, 0)
        b[36] = 0x80.toByte()
        b[38] = 0x29
        bb.putInt(39, volumeId)
        label.take(11).padEnd(11).toByteArray(Charsets.US_ASCII).copyInto(b, 43, 0, 11)
        "FAT16   ".toByteArray(Charsets.US_ASCII).copyInto(b, 54, 0, 8)
        b[510] = 0x55; b[511] = 0xAA.toByte()
        return b
    }
}
