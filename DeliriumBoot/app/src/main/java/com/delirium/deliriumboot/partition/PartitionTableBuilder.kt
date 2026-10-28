package com.delirium.deliriumboot.partition

import com.delirium.deliriumboot.model.FileSystemType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * Схема раздела, рассчитанная для конкретного накопителя.
 *
 * Partition 1 — основной раздел для ISO-образов (exFAT/NTFS/FAT32).
 * Partition 2 — скрытый служебный раздел VTOYBOOT (FAT16, ~32 МиБ),
 * в который записывается бинарный образ загрузчика.
 */
data class PartitionPlan(
    val sectorSize: Int,
    val totalSectors: Long,
    val part1StartLba: Long,
    val part1SectorCount: Long,
    val part2StartLba: Long,
    val part2SectorCount: Long
)

/**
 * Построение таблиц разделов MBR и GPT "с нуля" — без root и без внешних
 * утилит вроде fdisk/parted, которые на обычном Android недоступны.
 */
object PartitionTableBuilder {

    private const val HIDDEN_PART_MB = 32L
    const val GUID_MS_BASIC_DATA = "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"
    const val GUID_EFI_SYSTEM = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"

    fun buildPlan(sectorSize: Int, totalSectors: Long, align4k: Boolean): PartitionPlan {
        val alignSectors = if (align4k) (4096 / sectorSize).coerceAtLeast(1).toLong() else 1L

        // Первый раздел традиционно начинается с отступа в 1 МиБ.
        var part1Start = (1L * 1024 * 1024) / sectorSize
        part1Start = alignUp(part1Start, alignSectors)

        val hiddenSectors = (HIDDEN_PART_MB * 1024 * 1024) / sectorSize
        val part2Sectors = alignUp(hiddenSectors, alignSectors)

        val usableEnd = totalSectors - 34 // резерв под backup GPT в конце диска
        val part2Start = alignUp(usableEnd - part2Sectors, alignSectors)
        val part1Sectors = part2Start - part1Start

        return PartitionPlan(
            sectorSize = sectorSize,
            totalSectors = totalSectors,
            part1StartLba = part1Start,
            part1SectorCount = part1Sectors,
            part2StartLba = part2Start,
            part2SectorCount = part2Sectors
        )
    }

    private fun alignUp(value: Long, align: Long): Long {
        if (align <= 1) return value
        val rem = value % align
        return if (rem == 0L) value else value + (align - rem)
    }

    // ------------------------------------------------------------------
    // MBR
    // ------------------------------------------------------------------

    fun buildMbr(plan: PartitionPlan, fileSystem: FileSystemType): ByteArray {
        val mbr = ByteArray(plan.sectorSize)
        mbr[510] = 0x55
        mbr[511] = 0xAA.toByte()

        writeMbrEntry(mbr, 0, false, mbrTypeFor(fileSystem), plan.part1StartLba, plan.part1SectorCount)
        writeMbrEntry(mbr, 1, false, 0xEF, plan.part2StartLba, plan.part2SectorCount) // VTOYBOOT (EFI System)
        return mbr
    }

    private fun mbrTypeFor(fs: FileSystemType): Int = when (fs) {
        FileSystemType.FAT32 -> 0x0C // FAT32 LBA
        FileSystemType.NTFS -> 0x07
        FileSystemType.EXFAT -> 0x07 // NTFS/exFAT используют общий код раздела 0x07
    }

    private fun writeMbrEntry(
        mbr: ByteArray, index: Int, bootable: Boolean, type: Int,
        startLba: Long, sectorCount: Long
    ) {
        val base = 446 + index * 16
        mbr[base] = if (bootable) 0x80.toByte() else 0x00
        // CHS не критичен для современных флеш-накопителей — используем "максимальные" значения
        mbr[base + 1] = 0xFE.toByte(); mbr[base + 2] = 0xFF.toByte(); mbr[base + 3] = 0xFF.toByte()
        mbr[base + 4] = type.toByte()
        mbr[base + 5] = 0xFE.toByte(); mbr[base + 6] = 0xFF.toByte(); mbr[base + 7] = 0xFF.toByte()
        putLe32(mbr, base + 8, startLba)
        putLe32(mbr, base + 12, sectorCount)
    }

    // ------------------------------------------------------------------
    // GPT
    // ------------------------------------------------------------------

