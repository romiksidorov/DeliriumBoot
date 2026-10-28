package com.delirium.deliriumboot.model

/** Таблица разделов, которую выберет пользователь на экране настроек. */
enum class PartitionTableType(val label: String) {
    MBR("MBR"),
    GPT("GPT")
}

/** Файловая система основного раздела (для ISO-образов). */
enum class FileSystemType(val label: String) {
    EXFAT("exFAT"),
    NTFS("NTFS"),
    FAT32("FAT32")
}

data class InstallOptions(
    val partitionTable: PartitionTableType = PartitionTableType.GPT,
    val fileSystem: FileSystemType = FileSystemType.EXFAT,
    val secureBoot: Boolean = false,
    val align4k: Boolean = true
)

data class UsbDeviceState(
    val connected: Boolean = false,
    val hasPermission: Boolean = false,
    val deviceName: String? = null,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val capacityBytes: Long = 0L,
    val sectorSize: Int = 512
)

enum class LogLevel { INFO, WARN, ERROR, SUCCESS }

data class LogEntry(
    val level: LogLevel,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class TaskProgress(
    val running: Boolean = false,
    val title: String = "",
    val progress: Float = 0f,
    val detail: String = ""
)

data class IsoEntry(
    val name: String,
    val sizeBytes: Long,
    val uri: String
)

data class ThemeConfig(
    val menuTitle: String = "DeliriumBoot",
    val accentColorHex: String = "#B621FE",
    val timeoutSeconds: Int = 10,
    val backgroundName: String = "background.png",
    val displayMode: String = "GUI"
)
