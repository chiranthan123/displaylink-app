package com.displaylink.app.aoa

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor

/**
 * Abstraction over [UsbAccessory] to allow testing without relying on internal native constructors.
 */
interface UsbAccessoryWrapper {
    val manufacturer: String
    val model: String
    val description: String
    val version: String
    val rawAccessory: UsbAccessory?
}

/**
 * Production implementation wrapping a real Android [UsbAccessory].
 */
class RealUsbAccessory(override val rawAccessory: UsbAccessory) : UsbAccessoryWrapper {
    override val manufacturer: String get() = rawAccessory.manufacturer
    override val model: String get() = rawAccessory.model
    override val description: String get() = rawAccessory.description
    override val version: String get() = rawAccessory.version

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UsbAccessoryWrapper) return false
        return rawAccessory == other.rawAccessory ||
                (manufacturer == other.manufacturer && model == other.model && version == other.version)
    }

    override fun hashCode(): Int {
        return rawAccessory.hashCode()
    }

    override fun toString(): String = "$manufacturer $model (v$version)"
}

/**
 * Mock representation of [UsbAccessoryWrapper] for JVM unit testing and runtime diagnostics.
 */
data class MockUsbAccessory(
    override val manufacturer: String = "DisplayLink",
    override val model: String = "DL-6950 Dock",
    override val description: String = "DisplayLink 4K Docking Station",
    override val version: String = "1.0",
    override val rawAccessory: UsbAccessory? = null
) : UsbAccessoryWrapper {
    override fun toString(): String = "$manufacturer $model (v$version)"
}

/**
 * Adapter interface decoupling [AoaManager] from the Android framework's [UsbManager].
 */
interface UsbManagerAdapter {
    fun getAccessoryList(): List<UsbAccessoryWrapper>
    fun hasPermission(accessory: UsbAccessoryWrapper): Boolean
    fun requestPermission(accessory: UsbAccessoryWrapper, permissionIntent: PendingIntent?)
    fun openAccessory(accessory: UsbAccessoryWrapper): ParcelFileDescriptor?
}

/**
 * Production adapter delegating to Android's native [UsbManager].
 */
class DefaultUsbManagerAdapter(context: Context) : UsbManagerAdapter {
    private val usbManager: UsbManager =
        context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    override fun getAccessoryList(): List<UsbAccessoryWrapper> {
        val list = usbManager.accessoryList ?: return emptyList()
        return list.map { RealUsbAccessory(it) }
    }

    override fun hasPermission(accessory: UsbAccessoryWrapper): Boolean {
        val raw = accessory.rawAccessory ?: return false
        return usbManager.hasPermission(raw)
    }

    override fun requestPermission(accessory: UsbAccessoryWrapper, permissionIntent: PendingIntent?) {
        val raw = accessory.rawAccessory ?: return
        usbManager.requestPermission(raw, permissionIntent)
    }

    override fun openAccessory(accessory: UsbAccessoryWrapper): ParcelFileDescriptor? {
        val raw = accessory.rawAccessory ?: return null
        return usbManager.openAccessory(raw)
    }
}

