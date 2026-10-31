package com.displaylink.app.aoa

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.displaylink.app.ui.components.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests verifying AOA USB accessory lifecycle:
 * - Intent filtering (ATTACHED, PERMISSION, DETACHED, unrelated)
 * - Permission request negotiation (granted vs denied)
 * - Graceful fallback on unexpected detachment, open failure, and FD closure
 * - Diagnostic health monitoring
 */
class AoaManagerTest {

    private lateinit var harness: AoaMockTestHarness
    private lateinit var diagnostics: AoaDiagnosticUtility
    private lateinit var aoaManager: AoaManager
    private lateinit var testAccessory: UsbAccessoryWrapper

    @Before
    fun setUp() {
        harness = AoaMockTestHarness()
        diagnostics = AoaDiagnosticUtility()

        // Create AoaManager using the mock harness as the UsbManagerAdapter
        val fakeContext = FakeTestContext()
        aoaManager = AoaManager(
            context = fakeContext,
            usbAdapter = harness,
            diagnostics = diagnostics
        )

        testAccessory = MockUsbAccessory(
            manufacturer = "DisplayLink",
            model = "DL-6950 Dock",
            description = "DisplayLink 4K Dual Docking Station",
            version = "1.0"
        )
    }

    // ==========================================
    // 1. INTENT FILTERING TESTS
    // ==========================================

    @Test
    fun testIntentFiltering_attachedIntent_initiatesConnection() {
        assertEquals(ConnectionStatus.AWAITING, aoaManager.connectionStatus.value)

        val attachIntent = harness.createAttachedIntent(testAccessory)
        aoaManager.processIntent(attachIntent, accessoryOverride = testAccessory)

        // Permission was not pre-granted, so AoaManager should request permission
        assertEquals(1, harness.permissionRequests.size)
        assertEquals(testAccessory, harness.permissionRequests[0])
        assertEquals(ConnectionStatus.CONNECTING, aoaManager.connectionStatus.value)
    }

    @Test
    fun testIntentFiltering_permissionIntent_dispatchesCorrectly() {
        harness.attachAccessory(testAccessory)

        val permissionIntent = harness.createPermissionIntent(testAccessory, granted = true)
        aoaManager.processIntent(permissionIntent, accessoryOverride = testAccessory)

        // With permission granted, openAccessory should be called
        assertEquals(1, harness.openedAccessories.size)
        assertEquals(testAccessory, harness.openedAccessories[0])
    }

    @Test
    fun testIntentFiltering_detachedIntent_resetsConnection() {
        // Pre-connect
        harness.grantPermission(testAccessory)
        val attachIntent = harness.createAttachedIntent(testAccessory)
        aoaManager.processIntent(attachIntent, accessoryOverride = testAccessory)

        val detachIntent = harness.createDetachedIntent(testAccessory)
        aoaManager.processIntent(detachIntent, accessoryOverride = testAccessory)

        assertEquals(ConnectionStatus.AWAITING, aoaManager.connectionStatus.value)
        assertNull(aoaManager.currentAccessory.value)
        assertNull(aoaManager.fileDescriptorFlow.value)
    }

    @Test
    fun testIntentFiltering_ignoresUnrelatedIntents() {
        val unrelatedIntent = Intent("android.intent.action.BATTERY_LOW")
        aoaManager.processIntent(unrelatedIntent)

        // State remains AWAITING with zero interactions
        assertEquals(ConnectionStatus.AWAITING, aoaManager.connectionStatus.value)
        assertEquals(0, harness.permissionRequests.size)
        assertEquals(0, harness.openedAccessories.size)
    }

    // ==========================================
    // 2. PERMISSION REQUEST HANDLING TESTS
    // ==========================================

    @Test
    fun testPermissionHandling_whenGranted_opensAccessoryAndConnects() {
        harness.attachAccessory(testAccessory)

        val permissionIntent = harness.createPermissionIntent(testAccessory, granted = true)
        aoaManager.processIntent(permissionIntent, accessoryOverride = testAccessory)

        assertEquals(ConnectionStatus.CONNECTED, aoaManager.connectionStatus.value)
        assertEquals(testAccessory, aoaManager.currentAccessory.value)
        assertTrue(diagnostics.healthReport.value.hasUsbPermission)
    }

