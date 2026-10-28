package com.delirium.deliriumboot.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * Высокоуровневая обёртка над [ScsiBulkOnlyTransport] для последовательного
 * чтения/записи произвольных диапазонов секторов: MBR, GPT, образ
 * загрузчика, структуры файловых систем и пользовательские файлы.
 *
 * Это "сердце" DeliriumBoot — именно через этот класс происходит вся работа
 * с накопителем без использования root-прав: вся передача данных идёт через
 * android.hardware.usb.UsbDeviceConnection.bulkTransfer().
 */
class UsbWriter(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    private var transport: ScsiBulkOnlyTransport? = null

    var sectorSize: Int = 512
        private set
    var totalSectors: Long = 0L
        private set

    fun open() {
        val connection = usbManager.openDevice(device)
            ?: throw IOException("Не удалось открыть USB-устройство. Проверьте, что доступ разрешён.")
        val found = ScsiBulkOnlyTransport.findMassStorageInterface(device)
            ?: throw IOException("Устройство не поддерживает протокол USB Mass Storage Bulk-Only Transport")
        val (intf, epIn, epOut) = found
        if (!connection.claimInterface(intf, true)) {
            throw IOException("Не удалось захватить USB-интерфейс устройства")
        }
        val t = ScsiBulkOnlyTransport(connection, intf, epIn, epOut)
        for (i in 0 until 5) {
            if (t.testUnitReady()) break
        }
        val (lastLba, blockSize) = t.readCapacity10()
        sectorSize = if (blockSize > 0) blockSize else 512
        totalSectors = lastLba + 1
        transport = t
    }

    fun close() {
        transport?.close()
        transport = null
    }

    fun capacityBytes(): Long = totalSectors * sectorSize

    /**
     * Записывает произвольный массив байт начиная с сектора [startLba].
     * Длина данных будет дополнена нулями до кратности размеру сектора.
     */
    fun writeAt(startLba: Long, data: ByteArray, onProgress: ((Float) -> Unit)? = null) {
        val t = transport ?: throw IOException("Устройство не открыто (вызовите open())")
        val maxChunkSectors = 128 // ~64 КБ за одну передачу — безопасно для большинства контроллеров

        val padded = if (data.size % sectorSize != 0) {
            val newSize = ((data.size / sectorSize) + 1) * sectorSize
            data.copyOf(newSize)
        } else data

        val totalSectorsToWrite = padded.size / sectorSize
        var written = 0
        var lba = startLba
        var offset = 0
        while (written < totalSectorsToWrite) {
            val chunkSectors = minOf(maxChunkSectors, totalSectorsToWrite - written)
            val chunk = padded.copyOfRange(offset, offset + chunkSectors * sectorSize)
            t.write10(lba, chunkSectors, sectorSize, chunk)
            lba += chunkSectors
            offset += chunk.size
            written += chunkSectors
            onProgress?.invoke(written.toFloat() / totalSectorsToWrite.toFloat())
        }
    }

    fun readAt(startLba: Long, sectors: Int): ByteArray {
        val t = transport ?: throw IOException("Устройство не открыто (вызовите open())")
        val buffer = ByteArray(sectors * sectorSize)
        t.read10(startLba, sectors, sectorSize, buffer)
        return buffer
    }
}
