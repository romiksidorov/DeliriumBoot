package com.delirium.deliriumboot.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Минимальная реализация протокола USB Mass Storage Bulk-Only Transport (BBB)
 * поверх набора команд SCSI Primary/Block Commands, написанная "с нуля" по
 * официальным спецификациям:
 *  - USB Mass Storage Class – Bulk Only Transport, rev. 1.0
 *  - SCSI Block Commands (SBC-3): READ(10) / WRITE(10) / READ CAPACITY(10)
 *
 * Класс не зависит от внутреннего устройства сторонних библиотек и поэтому
 * умеет работать с "чистой" (неразмеченной) флешкой — это необходимо для
 * установки DeliriumBoot/Ventoy с нуля, когда никакой файловой системы или
 * таблицы разделов ещё не существует.
 */
class ScsiBulkOnlyTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {
    companion object {
        private const val CBW_SIGNATURE = 0x43425355
        private const val CSW_SIGNATURE = 0x53425355
        private const val CBW_LEN = 31
        private const val CSW_LEN = 13
        private const val TIMEOUT_MS = 10000
        private const val DIR_IN: Byte = 0x80.toByte()
        private const val DIR_OUT: Byte = 0x00

        /** Находит интерфейс USB Mass Storage (SCSI, Bulk-Only) у устройства. */
        fun findMassStorageInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    intf.interfaceSubclass == 0x06 && // SCSI transparent command set
                    intf.interfaceProtocol == 0x50     // Bulk-Only Transport
                ) {
                    var inEp: UsbEndpoint? = null
                    var outEp: UsbEndpoint? = null
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                    }
                    if (inEp != null && outEp != null) return Triple(intf, inEp, outEp)
                }
            }
            return null
        }
    }

    private var tag = 1

    @Synchronized
    private fun nextTag(): Int = tag++

    private fun buildCbw(cdb: ByteArray, dataLength: Int, direction: Byte): ByteArray {
        val buf = ByteBuffer.allocate(CBW_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(CBW_SIGNATURE)
        buf.putInt(nextTag())
        buf.putInt(dataLength)
        buf.put(direction)
        buf.put(0) // LUN 0
        buf.put(cdb.size.toByte())
        val cbwcb = ByteArray(16)
        System.arraycopy(cdb, 0, cbwcb, 0, cdb.size)
        buf.put(cbwcb)
        return buf.array()
    }

    private fun sendCommand(cdb: ByteArray, data: ByteArray?, direction: Byte, dataLength: Int): Int {
        val cbw = buildCbw(cdb, dataLength, direction)
        val sent = connection.bulkTransfer(endpointOut, cbw, cbw.size, TIMEOUT_MS)
        if (sent != cbw.size) throw IOException("Не удалось отправить CBW (отправлено $sent байт)")

        if (dataLength > 0 && data != null) {
            if (direction == DIR_OUT) {
                val written = connection.bulkTransfer(endpointOut, data, dataLength, TIMEOUT_MS)
                if (written < 0) throw IOException("Ошибка записи данных на устройство")
            } else {
                val read = connection.bulkTransfer(endpointIn, data, dataLength, TIMEOUT_MS)
                if (read < 0) throw IOException("Ошибка чтения данных с устройства")
            }
        }

        val csw = ByteArray(CSW_LEN)
        val readCsw = connection.bulkTransfer(endpointIn, csw, CSW_LEN, TIMEOUT_MS)
        if (readCsw != CSW_LEN) throw IOException("Не удалось получить CSW")
        val bb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val sig = bb.int
        bb.int // tag — не проверяем строго ради совместимости с нестрогими прошивками
        bb.int // residue
        val status = bb.get().toInt()
        if (sig != CSW_SIGNATURE) throw IOException("Неверная сигнатура CSW — устройство ведёт себя нестандартно")
        return status
    }

    fun testUnitReady(): Boolean {
        val cdb = ByteArray(6)
        return try { sendCommand(cdb, null, DIR_IN, 0) == 0 } catch (e: Exception) { false }
    }

    fun inquiry(): ByteArray {
        val cdb = byteArrayOf(0x12, 0, 0, 0, 36, 0)
        val data = ByteArray(36)
        sendCommand(cdb, data, DIR_IN, data.size)
        return data
    }

    /** @return Пара (последний LBA, размер сектора в байтах). */
    fun readCapacity10(): Pair<Long, Int> {
        val cdb = byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val data = ByteArray(8)
        sendCommand(cdb, data, DIR_IN, data.size)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val lastLba = bb.int.toLong() and 0xFFFFFFFFL
        val blockSize = bb.int
        return Pair(lastLba, blockSize)
    }

    fun read10(lba: Long, blockCount: Int, sectorSize: Int, out: ByteArray) {
        val cdb = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
            put(0, 0x28.toByte())
            putInt(2, lba.toInt())
            putShort(7, blockCount.toShort())
        }.array()
        sendCommand(cdb, out, DIR_IN, blockCount * sectorSize)
    }

    fun write10(lba: Long, blockCount: Int, sectorSize: Int, data: ByteArray) {
        val cdb = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
            put(0, 0x2A.toByte())
            putInt(2, lba.toInt())
            putShort(7, blockCount.toShort())
        }.array()
        val status = sendCommand(cdb, data, DIR_OUT, blockCount * sectorSize)
        if (status != 0) throw IOException("Устройство отклонило запись сектора $lba (status=$status)")
    }

    fun close() {
        try { connection.releaseInterface(usbInterface) } catch (_: Exception) { }
        connection.close()
    }
}
