package com.delirium.deliriumboot.ventoy

import com.delirium.deliriumboot.fs.ExFatFileWriter
import com.delirium.deliriumboot.fs.Fat32FileWriter
import com.delirium.deliriumboot.model.FileSystemType
import com.delirium.deliriumboot.model.ThemeConfig
import com.delirium.deliriumboot.usb.UsbWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Генератор файла конфигурации темы Ventoy (ventoy.json) и запись брендинга
 * DeliriumBoot (фон, акцентный цвет, заголовок меню) в каталог /ventoy на
 * основном разделе накопителя.
 */
class VentoyThemeManager(private val fileSystem: FileSystemType) {

    fun buildVentoyJson(theme: ThemeConfig): String {
        val root = JSONObject()

        val themeObj = JSONObject().apply {
            put("file", "/ventoy/theme/${theme.backgroundName}")
            put("gfxmode", "1920x1080")
            put("boot_menu_language", "ru-RU")
            put("display_mode", theme.displayMode)
            put("ventoy_left", "10%")
            put("ventoy_top", "68%")
            put("ventoy_color", theme.accentColorHex)
            put("ventoy_width", "80%")
        }
        root.put("theme", themeObj)

        val control = JSONArray().apply {
            put(JSONObject().put("VTOY_DEFAULT_MENU_MODE", "0"))
            put(JSONObject().put("VTOY_TIMEOUT", theme.timeoutSeconds.toString()))
            put(JSONObject().put("VTOY_SECONDARY_TIMEOUT", "5"))
            put(JSONObject().put("VTOY_MENU_LANGUAGE", "ru_RU"))
        }
        root.put("control", control)

        root.put("menu_class", JSONArray().put(JSONObject().apply {
            put("key", "image")
            put("class", "delirium_iso")
        }))
        root.put("menu_alias", JSONArray())
        root.put("menu_title", JSONObject().put("name", theme.menuTitle))

        return root.toString(2)
    }

    /** Записывает ventoy.json и фоновое изображение на основной раздел. */
    fun applyTheme(
        writer: UsbWriter,
        partitionStartLba: Long,
        theme: ThemeConfig,
        backgroundPng: ByteArray,
        onLog: (String) -> Unit
    ) {
        if (fileSystem == FileSystemType.NTFS) {
            onLog("⚠ Запись темы на NTFS пока не поддерживается в этой версии приложения.")
            return
        }

        val json = buildVentoyJson(theme).toByteArray(Charsets.UTF_8)

        when (fileSystem) {
            FileSystemType.FAT32 -> {
                val info = Fat32FileWriter.readInfo(writer, partitionStartLba)
                Fat32FileWriter.writeFile(writer, info, "ventoy/ventoy.json", json.size.toLong(), ByteArrayInputStream(json))
                Fat32FileWriter.writeFile(writer, info, "ventoy/theme/${theme.backgroundName}", backgroundPng.size.toLong(), ByteArrayInputStream(backgroundPng))
            }
            FileSystemType.EXFAT -> {
                val info = ExFatFileWriter.readInfo(writer, partitionStartLba)
                ExFatFileWriter.writeFile(writer, info, "ventoy/ventoy.json", json.size.toLong(), ByteArrayInputStream(json))
                ExFatFileWriter.writeFile(writer, info, "ventoy/theme/${theme.backgroundName}", backgroundPng.size.toLong(), ByteArrayInputStream(backgroundPng))
            }
            FileSystemType.NTFS -> Unit
        }
        onLog("✔ Тема «${theme.menuTitle}» применена (акцент ${theme.accentColorHex}, таймаут ${theme.timeoutSeconds} с).")
    }
}
