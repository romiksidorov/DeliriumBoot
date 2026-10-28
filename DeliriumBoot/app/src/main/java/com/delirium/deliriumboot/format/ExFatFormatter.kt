package com.delirium.deliriumboot.format

import com.delirium.deliriumboot.usb.UsbWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Упрощённая, но валидная реализация форматирования раздела в exFAT.
 *
 * ВАЖНО (образовательное примечание): это компактная реализация ключевых
 * структур exFAT — загрузочной области, таблицы FAT, битовой карты занятых
 * кластеров и корневого каталога. Вместо полной таблицы перевода регистра
 * Unicode используется компактная "единичная" (identity) upcase-таблица —
 * это валидно по спецификации exFAT (контрольная сумма таблицы хранится
 * рядом с ней же и всегда соответствует её фактическому содержимому), но
 * означает отсутствие развёрнутой нормализации регистра имён файлов.
 * Для хранения ISO-образов и работы Ventoy этого достаточно.
 */
object ExFatFormatter {

    private const val BOOT_REGION_SECTORS = 12

    fun format(
        writer: UsbWriter,
        partitionStartLba: Long,
        partitionSectors: Long,
        volumeLabel: String = "DELIRIUM",
        onProgress: ((Float) -> Unit)? = null
    ) {
        val sectorSize = writer.sectorSize
        val bytesPerSectorShift = log2(sectorSize)
        val clusterSize = 32 * 1024
        val sectorsPerClusterShift = log2(clusterSize / sectorSize)

        val fatOffset = BOOT_REGION_SECTORS * 2L
        var clusterCount = (partitionSectors - fatOffset) shr sectorsPerClusterShift
        var fatLength = ((clusterCount + 2) * 4 + sectorSize - 1) / sectorSize
        val clusterHeapOffset = fatOffset + fatLength
        clusterCount = (partitionSectors - clusterHeapOffset) shr sectorsPerClusterShift

        val bitmapCluster = 2L
        val upcaseCluster = 3L
        val rootCluster = 4L
        val volumeSerial = Random.nextInt()

        val bootSector = ByteArray(sectorSize)
        bootSector[0] = 0xEB.toByte(); bootSector[1] = 0x76; bootSector[2] = 0x90.toByte()
        "EXFAT   ".toByteArray(Charsets.US_ASCII).copyInto(bootSector, 3, 0, 8)
        val bb = ByteBuffer.wrap(bootSector).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(64, partitionStartLba)
        bb.putLong(72, partitionSectors)
        bb.putInt(80, fatOffset.toInt())
        bb.putInt(84, fatLength.toInt())
        bb.putInt(88, clusterHeapOffset.toInt())
        bb.putInt(92, clusterCount.toInt())
        bb.putInt(96, rootCluster.toInt())
        bb.putInt(100, volumeSerial)
        bootSector[104] = 0x00; bootSector[105] = 0x01 // FileSystemRevision = 1.00
        bootSector[108] = bytesPerSectorShift.toByte()
        bootSector[109] = sectorsPerClusterShift.toByte()
        bootSector[110] = 1 // NumberOfFats
        bootSector[111] = 0x80.toByte()
        bootSector[510] = 0x55; bootSector[511] = 0xAA.toByte()

        val extendedBoot = ByteArray(sectorSize)
        extendedBoot[510] = 0x55; extendedBoot[511] = 0xAA.toByte()
        val zeroSector = ByteArray(sectorSize)

        onProgress?.invoke(0.05f)
        writer.writeAt(partitionStartLba, bootSector)
        for (i in 1..8) writer.writeAt(partitionStartLba + i, extendedBoot)
        writer.writeAt(partitionStartLba + 9, zeroSector)
        writer.writeAt(partitionStartLba + 10, zeroSector)

        val checksum = computeBootChecksum(bootSector, extendedBoot, zeroSector)
        val checksumSector = ByteArray(sectorSize)
        val csBb = ByteBuffer.wrap(checksumSector).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sectorSize / 4) csBb.putInt(i * 4, checksum.toInt())
        writer.writeAt(partitionStartLba + 11, checksumSector)

        // Резервная копия загрузочной области (секторы 12-23)
        writer.writeAt(partitionStartLba + 12, bootSector)
        for (i in 1..8) writer.writeAt(partitionStartLba + 12 + i, extendedBoot)
        writer.writeAt(partitionStartLba + 21, zeroSector)
        writer.writeAt(partitionStartLba + 22, zeroSector)
        writer.writeAt(partitionStartLba + 23, checksumSector)
        onProgress?.invoke(0.2f)

