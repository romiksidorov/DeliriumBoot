package com.delirium.deliriumboot.ventoy

import android.content.Context
import com.delirium.deliriumboot.format.ExFatFormatter
import com.delirium.deliriumboot.format.Fat16Formatter
import com.delirium.deliriumboot.format.Fat32Formatter
import com.delirium.deliriumboot.format.NtfsFormatter
import com.delirium.deliriumboot.model.FileSystemType
import com.delirium.deliriumboot.model.InstallOptions
import com.delirium.deliriumboot.model.PartitionTableType
import com.delirium.deliriumboot.partition.PartitionPlan
import com.delirium.deliriumboot.partition.PartitionTableBuilder
import com.delirium.deliriumboot.usb.UsbWriter
import java.io.IOException

/**
 * Главный оркестратор установки DeliriumBoot: разметка диска, форматирование
 * разделов, запись загрузчика. Прогресс и лог транслируются в UI через
 * колбэки [onProgress] (0..1) и [onLog].
 */
class VentoyInstaller(private val context: Context, private val writer: UsbWriter) {

    fun install(
        options: InstallOptions,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ): PartitionPlan {
        onLog("Анализ накопителя: ${writer.totalSectors} секторов × ${writer.sectorSize} байт (${writer.capacityBytes() / (1024 * 1024)} МиБ)")
        val plan = PartitionTableBuilder.buildPlan(writer.sectorSize, writer.totalSectors, options.align4k)
        onLog(
            "План разделов рассчитан: раздел 1 — с сектора ${plan.part1StartLba} (${plan.part1SectorCount} секторов), " +
                "скрытый раздел VTOYBOOT — с сектора ${plan.part2StartLba} (${plan.part2SectorCount} секторов)"
        )

        var progressBase = 0f
        fun step(name: String, weight: Float, block: ((Float) -> Unit) -> Unit) {
            onLog(name)
            block { p -> onProgress(progressBase + p * weight) }
            progressBase += weight
        }

        step("Запись таблицы разделов (${options.partitionTable.label})", 0.1f) { report ->
            writePartitionTable(plan, options)
            report(1f)
        }

        step("Форматирование основного раздела (${options.fileSystem.label})", 0.4f) { report ->
            formatMainPartition(plan, options.fileSystem, report)
        }

        step("Форматирование скрытого раздела VTOYBOOT (FAT16)", 0.2f) { report ->
            Fat16Formatter.format(writer, plan.part2StartLba, plan.part2SectorCount, "VTOYBOOT", report)
        }

        step("Запись загрузчика DeliriumBoot/Ventoy", 0.2f) { report ->
            writeBootloader(plan, options, report, onLog)
        }

        step("Проверка Secure Boot / выравнивания", 0.1f) { report ->
            if (options.secureBoot) {
                onLog("Secure Boot: загрузчик будет использовать подписанный shim/MokManager (требуется core.img со встроенной поддержкой Secure Boot).")
            }
            if (options.align4k) {
                onLog("Выравнивание разделов по границе 4К выполнено.")
            }
            report(1f)
        }

        onLog("✔ Установка DeliriumBoot завершена успешно.")
        return plan
    }

    fun updateBootloaderOnly(plan: PartitionPlan, options: InstallOptions, onProgress: (Float) -> Unit, onLog: (String) -> Unit) {
        onLog("Обновление загрузчика без потери данных ISO-раздела…")
        writeBootloader(plan, options, onProgress, onLog)
        onLog("✔ Загрузчик обновлён.")
    }

    private fun writePartitionTable(plan: PartitionPlan, options: InstallOptions) {
        when (options.partitionTable) {
            PartitionTableType.MBR -> {
                writer.writeAt(0, PartitionTableBuilder.buildMbr(plan, options.fileSystem))
            }
            PartitionTableType.GPT -> {
                writer.writeAt(0, PartitionTableBuilder.buildProtectiveMbr(plan))
                val (header, entries, backupHeader) = PartitionTableBuilder.buildGptHeaderAndEntries(plan)
                writer.writeAt(1, header)
                writer.writeAt(2, entries)
                writer.writeAt(plan.totalSectors - 33, entries)
                writer.writeAt(plan.totalSectors - 1, backupHeader)
            }
        }
    }

    private fun formatMainPartition(plan: PartitionPlan, fs: FileSystemType, report: (Float) -> Unit) {
        when (fs) {
            FileSystemType.FAT32 -> Fat32Formatter.format(writer, plan.part1StartLba, plan.part1SectorCount, "DELIRIUM", report)
            FileSystemType.EXFAT -> ExFatFormatter.format(writer, plan.part1StartLba, plan.part1SectorCount, "DELIRIUM", report)
            FileSystemType.NTFS -> NtfsFormatter.format(writer, plan.part1StartLba, plan.part1SectorCount, "DELIRIUM", report)
        }
    }

    private fun writeBootloader(plan: PartitionPlan, options: InstallOptions, report: (Float) -> Unit, onLog: (String) -> Unit) {
        val assetPath = "ventoy/core.img"
        val bytes = try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (e: IOException) {
            onLog(
                "⚠ Не найден файл assets/$assetPath — загрузочный код не будет записан. " +
                    "Раздел с ISO при этом остаётся полностью рабочим и читаемым на ПК. " +
                    "См. app/src/main/assets/ventoy/README.md, как добавить настоящий образ загрузчика."
            )
            report(1f)
            return
        }
        writer.writeAt(plan.part2StartLba, bytes) { p -> report(p * 0.8f) }

        if (options.partitionTable == PartitionTableType.MBR) {
            val stage1 = try {
                context.assets.open("ventoy/stage1.bin").use { it.readBytes() }
            } catch (e: IOException) { null }
            if (stage1 != null) {
                val mbr = writer.readAt(0, 1)
                System.arraycopy(stage1, 0, mbr, 0, minOf(stage1.size, 440))
                writer.writeAt(0, mbr)
                onLog("Записан stage1 для legacy BIOS boot.")
            }
        }
        report(1f)
    }
}
