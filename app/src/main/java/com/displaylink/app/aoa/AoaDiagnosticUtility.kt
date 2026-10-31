package com.displaylink.app.aoa

import android.os.ParcelFileDescriptor
import android.util.Log
import com.displaylink.app.ui.components.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileDescriptor

enum class DiagnosticSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

data class DiagnosticLog(
    val timestamp: Long = System.currentTimeMillis(),
    val severity: DiagnosticSeverity,
    val tag: String,
    val message: String
)

data class AoaHealthReport(
    val connectionStatus: ConnectionStatus,
    val isAccessoryAttached: Boolean,
    val accessoryDescription: String?,
    val hasUsbPermission: Boolean,
    val isFileDescriptorActive: Boolean,
    val fileDescriptorInt: Int?,
    val lastError: String?
)

/**
 * Diagnostic utility for monitoring and troubleshooting the AOA USB connection pipeline.
 * Tracks hardware link health, captures runtime diagnostics, and detects stream/FD failures.
 */
class AoaDiagnosticUtility {

    companion object {
        private const val TAG = "AoaDiagnostics"
        private const val MAX_LOG_HISTORY = 100
    }

    private val _logs = MutableStateFlow<List<DiagnosticLog>>(emptyList())
    val logs: StateFlow<List<DiagnosticLog>> = _logs.asStateFlow()

    private val _healthReport = MutableStateFlow(
        AoaHealthReport(
            connectionStatus = ConnectionStatus.AWAITING,
            isAccessoryAttached = false,
            accessoryDescription = null,
            hasUsbPermission = false,
            isFileDescriptorActive = false,
            fileDescriptorInt = null,
            lastError = null
        )
    )
    val healthReport: StateFlow<AoaHealthReport> = _healthReport.asStateFlow()

    /**
     * Logs a diagnostic event and appends to the reactive history.
     */
    fun log(severity: DiagnosticSeverity, tag: String, message: String) {
        val entry = DiagnosticLog(severity = severity, tag = tag, message = message)
        val updated = (_logs.value + entry).takeLast(MAX_LOG_HISTORY)
        _logs.value = updated

        when (severity) {
            DiagnosticSeverity.INFO -> Log.i(tag, message)
            DiagnosticSeverity.SUCCESS -> Log.i(tag, "✅ $message")
            DiagnosticSeverity.WARNING -> Log.w(tag, "⚠️ $message")
            DiagnosticSeverity.ERROR -> Log.e(tag, "❌ $message")
        }
    }

    /**
     * Updates the active health report snapshot.
     */
    fun updateHealth(
        status: ConnectionStatus,
        isAttached: Boolean,
        accessoryDesc: String?,
        hasPermission: Boolean,
        pfd: ParcelFileDescriptor?,
        lastError: String? = null
    ) {
        val isFdActive = isFileDescriptorValid(pfd)
        val fdInt = try {
            pfd?.fd
        } catch (_: Exception) {
            null
        }

        _healthReport.value = AoaHealthReport(
            connectionStatus = status,
            isAccessoryAttached = isAttached,
            accessoryDescription = accessoryDesc,
            hasUsbPermission = hasPermission,
            isFileDescriptorActive = isFdActive,
            fileDescriptorInt = fdInt,
            lastError = lastError
        )
    }

    /**
     * Verifies whether the underlying native file descriptor is still valid and not closed.
     */
    fun isFileDescriptorValid(pfd: ParcelFileDescriptor?): Boolean {
        if (pfd == null) return false
        return try {
            val fd: FileDescriptor = pfd.fileDescriptor
            fd.valid()
        } catch (e: Exception) {
            log(DiagnosticSeverity.ERROR, TAG, "FD validation error: ${e.message}")
            false
        }
    }

    /**
     * Clears diagnostic event history.
     */
    fun clearLogs() {
        _logs.value = emptyList()
    }
}

