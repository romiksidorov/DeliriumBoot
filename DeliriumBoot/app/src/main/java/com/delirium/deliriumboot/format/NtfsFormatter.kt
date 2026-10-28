package com.delirium.deliriumboot.format

import com.delirium.deliriumboot.usb.UsbWriter

/**
 * NTFS — журналируемая файловая система со сложной внутренней структурой
 * (MFT, журнал $LogFile, системные метафайлы и т.д.). Корректная реализация
 * форматирования NTFS "с нуля" выходит далеко за рамки учебного проекта и
 * обычно требует использования готовых библиотек уровня ntfs-3g/mkntfs.
 *
 * Чтобы не создавать "битый" NTFS-том, эта реализация явно сообщает о
 * текущем ограничении вместо имитации форматирования. Рекомендуется
 * выбрать exFAT или FAT32 в приложении, либо отформатировать раздел в NTFS
 * заранее на компьютере, а DeliriumBoot использовать только для разметки,
 * записи загрузчика и копирования ISO-образов на уже готовый раздел.
 */
object NtfsFormatter {
    fun format(
        writer: UsbWriter,
        partitionStartLba: Long,
        partitionSectors: Long,
        volumeLabel: String = "DELIRIUM",
        onProgress: ((Float) -> Unit)? = null
    ) {
        throw UnsupportedOperationException(
            "Форматирование в NTFS пока не поддерживается в этой версии DeliriumBoot. " +
                "Выберите exFAT или FAT32, либо отформатируйте раздел в NTFS заранее на ПК."
        )
    }
}
