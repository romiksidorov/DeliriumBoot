package com.delirium.deliriumboot.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Parcelable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val ACTION_USB_PERMISSION = "com.delirium.deliriumboot.USB_PERMISSION"

/**
 * Обёртка над [UsbManager]: поиск подключённой флешки, запрос разрешения
 * пользователя (системный диалог Android, без root) и отслеживание событий
 * подключения/отключения устройства.
 */
class UsbController(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _device = MutableStateFlow<UsbDevice?>(null)
    val device: StateFlow<UsbDevice?> = _device

    private val _hasPermission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _hasPermission

    private var onPermissionResult: ((Boolean) -> Unit)? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val dev = getParcelableExtraCompat(intent, UsbManager.EXTRA_DEVICE)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && dev != null) {
                            _device.value = dev
                            _hasPermission.value = true
                        } else {
                            _hasPermission.value = false
                        }
                        onPermissionResult?.invoke(granted)
                        onPermissionResult = null
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    findMassStorageDevice()?.let { _device.value = it }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    _device.value = null
                    _hasPermission.value = false
                }
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        findMassStorageDevice()?.let { dev ->
            _device.value = dev
            _hasPermission.value = usbManager.hasPermission(dev)
        }
    }

    fun unregister() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
        registered = false
    }

    fun findMassStorageDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any {
                dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
            }
        }
    }

    fun requestPermission(dev: UsbDevice, callback: (Boolean) -> Unit) {
        if (usbManager.hasPermission(dev)) {
            _device.value = dev
            _hasPermission.value = true
            callback(true)
            return
        }
        onPermissionResult = callback
        val flags = PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(dev, pi)
    }

    fun managerInstance(): UsbManager = usbManager

    @Suppress("DEPRECATION")
    private fun getParcelableExtraCompat(intent: Intent, name: String): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(name, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(name) as? UsbDevice
        }
    }
}