    fun buildProtectiveMbr(plan: PartitionPlan): ByteArray {
        val mbr = ByteArray(plan.sectorSize)
        mbr[510] = 0x55; mbr[511] = 0xAA.toByte()
        val sizeInSectors = if (plan.totalSectors - 1 > 0xFFFFFFFFL) 0xFFFFFFFFL else plan.totalSectors - 1
        writeMbrEntry(mbr, 0, false, 0xEE, 1, sizeInSectors)
        return mbr
    }

    /** @return Triple(основной заголовок GPT, массив записей разделов, резервный заголовок GPT) */
    fun buildGptHeaderAndEntries(plan: PartitionPlan): Triple<ByteArray, ByteArray, ByteArray> {
        val entriesBytes = ByteArray(128 * 128)
        writeGptEntry(entriesBytes, 0, GUID_MS_BASIC_DATA, "DELIRIUM-ISO", plan.part1StartLba, plan.part1StartLba + plan.part1SectorCount - 1)
        writeGptEntry(entriesBytes, 1, GUID_EFI_SYSTEM, "VTOYBOOT", plan.part2StartLba, plan.part2StartLba + plan.part2SectorCount - 1)
        val entriesCrc = crc32(entriesBytes)

        val primaryHeader = buildGptHeader(
            currentLba = 1L, backupLba = plan.totalSectors - 1,
            firstUsable = 34L, lastUsable = plan.totalSectors - 34,
            partEntriesLba = 2L, partEntriesCrc = entriesCrc, sectorSize = plan.sectorSize
        )
        val backupHeader = buildGptHeader(
            currentLba = plan.totalSectors - 1, backupLba = 1L,
            firstUsable = 34L, lastUsable = plan.totalSectors - 34,
            partEntriesLba = plan.totalSectors - 33, partEntriesCrc = entriesCrc, sectorSize = plan.sectorSize
        )
        return Triple(primaryHeader, entriesBytes, backupHeader)
    }

    private val diskGuid: ByteArray by lazy { guidToBytes(UUID.randomUUID()) }

    private fun buildGptHeader(
        currentLba: Long, backupLba: Long, firstUsable: Long, lastUsable: Long,
        partEntriesLba: Long, partEntriesCrc: Long, sectorSize: Int
    ): ByteArray {
        val header = ByteArray(sectorSize)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("EFI PART".toByteArray(Charsets.US_ASCII))
        bb.putInt(0x00010000) // revision 1.0
        bb.putInt(92)         // header size
        bb.putInt(0)          // crc32 — заполним позже
        bb.putInt(0)          // reserved
        bb.putLong(currentLba)
        bb.putLong(backupLba)
        bb.putLong(firstUsable)
        bb.putLong(lastUsable)
        bb.put(diskGuid)
        bb.putLong(partEntriesLba)
        bb.putInt(128) // число записей
        bb.putInt(128) // размер записи
        bb.putInt(partEntriesCrc.toInt())

        val headerOnly = header.copyOf(92)
        headerOnly[16] = 0; headerOnly[17] = 0; headerOnly[18] = 0; headerOnly[19] = 0
        val headerCrc = crc32(headerOnly)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(16, headerCrc.toInt())
        return header
    }

    private fun writeGptEntry(buf: ByteArray, index: Int, typeGuid: String, name: String, startLba: Long, endLba: Long) {
        val base = index * 128
        guidToBytes(UUID.fromString(typeGuid)).copyInto(buf, base)
        guidToBytes(UUID.randomUUID()).copyInto(buf, base + 16)
        putLe64(buf, base + 32, startLba)
        putLe64(buf, base + 40, endLba)
        putLe64(buf, base + 48, 0L)
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        nameBytes.copyInto(buf, base + 56, 0, minOf(nameBytes.size, 72))
    }

    /** GUID в "перемешанном" порядке байт, который требует спецификация GPT. */
    private fun guidToBytes(uuid: UUID): ByteArray {
        val out = ByteArray(16)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(0, uuid.mostSignificantBits)
        bb.putLong(8, uuid.leastSignificantBits)
        fun swap(a: Int, b: Int) { val t = out[a]; out[a] = out[b]; out[b] = t }
        swap(0, 3); swap(1, 2); swap(4, 5); swap(6, 7)
        return out
    }

    private fun crc32(data: ByteArray): Long {
        val c = CRC32(); c.update(data); return c.value
    }

    private fun putLe32(arr: ByteArray, offset: Int, value: Long) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putLe64(arr: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) arr[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }
}
