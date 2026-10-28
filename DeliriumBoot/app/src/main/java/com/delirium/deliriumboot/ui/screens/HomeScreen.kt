package com.delirium.deliriumboot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.delirium.deliriumboot.model.FileSystemType
import com.delirium.deliriumboot.model.InstallOptions
import com.delirium.deliriumboot.model.LogEntry
import com.delirium.deliriumboot.model.PartitionTableType
import com.delirium.deliriumboot.model.TaskProgress
import com.delirium.deliriumboot.model.UsbDeviceState
import com.delirium.deliriumboot.ui.components.LogConsole
import com.delirium.deliriumboot.ui.components.OptionCheckbox
import com.delirium.deliriumboot.ui.components.PrimaryActionButton
import com.delirium.deliriumboot.ui.components.ProgressSection
import com.delirium.deliriumboot.ui.components.SecondaryActionButton
import com.delirium.deliriumboot.ui.components.SectionCard
import com.delirium.deliriumboot.ui.components.SegmentedOption
import com.delirium.deliriumboot.ui.components.UsbStatusCard
import com.delirium.deliriumboot.ui.theme.DeliriumSecondary

@Composable
fun HomeScreen(
    usbState: UsbDeviceState,
    options: InstallOptions,
    progress: TaskProgress,
    logs: List<LogEntry>,
    onRequestUsbAccess: () -> Unit,
    onOptionsChange: (InstallOptions) -> Unit,
    onInstall: () -> Unit,
    onUpdateBootloader: () -> Unit,
    onOpenIsoManager: () -> Unit,
    onOpenThemeManager: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    "DeliriumBoot",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text("v1.0 · открытый аналог Ventoy без root и без рекламы", color = DeliriumSecondary)
            }
        }

        item {
            UsbStatusCard(
                connected = usbState.connected,
                hasPermission = usbState.hasPermission,
                deviceName = usbState.deviceName,
                capacityMb = usbState.capacityBytes / (1024 * 1024),
                onRequestAccess = onRequestUsbAccess
            )
        }

        item {
            SectionCard("Таблица разделов") {
                SegmentedOption(
                    options = listOf("MBR", "GPT"),
                    selectedIndex = if (options.partitionTable == PartitionTableType.MBR) 0 else 1,
                    onSelect = { i ->
                        onOptionsChange(options.copy(partitionTable = if (i == 0) PartitionTableType.MBR else PartitionTableType.GPT))
                    }
                )
            }
        }

        item {
            SectionCard("Файловая система основного раздела") {
                SegmentedOption(
                    options = listOf("exFAT", "NTFS", "FAT32"),
                    selectedIndex = when (options.fileSystem) {
                        FileSystemType.EXFAT -> 0
                        FileSystemType.NTFS -> 1
                        FileSystemType.FAT32 -> 2
                    },
                    onSelect = { i ->
                        val fs = when (i) { 0 -> FileSystemType.EXFAT; 1 -> FileSystemType.NTFS; else -> FileSystemType.FAT32 }
                        onOptionsChange(options.copy(fileSystem = fs))
                    }
                )
            }
        }

        item {
            SectionCard("Дополнительные настройки") {
                OptionCheckbox("Поддержка Secure Boot", options.secureBoot) {
                    onOptionsChange(options.copy(secureBoot = it))
                }
                OptionCheckbox("Выравнивание 4K", options.align4k) {
                    onOptionsChange(options.copy(align4k = it))
                }
            }
        }

        if (progress.running) {
            item {
                SectionCard("Выполняется") {
                    ProgressSection(progress.title, progress.progress, progress.detail)
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryActionButton("УСТАНОВИТЬ DELIRIUMBOOT", enabled = usbState.connected && usbState.hasPermission && !progress.running) {
                    onInstall()
                }
                SecondaryActionButton("ОБНОВИТЬ ЗАГРУЗЧИК", enabled = usbState.connected && usbState.hasPermission && !progress.running) {
                    onUpdateBootloader()
                }
                SecondaryActionButton("ДОБАВИТЬ ISO ОБРАЗ", enabled = usbState.connected && usbState.hasPermission && !progress.running) {
                    onOpenIsoManager()
                }
                SecondaryActionButton("МЕНЕДЖЕР ТЕМ DELIRIUM", enabled = !progress.running) {
                    onOpenThemeManager()
                }
            }
        }

        item {
            Text("Журнал операций", style = MaterialTheme.typography.titleMedium)
        }
        item {
            LogConsole(logs)
        }
    }
}