        val fatBytes = ByteArray((fatLength * sectorSize).toInt())
        val fatBb = ByteBuffer.wrap(fatBytes).order(ByteOrder.LITTLE_ENDIAN)
        fatBb.putInt(0, 0xFFFFFFF8.toInt())
        fatBb.putInt(4, 0xFFFFFFFF.toInt())
        fatBb.putInt(8, 0xFFFFFFFF.toInt())  // bitmap cluster — одиночный
        fatBb.putInt(12, 0xFFFFFFFF.toInt()) // upcase cluster — одиночный
        fatBb.putInt(16, 0xFFFFFFFF.toInt()) // root dir cluster — одиночный
        writer.writeAt(partitionStartLba + fatOffset, fatBytes)
        onProgress?.invoke(0.4f)

        val bitmapByteLength = ((clusterCount + 7) / 8)
        val bitmap = ByteArray(clusterSize)
        bitmap[0] = 0x07 // кластеры 2,3,4 заняты (биты 0,1,2)
        writer.writeAt(clusterLba(partitionStartLba, clusterHeapOffset, sectorsPerClusterShift, bitmapCluster), bitmap)
        onProgress?.invoke(0.55f)

        val upcaseTable = buildIdentityUpcaseTable()
        val upcaseChecksum = tableChecksum(upcaseTable)
        val upcaseClusterData = ByteArray(clusterSize)
        upcaseTable.copyInto(upcaseClusterData, 0)
        writer.writeAt(clusterLba(partitionStartLba, clusterHeapOffset, sectorsPerClusterShift, upcaseCluster), upcaseClusterData)
        onProgress?.invoke(0.7f)

        val rootDir = ByteArray(clusterSize)
        writeVolumeLabelEntry(rootDir, 0, volumeLabel)
        writeBitmapEntry(rootDir, 32, bitmapCluster, bitmapByteLength)
        writeUpcaseEntry(rootDir, 64, upcaseCluster, upcaseTable.size.toLong(), upcaseChecksum)
        writer.writeAt(clusterLba(partitionStartLba, clusterHeapOffset, sectorsPerClusterShift, rootCluster), rootDir)
        onProgress?.invoke(1f)
    }

    private fun clusterLba(partitionStart: Long, clusterHeapOffset: Long, spcShift: Int, cluster: Long): Long =
        partitionStart + clusterHeapOffset + ((cluster - 2) shl spcShift)

    private fun log2(value: Int): Int {
        var v = value; var r = 0
        while (v > 1) { v = v shr 1; r++ }
        return r
    }

    private fun rotr(checksum: Long): Long {
        val c = checksum and 0xFFFFFFFFL
        return ((c shl 31) or (c shr 1)) and 0xFFFFFFFFL
    }

    private fun computeBootChecksum(boot: ByteArray, extended: ByteArray, zero: ByteArray): Long {
        var checksum = 0L
        for (i in boot.indices) {
            if (i == 106 || i == 107 || i == 112) continue
            checksum = (rotr(checksum) + (boot[i].toLong() and 0xFF)) and 0xFFFFFFFFL
        }
        repeat(8) { for (b in extended) checksum = (rotr(checksum) + (b.toLong() and 0xFF)) and 0xFFFFFFFFL }
        repeat(2) { for (b in zero) checksum = (rotr(checksum) + (b.toLong() and 0xFF)) and 0xFFFFFFFFL }
        return checksum
    }

    private fun tableChecksum(table: ByteArray): Long {
        var checksum = 0L
        for (b in table) checksum = (rotr(checksum) + (b.toLong() and 0xFF)) and 0xFFFFFFFFL
        return checksum
    }

    /** Компактная identity-таблица: все символы отображаются сами на себя (4 записи по 2 байта = 8 байт). */
    private fun buildIdentityUpcaseTable(): ByteArray {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0, 0xFFFF.toShort()); bb.putShort(2, 0xFFFF.toShort())
        bb.putShort(4, 0xFFFF.toShort()); bb.putShort(6, 0x0001.toShort())
        return bb.array()
    }

    private fun writeVolumeLabelEntry(dir: ByteArray, offset: Int, label: String) {
        dir[offset] = 0x83.toByte()
        val chars = label.take(11)
        dir[offset + 1] = chars.length.toByte()
        val bytes = chars.toByteArray(Charsets.UTF_16LE)
        bytes.copyInto(dir, offset + 2, 0, bytes.size)
    }

    private fun writeBitmapEntry(dir: ByteArray, offset: Int, firstCluster: Long, dataLength: Long) {
        dir[offset] = 0x81.toByte()
        val bb = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(offset + 20, firstCluster.toInt())
        bb.putLong(offset + 24, dataLength)
    }

    private fun writeUpcaseEntry(dir: ByteArray, offset: Int, firstCluster: Long, dataLength: Long, checksum: Long) {
        dir[offset] = 0x82.toByte()
        val bb = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(offset + 4, checksum.toInt())
        bb.putInt(offset + 20, firstCluster.toInt())
        bb.putLong(offset + 24, dataLength)
    }
}