    @Test
    fun testPermissionHandling_whenDenied_fallsBackToAwaiting() {
        harness.attachAccessory(testAccessory)

        val permissionIntent = harness.createPermissionIntent(testAccessory, granted = false)
        aoaManager.processIntent(permissionIntent, accessoryOverride = testAccessory)

        assertEquals(ConnectionStatus.AWAITING, aoaManager.connectionStatus.value)
        assertNull(aoaManager.currentAccessory.value)
        assertNull(aoaManager.fileDescriptorFlow.value)
        assertEquals(0, harness.openedAccessories.size)
        assertFalse(diagnostics.healthReport.value.hasUsbPermission)
    }

    @Test
    fun testPermissionHandling_whenAlreadyPermitted_connectsImmediatelyWithoutRequest() {
        harness.grantPermission(testAccessory)
        harness.attachAccessory(testAccessory)

        aoaManager.connectAccessory(testAccessory)

        // Permission was already granted -> no request dispatched, directly opened
        assertEquals(0, harness.permissionRequests.size)
        assertEquals(1, harness.openedAccessories.size)
        assertEquals(ConnectionStatus.CONNECTED, aoaManager.connectionStatus.value)
    }

    // ==========================================
    // 3. GRACEFUL FALLBACK & ERROR RECOVERY TESTS
    // ==========================================

    @Test
    fun testGracefulFallback_whenOpenAccessoryReturnsNull() {
        harness.grantPermission(testAccessory)
        harness.attachAccessory(testAccessory)
        // Simulate hardware driver returning null ParcelFileDescriptor
        harness.simulateFailureOnOpen = true

        val success = aoaManager.openAccessoryConnection(testAccessory)

        assertFalse(success)
        assertEquals(ConnectionStatus.AWAITING, aoaManager.connectionStatus.value)
        assertNull(aoaManager.fileDescriptorFlow.value)
        assertNull(aoaManager.currentAccessory.value)
        assertNotNull(diagnostics.healthReport.value.lastError)
    }

    @Test
    fun testGracefulFallback_whenOpenAccessoryThrowsSecurityException() {
        harness.grantPermission(testAccessory)
        harness.attachAccessory(testAccessory)
        harness.simulateExceptionOnOpen = SecurityException("UsbService access denied by Knox policy")

        val success = aoaManager.openAccessoryConnection(testAccessory)

        assertFalse(success)
        assertEquals(ConnectionStatus.AWAITING, aoaManager.connectionStatus.value)
        assertNull(aoaManager.fileDescriptorFlow.value)
        assertEquals("UsbService access denied by Knox policy", diagnostics.healthReport.value.lastError)
    }

    @Test
    fun testGracefulFallback_whenAccessoryDisconnectsUnexpectedly() {
        // First establish active connection
        harness.grantPermission(testAccessory)
        aoaManager.openAccessoryConnection(testAccessory)
        assertEquals(ConnectionStatus.CONNECTED, aoaManager.connectionStatus.value)

        // Simulate abrupt physical cable removal
        harness.simulateUnexpectedDisconnection(aoaManager, testAccessory)

        assertEquals(ConnectionStatus.AWAITING, aoaManager.connectionStatus.value)
        assertNull(aoaManager.currentAccessory.value)
        assertNull(aoaManager.fileDescriptorFlow.value)
    }

    @Test
    fun testGracefulFallback_whenFileDescriptorClosesUnexpectedly() {
        // Establish connection
        harness.grantPermission(testAccessory)
        aoaManager.openAccessoryConnection(testAccessory)
        assertEquals(ConnectionStatus.CONNECTED, aoaManager.connectionStatus.value)

        // Simulate file descriptor crash / broken pipe
        aoaManager.onFileDescriptorClosed(IOException("EPIPE: Broken pipe on accessory stream"))

        assertEquals(ConnectionStatus.AWAITING, aoaManager.connectionStatus.value)
        assertNull(aoaManager.fileDescriptorFlow.value)
        assertNull(aoaManager.currentAccessory.value)
        assertEquals("EPIPE: Broken pipe on accessory stream", diagnostics.healthReport.value.lastError)
    }

    // ==========================================
    // 4. DIAGNOSTIC UTILITY TESTS
    // ==========================================

    @Test
    fun testDiagnostics_recordsLifecycleEvents() {
        harness.grantPermission(testAccessory)
        aoaManager.openAccessoryConnection(testAccessory)

        val logs = diagnostics.logs.value
        assertTrue("Diagnostics should have captured connection logs", logs.isNotEmpty())

        val successLogs = logs.filter { it.severity == DiagnosticSeverity.SUCCESS }
        assertTrue("Should have recorded success events", successLogs.isNotEmpty())

        val report = diagnostics.healthReport.value
        assertEquals(ConnectionStatus.CONNECTED, report.connectionStatus)
        assertTrue(report.isAccessoryAttached)
        assertEquals(testAccessory.description, report.accessoryDescription)
    }

