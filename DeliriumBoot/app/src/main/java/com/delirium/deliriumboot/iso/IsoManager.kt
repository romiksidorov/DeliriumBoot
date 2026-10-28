package com.delirium.deliriumboot.iso

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.delirium.deliriumboot.fs.ExFatFileWriter
import com.delirium.deliriumboot.fs.Fat32FileWriter
import com.delirium.deliriumboot.model.FileSystemType
import com.delirium.deliriumboot.partition.PartitionPlan
import com.delirium.deliriumboot.usb.UsbWriter
import java.io.IOException

/**
 * Копирование ISO/IMG-образов, выбранных пользователем через Storage Access
 * Framework, на основной раздел накопителя (прямая побайтовая запись, без
 * промежуточного кеша на диске телефона).
 */
class IsoManager(private val contentResolver: ContentResolver, private val fileSystem: FileSystemType) {

    data class PickedFile(val uri: Uri, val name: String, val size: Long)

    fun resolveFile(uri: Uri): PickedFile {
        var name = "image.iso"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return PickedFile(uri, name, size)
    }

    fun copyToDevice(
        writer: UsbWriter,
        plan: PartitionPlan,
        file: PickedFile,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ) {
        if (fileSystem == FileSystemType.NTFS) {
            throw IOException("Копирование на NTFS пока не поддерживается в этой версии приложения.")
        }
        onLog("Копирование «${file.name}» (${file.size / (1024 * 1024)} МиБ)…")
        val input = contentResolver.openInputStream(file.uri)
            ?: throw IOException("Не удалось открыть выбранный файл")

        input.use { stream ->
            when (fileSystem) {
                FileSystemType.FAT32 -> {
                    val info = Fat32FileWriter.readInfo(writer, plan.part1StartLba)
                    Fat32FileWriter.writeFile(writer, info, file.name, file.size, stream, onProgress)
                }
                FileSystemType.EXFAT -> {
                    val info = ExFatFileWriter.readInfo(writer, plan.part1StartLba)
                    ExFatFileWriter.writeFile(writer, info, file.name, file.size, stream, onProgress)
                }
                FileSystemType.NTFS -> Unit
            }
        }
        onLog("✔ «${file.name}» успешно записан на DeliriumBoot-накопитель.")
    }
}
