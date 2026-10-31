package com.displaylink.app.aoa

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor

/**
 * Mock test harness for the USB accessory lifecycle.
 * Emulates physical docking, permission broadcasting, and unexpected disconnects.
 */
class AoaMockTestHarness : UsbManagerAdapter {

    val connectedAccessories = mutableListOf<UsbAccessoryWrapper>()
    val grantedPermissions = mutableSetOf<String>()
    val permissionRequests = mutableListOf<UsbAccessoryWrapper>()
    val openedAccessories = mutableListOf<UsbAccessoryWrapper>()

    var simulatedPfd: ParcelFileDescriptor? = createMockParcelFileDescriptor()
    var simulateFailureOnOpen: Boolean = false
    var simulateExceptionOnOpen: Exception? = null

    companion object {
        /**
         * Creates a mock ParcelFileDescriptor instance via reflection for JVM unit testing.
         */
        fun createMockParcelFileDescriptor(): ParcelFileDescriptor? {
            return try {
                val constructor = ParcelFileDescriptor::class.java.getDeclaredConstructor(FileDescriptor::class.java)
                constructor.isAccessible = true
                constructor.newInstance(FileDescriptor())
            } catch (_: Exception) {
                try {
                    val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
                    unsafeField.isAccessible = true
                    val unsafe = unsafeField.get(null)
                    val allocateMethod = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
                    allocateMethod.invoke(unsafe, ParcelFileDescriptor::class.java) as ParcelFileDescriptor
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    // ==========================================
    // UsbManagerAdapter Interface Implementation
    // ==========================================

    override fun getAccessoryList(): List<UsbAccessoryWrapper> {
        return connectedAccessories.toList()
    }

    override fun hasPermission(accessory: UsbAccessoryWrapper): Boolean {
        return grantedPermissions.contains(accessoryKey(accessory))
    }

    override fun requestPermission(accessory: UsbAccessoryWrapper, permissionIntent: PendingIntent?) {
        permissionRequests.add(accessory)
    }

    override fun openAccessory(accessory: UsbAccessoryWrapper): ParcelFileDescriptor? {
        if (simulateExceptionOnOpen != null) {
            throw simulateExceptionOnOpen!!
        }
        if (simulateFailureOnOpen) {
            return null
        }
        openedAccessories.add(accessory)
        return simulatedPfd
    }

    // ==========================================
    // Lifecycle Simulation Helpers
    // ==========================================

    fun attachAccessory(accessory: UsbAccessoryWrapper) {
        if (!connectedAccessories.contains(accessory)) {
            connectedAccessories.add(accessory)
        }
    }

    fun detachAccessory(accessory: UsbAccessoryWrapper) {
        connectedAccessories.remove(accessory)
        grantedPermissions.remove(accessoryKey(accessory))
    }

    fun grantPermission(accessory: UsbAccessoryWrapper) {
        grantedPermissions.add(accessoryKey(accessory))
    }

    fun revokePermission(accessory: UsbAccessoryWrapper) {
        grantedPermissions.remove(accessoryKey(accessory))
    }

    /**
     * Constructs a simulated [UsbManager.ACTION_USB_ACCESSORY_ATTACHED] intent.
     */
    fun createAttachedIntent(accessory: UsbAccessoryWrapper): Intent {
        attachAccessory(accessory)
        return Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED).apply {
            accessory.rawAccessory?.let { putExtra(UsbManager.EXTRA_ACCESSORY, it) }
        }
    }

    /**
     * Constructs a simulated [AoaManager.ACTION_USB_PERMISSION] response broadcast intent.
     */
    fun createPermissionIntent(accessory: UsbAccessoryWrapper, granted: Boolean): Intent {
        if (granted) {
            grantPermission(accessory)
        } else {
            revokePermission(accessory)
        }
        return Intent(AoaManager.ACTION_USB_PERMISSION).apply {
            accessory.rawAccessory?.let { putExtra(UsbManager.EXTRA_ACCESSORY, it) }
            putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted)
        }
    }

    /**
     * Constructs a simulated [UsbManager.ACTION_USB_ACCESSORY_DETACHED] intent.
     */
    fun createDetachedIntent(accessory: UsbAccessoryWrapper): Intent {
        detachAccessory(accessory)
        return Intent(UsbManager.ACTION_USB_ACCESSORY_DETACHED).apply {
            accessory.rawAccessory?.let { putExtra(UsbManager.EXTRA_ACCESSORY, it) }
        }
    }

    /**
     * Simulates an unexpected disconnection event dispatched to an [AoaManager] instance.
     */
    fun simulateUnexpectedDisconnection(aoaManager: AoaManager, accessory: UsbAccessoryWrapper) {
        val detachIntent = createDetachedIntent(accessory)
        aoaManager.processIntent(detachIntent, accessoryOverride = accessory)
    }

    /**
     * Simulates a file descriptor closure while an active connection is established.
     */
    fun simulateFileDescriptorClosure(aoaManager: AoaManager) {
        aoaManager.onFileDescriptorClosed()
    }

    fun reset() {
        connectedAccessories.clear()
        grantedPermissions.clear()
        permissionRequests.clear()
        openedAccessories.clear()
        simulatedPfd = createMockParcelFileDescriptor()
        simulateFailureOnOpen = false
        simulateExceptionOnOpen = null
    }

    private fun accessoryKey(accessory: UsbAccessoryWrapper): String {
        return "${accessory.manufacturer}:${accessory.model}:${accessory.version}"
    }
}