    // ==========================================
    // 5. BI-DIRECTIONAL AOA TELEMETRY TESTS
    // ==========================================

    @Test
    fun testTelemetryWriterCoroutine_writesJsonToOutputStream() {
        val testStream = java.io.ByteArrayOutputStream()
        aoaManager.telemetryStreamOverride = testStream

        // Open connection
        harness.grantPermission(testAccessory)
        val opened = aoaManager.openAccessoryConnection(testAccessory)
        assertTrue(opened)
        assertEquals(ConnectionStatus.CONNECTED, aoaManager.connectionStatus.value)

        // Send telemetry message
        val controlMsg = "{\"command\": \"reduce_bitrate\", \"latency_ms\": 80.5}"
        val enqueued = aoaManager.sendTelemetry(controlMsg)
        assertTrue(enqueued)

        // Allow coroutine on Dispatchers.IO to flush message
        Thread.sleep(150)

        val output = testStream.toString("UTF-8")
        assertTrue(output.contains("\"command\": \"reduce_bitrate\""))
        assertTrue(output.contains("\"latency_ms\": 80.5"))
        assertTrue(output.endsWith("\n"))
    }

    @Test
    fun testSendTelemetry_whenNotConnected_returnsFalse() {
        val enqueued = aoaManager.sendTelemetry("{\"command\": \"reduce_bitrate\"}")
        assertFalse(enqueued)
    }
}

/**
 * Lightweight mock Context for local unit testing without Android OS daemon.
 */
private class FakeTestContext : ContextWrapperStub() {
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.displaylink.app"
}

/**
 * Minimal stub providing base Context methods required by AoaManager for JVM unit tests.
 */
