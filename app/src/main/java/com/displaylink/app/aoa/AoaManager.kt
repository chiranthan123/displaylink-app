package com.displaylink.app.aoa

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.displaylink.app.ui.components.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Manages the Android Open Accessory (AOA) protocol lifecycle for DisplayLink.
 *
 * Responsibilities:
 * - Detects connected USB accessories via [UsbManagerAdapter]
 * - Requests and handles USB permission broadcasts
 * - Opens and manages the [ParcelFileDescriptor] via [UsbManagerAdapter.openAccessory]
 * - Tracks runtime health via [AoaDiagnosticUtility]
 * - Provides graceful fallback on unexpected disconnects or stream failures
 * - Exposes reactive connection state via Kotlin [StateFlow]
 */
class AoaManager(
    private val context: Context,
    val usbAdapter: UsbManagerAdapter = DefaultUsbManagerAdapter(context),
    val diagnostics: AoaDiagnosticUtility = AoaDiagnosticUtility()
) {

    companion object {
        private const val TAG = "AoaManager"
        const val ACTION_USB_PERMISSION = "com.displaylink.app.USB_PERMISSION"
    }

    private val appContext: Context = try {
        context.applicationContext ?: context
    } catch (_: Exception) {
        context
    }

    // Connection state flows
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.AWAITING)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _currentAccessory = MutableStateFlow<UsbAccessoryWrapper?>(null)
    val currentAccessory: StateFlow<UsbAccessoryWrapper?> = _currentAccessory.asStateFlow()

    private val _fileDescriptorFlow = MutableStateFlow<ParcelFileDescriptor?>(null)
    val fileDescriptorFlow: StateFlow<ParcelFileDescriptor?> = _fileDescriptorFlow.asStateFlow()

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var isReceiverRegistered = false

    // Coroutine scope and channel for bi-directional AOA telemetry transmission
    private val aoaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var telemetryWriterJob: Job? = null
    private val telemetryChannel = Channel<String>(capacity = 64)

    // Input / Output streams derived from the accessory file descriptor
    var inputStream: FileInputStream? = null
        private set
    var outputStream: FileOutputStream? = null
        private set

    // Optional stream override for unit testing bi-directional telemetry
    internal var telemetryStreamOverride: OutputStream? = null

    /**
     * BroadcastReceiver for USB permission, attachment, and detachment events.
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            processIntent(intent)
        }
    }

    /**
     * Processes incoming USB-related intents from the receiver or test harness.
     */
    fun processIntent(intent: Intent, accessoryOverride: UsbAccessoryWrapper? = null) {
        val action = intent.action ?: return
        diagnostics.log(DiagnosticSeverity.INFO, TAG, "Processing intent action: $action")

        when (action) {
            ACTION_USB_PERMISSION -> {
                val accessory = accessoryOverride ?: getAccessoryFromIntent(intent)
                val permissionGranted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED,
                    false
                )

                diagnostics.log(
                    if (permissionGranted) DiagnosticSeverity.SUCCESS else DiagnosticSeverity.WARNING,
                    TAG,
                    "Permission result for ${accessory?.description}: granted=$permissionGranted"
                )

                if (permissionGranted && accessory != null) {
                    openAccessoryConnection(accessory)
                } else {
                    diagnostics.log(DiagnosticSeverity.WARNING, TAG, "USB Permission denied or accessory null")
                    _connectionStatus.value = ConnectionStatus.AWAITING
                    diagnostics.updateHealth(
                        status = ConnectionStatus.AWAITING,
                        isAttached = _currentAccessory.value != null,
                        accessoryDesc = accessory?.description,
                        hasPermission = false,
                        pfd = null,
                        lastError = "Permission denied by user or system"
                    )
                }
            }

            UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                val accessory = accessoryOverride ?: getAccessoryFromIntent(intent)
                diagnostics.log(DiagnosticSeverity.INFO, TAG, "USB Attached: ${accessory?.description}")
                if (accessory != null) {
                    connectAccessory(accessory)
                } else {
                    checkConnectedAccessories()
                }
            }

            UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                val accessory = accessoryOverride ?: getAccessoryFromIntent(intent)
                diagnostics.log(
                    DiagnosticSeverity.WARNING,
                    TAG,
                    "USB Detached: ${accessory?.description ?: "unknown"}"
                )
                if (accessory == null || accessory == _currentAccessory.value) {
                    closeAccessoryConnection(reason = "Accessory detached")
                }
            }

            else -> {
                diagnostics.log(DiagnosticSeverity.INFO, TAG, "Ignored unrelated action: $action")
            }
        }
    }

    /**
     * Starts listening for USB accessory events and connects to any already-attached accessory.
     */
    fun start() {
        registerUsbReceiver()
        checkConnectedAccessories()
    }

    /**
     * Stops listening and cleans up any active accessory connection.
     */
    fun stop() {
        unregisterUsbReceiver()
        closeAccessoryConnection(reason = "AoaManager stopped")
        stopTelemetryWriter()
    }

    /**
     * Directly handles an accessory provided via activity launch/new-intent.
     */
    fun handleAccessoryAttached(rawAccessory: UsbAccessory?) {
        if (rawAccessory != null) {
            val wrapper = RealUsbAccessory(rawAccessory)
            diagnostics.log(DiagnosticSeverity.INFO, TAG, "Handling attached accessory from Intent: ${wrapper.description}")
            connectAccessory(wrapper)
        } else {
            checkConnectedAccessories()
        }
    }

    /**
     * Scans for already attached accessories via [UsbManagerAdapter.getAccessoryList].
     */
    fun checkConnectedAccessories() {
        val accessoryList = usbAdapter.getAccessoryList()
        diagnostics.log(DiagnosticSeverity.INFO, TAG, "Scanning attached accessories. Count=${accessoryList.size}")

        if (accessoryList.isNotEmpty()) {
            val accessory = accessoryList[0]
            connectAccessory(accessory)
        } else {
            if (_connectionStatus.value != ConnectionStatus.AWAITING) {
                closeAccessoryConnection(reason = "No accessories found during scan")
            }
        }
    }

    /**
     * Connects to the accessory: requests permission if needed, or opens it immediately.
     */
    fun connectAccessory(accessory: UsbAccessoryWrapper) {
        if (usbAdapter.hasPermission(accessory)) {
            diagnostics.log(DiagnosticSeverity.SUCCESS, TAG, "Permission already granted for: ${accessory.description}")
            openAccessoryConnection(accessory)
        } else {
            diagnostics.log(DiagnosticSeverity.INFO, TAG, "Requesting permission for: ${accessory.description}")
            _connectionStatus.value = ConnectionStatus.CONNECTING
            diagnostics.updateHealth(
                status = ConnectionStatus.CONNECTING,
                isAttached = true,
                accessoryDesc = accessory.description,
                hasPermission = false,
                pfd = null
            )
            requestPermission(accessory)
        }
    }

    /**
     * Opens the accessory using [UsbManagerAdapter.openAccessory] and obtains the [ParcelFileDescriptor].
     * Implements graceful fallback if the accessory cannot be opened.
     */
    fun openAccessoryConnection(accessory: UsbAccessoryWrapper): Boolean {
        closeAccessoryConnection(reason = "Reopening accessory connection")
        _connectionStatus.value = ConnectionStatus.CONNECTING

        try {
            val pfd = usbAdapter.openAccessory(accessory)
            if (pfd != null) {
                parcelFileDescriptor = pfd
                try {
                    val fd: FileDescriptor = pfd.fileDescriptor
                    inputStream = FileInputStream(fd)
                    outputStream = FileOutputStream(fd)
                } catch (e: Exception) {
                    diagnostics.log(DiagnosticSeverity.WARNING, TAG, "Streams unavailable (mock/virtual FD): ${e.message}")
                }

                _currentAccessory.value = accessory
                _fileDescriptorFlow.value = pfd
                _connectionStatus.value = ConnectionStatus.CONNECTED

                // Launch the bi-directional telemetry writer coroutine on Dispatchers.IO
                startTelemetryWriter()

                diagnostics.log(
                    DiagnosticSeverity.SUCCESS,
                    TAG,
                    "AOA Connection established. Accessory=${accessory.description}"
                )
                diagnostics.updateHealth(
                    status = ConnectionStatus.CONNECTED,
                    isAttached = true,
                    accessoryDesc = accessory.description,
                    hasPermission = true,
                    pfd = pfd
                )
                return true
            } else {
                diagnostics.log(DiagnosticSeverity.ERROR, TAG, "openAccessory() returned null ParcelFileDescriptor")
                fallbackToAwaiting(reason = "Null ParcelFileDescriptor from UsbManager")
                return false
            }
        } catch (e: Exception) {
            diagnostics.log(DiagnosticSeverity.ERROR, TAG, "Exception opening accessory: ${e.message}")
            fallbackToAwaiting(reason = e.message ?: "Exception opening accessory")
            return false
        }
    }

    /**
     * Graceful fallback when opening fails or an unexpected disconnection occurs.
     */
    private fun fallbackToAwaiting(reason: String) {
        closeAccessoryConnection(reason = reason)
        _connectionStatus.value = ConnectionStatus.AWAITING
        diagnostics.updateHealth(
            status = ConnectionStatus.AWAITING,
            isAttached = false,
            accessoryDesc = null,
            hasPermission = false,
            pfd = null,
            lastError = reason
        )
    }

    /**
     * Called when the file descriptor closes unexpectedly during active communication.
     */
    fun onFileDescriptorClosed(error: Throwable? = null) {
        val errorMsg = error?.message ?: "Native file descriptor closed unexpectedly"
        diagnostics.log(DiagnosticSeverity.ERROR, TAG, "Unexpected FD closure: $errorMsg")
        fallbackToAwaiting(reason = errorMsg)
    }

    /**
     * Closes the active [ParcelFileDescriptor] and input/output streams.
     */
    fun closeAccessoryConnection(reason: String = "Normal closure") {
        stopTelemetryWriter()

        try {
            inputStream?.close()
        } catch (e: IOException) {
            diagnostics.log(DiagnosticSeverity.WARNING, TAG, "Error closing inputStream: ${e.message}")
        } finally {
            inputStream = null
        }

        try {
            outputStream?.close()
        } catch (e: IOException) {
            diagnostics.log(DiagnosticSeverity.WARNING, TAG, "Error closing outputStream: ${e.message}")
        } finally {
            outputStream = null
        }

        try {
            parcelFileDescriptor?.close()
        } catch (e: IOException) {
            diagnostics.log(DiagnosticSeverity.WARNING, TAG, "Error closing ParcelFileDescriptor: ${e.message}")
        } finally {
            parcelFileDescriptor = null
            _fileDescriptorFlow.value = null
            _currentAccessory.value = null
            _connectionStatus.value = ConnectionStatus.AWAITING
            diagnostics.log(DiagnosticSeverity.INFO, TAG, "Connection closed. Reason: $reason")
            diagnostics.updateHealth(
                status = ConnectionStatus.AWAITING,
                isAttached = false,
                accessoryDesc = null,
                hasPermission = false,
                pfd = null
            )
        }
    }

    /**
     * Enqueues a telemetry or control message (e.g. {"command": "reduce_bitrate"})
     * to be written to the host PC via the AOA FileOutputStream coroutine.
     */
    fun sendTelemetry(message: String): Boolean {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
            diagnostics.log(DiagnosticSeverity.WARNING, TAG, "Cannot send telemetry: accessory not connected")
            return false
        }
        val result = telemetryChannel.trySend(message)
        if (result.isSuccess) {
            diagnostics.log(DiagnosticSeverity.INFO, TAG, "Enqueued telemetry control message: $message")
        }
        return result.isSuccess
    }

    /**
     * Dedicated coroutine on Dispatchers.IO that continuously consumes telemetry messages
     * from the queue and writes them back to the host PC using the FileOutputStream
     * associated with the accessory's ParcelFileDescriptor.
     */
    fun startTelemetryWriter() {
        telemetryWriterJob?.cancel()
        telemetryWriterJob = aoaScope.launch(Dispatchers.IO) {
            runTelemetryWriterLoop()
        }
    }

    private suspend fun runTelemetryWriterLoop() {
        diagnostics.log(DiagnosticSeverity.INFO, TAG, "AOA Telemetry writer coroutine started on Dispatchers.IO")
        val stream: OutputStream? = telemetryStreamOverride ?: outputStream
        if (stream == null) {
            diagnostics.log(DiagnosticSeverity.WARNING, TAG, "FileOutputStream is null, cannot transmit telemetry")
            return
        }

        try {
            for (message in telemetryChannel) {
                if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
                    break
                }
                try {
                    val payload = (if (message.endsWith("\n")) message else "$message\n")
                        .toByteArray(Charsets.UTF_8)
                    stream.write(payload)
                    stream.flush()
                    diagnostics.log(
                        DiagnosticSeverity.INFO,
                        TAG,
                        "Transmitted telemetry to host (${payload.size}B): ${message.trim()}"
                    )
                } catch (e: IOException) {
                    diagnostics.log(
                        DiagnosticSeverity.WARNING,
                        TAG,
                        "IOException writing telemetry to host FileOutputStream: ${e.message}"
                    )
                    break
                }
            }
        } catch (e: CancellationException) {
            diagnostics.log(DiagnosticSeverity.INFO, TAG, "AOA telemetry writer coroutine cancelled cleanly")
        }
    }

    fun stopTelemetryWriter() {
        telemetryWriterJob?.cancel()
        telemetryWriterJob = null
    }

    /**
     * Dispatches an explicit permission request to the system.
     */
    private fun requestPermission(accessory: UsbAccessoryWrapper) {
        val permissionFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pkg = try { appContext.packageName } catch (_: Exception) { "com.displaylink.app" } ?: "com.displaylink.app"
        val permissionIntent = try {
            PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(pkg),
                permissionFlags
            )
        } catch (_: Exception) {
            null
        }

        usbAdapter.requestPermission(accessory, permissionIntent)
    }

    private fun registerUsbReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appContext.registerReceiver(usbReceiver, filter)
                }
                isReceiverRegistered = true
                diagnostics.log(DiagnosticSeverity.INFO, TAG, "USB BroadcastReceiver registered")
            } catch (e: Exception) {
                diagnostics.log(DiagnosticSeverity.WARNING, TAG, "Could not register receiver (test environment): ${e.message}")
            }
        }
    }

    private fun unregisterUsbReceiver() {
        if (isReceiverRegistered) {
            try {
                appContext.unregisterReceiver(usbReceiver)
            } catch (e: IllegalArgumentException) {
                diagnostics.log(DiagnosticSeverity.WARNING, TAG, "Receiver not registered: ${e.message}")
            } finally {
                isReceiverRegistered = false
            }
        }
    }

    private fun getAccessoryFromIntent(intent: Intent): UsbAccessoryWrapper? {
        val rawAccessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
        return rawAccessory?.let { RealUsbAccessory(it) }
    }
}
