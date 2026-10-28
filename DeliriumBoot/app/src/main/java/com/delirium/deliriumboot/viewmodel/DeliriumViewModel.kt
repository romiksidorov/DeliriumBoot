package com.delirium.deliriumboot.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.delirium.deliriumboot.format.Fat32Formatter
import com.delirium.deliriumboot.iso.IsoManager
import com.delirium.deliriumboot.model.FileSystemType
import com.delirium.deliriumboot.model.InstallOptions
import com.delirium.deliriumboot.model.IsoEntry
import com.delirium.deliriumboot.model.LogEntry
import com.delirium.deliriumboot.model.LogLevel
import com.delirium.deliriumboot.model.PartitionTableType
import com.delirium.deliriumboot.model.TaskProgress
import com.delirium.deliriumboot.model.ThemeConfig
import com.delirium.deliriumboot.model.UsbDeviceState
import com.delirium.deliriumboot.partition.PartitionPlan
import com.delirium.deliriumboot.usb.UsbController
import com.delirium.deliriumboot.usb.UsbWriter
import com.delirium.deliriumboot.ventoy.VentoyInstaller
import com.delirium.deliriumboot.ventoy.VentoyThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeliriumViewModel(application: Application) : AndroidViewModel(application) {

    val usbController = UsbController(application)

    private val _usbState = MutableStateFlow(UsbDeviceState())
    val usbState: StateFlow<UsbDeviceState> = _usbState.asStateFlow()

    private val _options = MutableStateFlow(InstallOptions())
    val options: StateFlow<InstallOptions> = _options.asStateFlow()

    private val _theme = MutableStateFlow(ThemeConfig())
    val theme: StateFlow<ThemeConfig> = _theme.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _progress = MutableStateFlow(TaskProgress())
    val progress: StateFlow<TaskProgress> = _progress.asStateFlow()

    private val _isoList = MutableStateFlow<List<IsoEntry>>(emptyList())
    val isoList: StateFlow<List<IsoEntry>> = _isoList.asStateFlow()

    private var currentPlan: PartitionPlan? = null

    init {
        usbController.register()
        viewModelScope.launch {
            usbController.device.collect { device ->
                _usbState.update {
                    it.copy(
                        connected = device != null,
                        deviceName = device?.productName ?: device?.deviceName,
                        vendorId = device?.vendorId ?: 0,
                        productId = device?.productId ?: 0
                    )
                }
            }
        }
        viewModelScope.launch {
            usbController.hasPermission.collect { granted ->
                _usbState.update { it.copy(hasPermission = granted) }
                if (granted) refreshCapacity()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        usbController.unregister()
    }

    fun log(level: LogLevel, message: String) {
        _logs.update { it + LogEntry(level, message) }
    }

    fun requestUsbAccess() {
        val device = usbController.findMassStorageDevice()
        if (device == null) {
            log(LogLevel.WARN, "USB-накопитель не найден. Подключите флешку через OTG-переходник.")
            return
        }
        usbController.requestPermission(device) { granted ->
            if (granted) {
                log(LogLevel.SUCCESS, "Доступ к USB-накопителю получен.")
            } else {
                log(LogLevel.ERROR, "Пользователь отклонил доступ к USB-устройству.")
            }
        }
    }

    private fun refreshCapacity() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withWriter { writer ->
                    _usbState.update {
                        it.copy(capacityBytes = writer.capacityBytes(), sectorSize = writer.sectorSize)
                    }
                }
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Не удалось прочитать параметры накопителя: ${e.message}")
            }
        }
    }

    fun updateOptions(block: (InstallOptions) -> InstallOptions) {
        _options.update(block)
    }

    fun updateTheme(block: (ThemeConfig) -> ThemeConfig) {
        _theme.update(block)
    }

    private inline fun <T> withWriter(block: (UsbWriter) -> T): T {
        val device = usbController.device.value ?: throw IllegalStateException("USB-устройство не подключено")
        val writer = UsbWriter(usbController.managerInstance(), device)
        writer.open()
        try {
            return block(writer)
        } finally {
            writer.close()
        }
    }

    fun install() = runTask("Установка DeliriumBoot") { writer ->
        val installer = VentoyInstaller(getApplication(), writer)
        val plan = installer.install(
            options = _options.value,
            onProgress = { p -> _progress.update { it.copy(progress = p) } },
            onLog = { msg -> log(LogLevel.INFO, msg) }
        )
        currentPlan = plan

        // Применяем тему DeliriumBoot по умолчанию сразу после установки
        val themeManager = VentoyThemeManager(_options.value.fileSystem)
        val background = loadDefaultBackground()
        themeManager.applyTheme(writer, plan.part1StartLba, _theme.value, background) { msg -> log(LogLevel.INFO, msg) }
    }

    fun updateBootloader() = runTask("Обновление загрузчика") { writer ->
        val plan = currentPlan ?: run {
            // Пересчитываем план на основе уже существующей разметки диска
            com.delirium.deliriumboot.partition.PartitionTableBuilder.buildPlan(
                writer.sectorSize, writer.totalSectors, _options.value.align4k
            )
        }
        val installer = VentoyInstaller(getApplication(), writer)
        installer.updateBootloaderOnly(
            plan, _options.value,
            onProgress = { p -> _progress.update { it.copy(progress = p) } },
            onLog = { msg -> log(LogLevel.INFO, msg) }
        )
    }

    fun applyTheme() = runTask("Применение темы DeliriumBoot") { writer ->
        val plan = currentPlan ?: com.delirium.deliriumboot.partition.PartitionTableBuilder.buildPlan(
            writer.sectorSize, writer.totalSectors, _options.value.align4k
        )
        val themeManager = VentoyThemeManager(_options.value.fileSystem)
        val background = loadDefaultBackground()
        themeManager.applyTheme(writer, plan.part1StartLba, _theme.value, background) { msg -> log(LogLevel.INFO, msg) }
    }

    fun addIsoFiles(uris: List<Uri>) = runTask("Добавление ISO-образов") { writer ->
        val plan = currentPlan ?: com.delirium.deliriumboot.partition.PartitionTableBuilder.buildPlan(
            writer.sectorSize, writer.totalSectors, _options.value.align4k
        )
        val manager = IsoManager(getApplication<Application>().contentResolver, _options.value.fileSystem)
        for (uri in uris) {
            val file = manager.resolveFile(uri)
            manager.copyToDevice(
                writer, plan, file,
                onProgress = { p -> _progress.update { it.copy(progress = p, detail = file.name) } },
                onLog = { msg -> log(LogLevel.INFO, msg) }
            )
            _isoList.update { it + IsoEntry(file.name, file.size, uri.toString()) }
        }
    }

    private fun loadDefaultBackground(): ByteArray {
        return try {
            getApplication<Application>().assets.open("ventoy/theme/background.png").use { it.readBytes() }
        } catch (e: Exception) {
            // Если фон не упакован в assets — создаём минимальный валидный PNG 1x1,
            // чтобы ventoy.json всё равно указывал на существующий файл.
            MINIMAL_PNG
        }
    }

    private fun runTask(title: String, block: (UsbWriter) -> Unit) {
        viewModelScope.launch {
            _progress.value = TaskProgress(running = true, title = title, progress = 0f)
            try {
                withContext(Dispatchers.IO) {
                    withWriter { writer -> block(writer) }
                }
                log(LogLevel.SUCCESS, "$title: готово.")
            } catch (e: Exception) {
                log(LogLevel.ERROR, "$title: ошибка — ${e.message}")
            } finally {
                _progress.value = TaskProgress(running = false, title = title, progress = 1f)
            }
        }
    }

    companion object {
        // Минимальный валидный PNG 1×1 (чёрный пиксель) — запасной вариант, если
        // в assets/ventoy/theme/background.png не упакован собственный файл.
        private val MINIMAL_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
            0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, 0xD7.toByte(), 0x63, 0x60, 0x60, 0x60, 0x00,
            0x00, 0x00, 0x04, 0x00, 0x01, 0xF6.toByte(), 0x17.toByte(), 0xA7.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
    }
}