open class ContextWrapperStub : Context() {
    override fun getAssets() = throw UnsupportedOperationException()
    override fun getResources() = throw UnsupportedOperationException()
    override fun getPackageManager() = throw UnsupportedOperationException()
    override fun getContentResolver() = throw UnsupportedOperationException()
    override fun getMainLooper() = throw UnsupportedOperationException()
    override fun getApplicationContext(): Context = this
    override fun setTheme(resid: Int) {}
    override fun getTheme() = throw UnsupportedOperationException()
    override fun getClassLoader(): ClassLoader = javaClass.classLoader!!
    override fun getPackageName(): String = "com.displaylink.app"
    override fun getApplicationInfo() = throw UnsupportedOperationException()
    override fun getPackageResourcePath() = throw UnsupportedOperationException()
    override fun getPackageCodePath() = throw UnsupportedOperationException()
    override fun getSharedPreferences(name: String?, mode: Int) = throw UnsupportedOperationException()
    override fun moveSharedPreferencesFrom(sourceContext: Context?, name: String?) = false
    override fun deleteSharedPreferences(name: String?) = false
    override fun openFileInput(name: String?) = throw UnsupportedOperationException()
    override fun openFileOutput(name: String?, mode: Int) = throw UnsupportedOperationException()
    override fun deleteFile(name: String?) = false
    override fun getFileStreamPath(name: String?) = throw UnsupportedOperationException()
    override fun fileList() = emptyArray<String>()
    override fun getFilesDir() = throw UnsupportedOperationException()
    override fun getNoBackupFilesDir() = throw UnsupportedOperationException()
    override fun getExternalFilesDir(type: String?) = null
    override fun getExternalFilesDirs(type: String?) = emptyArray<java.io.File>()
    override fun getObbDir() = throw UnsupportedOperationException()
    override fun getObbDirs() = emptyArray<java.io.File>()
    override fun getCacheDir() = throw UnsupportedOperationException()
    override fun getCodeCacheDir() = throw UnsupportedOperationException()
    override fun getExternalCacheDir() = null
    override fun getExternalCacheDirs() = emptyArray<java.io.File>()
    override fun getDataDir() = throw UnsupportedOperationException()
    override fun getDir(name: String?, mode: Int) = throw UnsupportedOperationException()
    override fun openOrCreateDatabase(name: String?, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) = throw UnsupportedOperationException()
    override fun openOrCreateDatabase(name: String?, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) = throw UnsupportedOperationException()
    override fun moveDatabaseFrom(sourceContext: Context?, name: String?) = false
    override fun deleteDatabase(name: String?) = false
    override fun getDatabasePath(name: String?) = throw UnsupportedOperationException()
    override fun databaseList() = emptyArray<String>()
    override fun getWallpaper() = throw UnsupportedOperationException()
    override fun peekWallpaper() = throw UnsupportedOperationException()
    override fun getWallpaperDesiredMinimumWidth() = 0
    override fun getWallpaperDesiredMinimumHeight() = 0
    override fun setWallpaper(bitmap: android.graphics.Bitmap?) {}
    override fun setWallpaper(data: java.io.InputStream?) {}
    override fun clearWallpaper() {}
    override fun startActivity(intent: Intent?) {}
    override fun startActivity(intent: Intent?, options: android.os.Bundle?) {}
    override fun startActivities(intents: Array<out Intent>?) {}
    override fun startActivities(intents: Array<out Intent>?, options: android.os.Bundle?) {}
    override fun startIntentSender(intent: android.content.IntentSender?, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) {}
    override fun startIntentSender(intent: android.content.IntentSender?, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: android.os.Bundle?) {}
    override fun sendBroadcast(intent: Intent?) {}
    override fun sendBroadcast(intent: Intent?, receiverPermission: String?) {}
    override fun sendOrderedBroadcast(intent: Intent?, receiverPermission: String?) {}
    override fun sendOrderedBroadcast(intent: Intent, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
    override fun sendBroadcastAsUser(intent: Intent?, user: android.os.UserHandle?) {}
    override fun sendBroadcastAsUser(intent: Intent?, user: android.os.UserHandle?, receiverPermission: String?) {}
    override fun sendOrderedBroadcastAsUser(intent: Intent?, user: android.os.UserHandle?, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
    override fun sendStickyBroadcast(intent: Intent?) {}
    override fun sendStickyOrderedBroadcast(intent: Intent?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
    override fun removeStickyBroadcast(intent: Intent?) {}
    override fun sendStickyBroadcastAsUser(intent: Intent?, user: android.os.UserHandle?) {}
    override fun sendStickyOrderedBroadcastAsUser(intent: Intent?, user: android.os.UserHandle?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
    override fun removeStickyBroadcastAsUser(intent: Intent?, user: android.os.UserHandle?) {}
    override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?) = null
    override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, flags: Int) = null
    override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, broadcastPermission: String?, scheduler: android.os.Handler?) = null
    override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, broadcastPermission: String?, scheduler: android.os.Handler?, flags: Int) = null
    override fun unregisterReceiver(receiver: android.content.BroadcastReceiver?) {}
    override fun startService(service: Intent?) = null
    override fun startForegroundService(service: Intent?) = null
    override fun stopService(service: Intent?) = false
    override fun bindService(service: Intent, conn: android.content.ServiceConnection, flags: Int) = false
    override fun unbindService(conn: android.content.ServiceConnection) {}
    override fun startInstrumentation(className: android.content.ComponentName, profileFile: String?, arguments: android.os.Bundle?) = false
    override fun getSystemService(name: String) = throw UnsupportedOperationException()
    override fun getSystemServiceName(serviceClass: Class<*>) = null
    override fun checkPermission(permission: String, pid: Int, uid: Int) = 0
    override fun checkCallingPermission(permission: String) = 0
    override fun checkCallingOrSelfPermission(permission: String) = 0
    override fun checkSelfPermission(permission: String) = 0
    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}
    override fun enforceCallingPermission(permission: String, message: String?) {}
    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
    override fun grantUriPermission(toPackage: String?, uri: android.net.Uri?, modeFlags: Int) {}
    override fun revokeUriPermission(uri: android.net.Uri?, modeFlags: Int) {}
    override fun revokeUriPermission(toPackage: String?, uri: android.net.Uri?, modeFlags: Int) {}
    override fun checkUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, modeFlags: Int) = 0
    override fun checkCallingUriPermission(uri: android.net.Uri?, modeFlags: Int) = 0
    override fun checkCallingOrSelfUriPermission(uri: android.net.Uri?, modeFlags: Int) = 0
    override fun checkUriPermission(uri: android.net.Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int) = 0
    override fun enforceUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, modeFlags: Int, message: String?) {}
    override fun enforceCallingUriPermission(uri: android.net.Uri?, modeFlags: Int, message: String?) {}
    override fun enforceCallingOrSelfUriPermission(uri: android.net.Uri?, modeFlags: Int, message: String?) {}
    override fun createPackageContext(packageName: String?, flags: Int) = throw UnsupportedOperationException()
    override fun createContextForSplit(splitName: String?) = throw UnsupportedOperationException()
    override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration) = throw UnsupportedOperationException()
    override fun createDisplayContext(display: android.view.Display) = throw UnsupportedOperationException()
    override fun createDeviceProtectedStorageContext() = throw UnsupportedOperationException()
    override fun isDeviceProtectedStorage() = false
}

