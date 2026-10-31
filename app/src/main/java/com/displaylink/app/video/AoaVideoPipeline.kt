package com.displaylink.app.video

import android.util.Log
import android.view.Surface
import com.displaylink.app.aoa.AoaManager
import com.displaylink.app.ui.components.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipeline state information for telemetry.
 */
data class AoaVideoPipelineState(
    val isPipelineActive: Boolean = false,
    val isReaderRunning: Boolean = false,
    val nalUnitsParsed: Long = 0L,
    val bytesRead: Long = 0L,
    val lastError: String? = null
)

/**
 * End-to-end data pipeline connecting [AoaManager] to [H264Decoder].
 *
 * Architecture:
 * - Runs a dedicated reader coroutine on [Dispatchers.IO] that continuously reads
 *   bytes from the AOA [java.io.FileInputStream].
 * - Ingests chunks into [NalUnitParser], detecting Annex-B start codes (0x00 0x00 0x00 0x01).
 * - For each complete NAL unit, dequeues an input buffer from [MediaCodec], copies
 *   the bytes, queues for hardware decoding, and releases the decoded frame to the [Surface].
 * - Automatically performs graceful teardown of the codec and reader coroutine
 *   when the USB accessory is detached.
 */
class AoaVideoPipeline(
    val aoaManager: AoaManager,
    val h264Decoder: H264Decoder = H264Decoder()
) {

    companion object {
        private const val TAG = "AoaVideoPipeline"
        private const val READ_BUFFER_SIZE = 32 * 1024 // 32 KB chunk reader
    }

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var statusMonitorJob: Job? = null
    private var telemetryJob: Job? = null

    private val nalParser = NalUnitParser()
    private val isRunning = AtomicBoolean(false)

    private val _pipelineState = MutableStateFlow(AoaVideoPipelineState())
    val pipelineState: StateFlow<AoaVideoPipelineState> = _pipelineState.asStateFlow()

    init {
        // Automatically watch connection state to teardown on detachment
        statusMonitorJob = pipelineScope.launch {
            aoaManager.connectionStatus.collect { status ->
                if (status != ConnectionStatus.CONNECTED && isRunning.get()) {
                    Log.i(TAG, "AOA connection state changed to $status. Triggering graceful teardown.")
                    teardown(reason = "USB accessory detached")
                }
            }
        }

        // Forward stream health telemetry from H264Decoder directly to AoaManager
        h264Decoder.onTelemetryMessage = { message ->
            aoaManager.sendTelemetry(message)
        }
    }

    /**
     * Starts the video pipeline:
     * 1. Configures H264Decoder with the hardware Surface.
     * 2. Starts the decoder.
     * 3. Launches the dedicated reader coroutine on [Dispatchers.IO].
     *
     * @param surface Native hardware Surface from DisplayCanvas.
     * @param resolution Desktop resolution preset.
     * @param sourceStreamOverride Optional stream override for testing.
     */
    @Synchronized
    fun start(
        surface: Surface,
        resolution: DesktopResolution = DesktopResolution.FHD_1080P,
        sourceStreamOverride: InputStream? = null
    ): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "AoaVideoPipeline is already running. Stopping previous session...")
            teardown(reason = "Restarting pipeline")
        }

        if (!surface.isValid) {
            val err = "Cannot start pipeline: Surface is invalid."
            Log.e(TAG, err)
            _pipelineState.value = _pipelineState.value.copy(lastError = err)
            return false
        }

        // Configure H.264 decoder with the target Surface
        if (!h264Decoder.configure(surface = surface, resolution = resolution, useAsyncMode = false)) {
            Log.e(TAG, "Failed to configure H264Decoder.")
            return false
        }

        if (!h264Decoder.start()) {
            Log.e(TAG, "Failed to start H264Decoder.")
            return false
        }

        isRunning.set(true)
        nalParser.reset()

        val inputStream = sourceStreamOverride ?: aoaManager.inputStream
        if (inputStream == null) {
            val err = "Cannot start reader coroutine: AOA inputStream is null."
            Log.w(TAG, err)
            _pipelineState.value = _pipelineState.value.copy(lastError = err)
            return false
        }

        // Launch dedicated reader coroutine on Dispatchers.IO
        readerJob = pipelineScope.launch(Dispatchers.IO) {
            runReaderLoop(inputStream)
        }

        // Forward telemetry from H264Decoder flow to AoaManager
        telemetryJob?.cancel()
        telemetryJob = pipelineScope.launch(Dispatchers.IO) {
            h264Decoder.telemetryFlow.collect { message ->
                aoaManager.sendTelemetry(message)
            }
        }

        _pipelineState.value = AoaVideoPipelineState(
            isPipelineActive = true,
            isReaderRunning = true
        )
        Log.i(TAG, "AoaVideoPipeline started on Dispatchers.IO")
        return true
    }

    /**
     * Continuous reading and NAL parsing loop executing on Dispatchers.IO.
     */
    private suspend fun runReaderLoop(inputStream: InputStream) {
        val readBuffer = ByteArray(READ_BUFFER_SIZE)
        var totalBytes = 0L
        var totalNalUnits = 0L

        Log.i(TAG, "Reader coroutine loop active on thread: ${Thread.currentThread().name}")

        try {
            while (pipelineScope.isActive && isRunning.get()) {
                val bytesRead = try {
                    inputStream.read(readBuffer)
                } catch (e: IOException) {
                    Log.w(TAG, "AOA inputStream closed or read error: ${e.message}")
                    break
                }

                if (bytesRead == -1) {
                    Log.i(TAG, "AOA InputStream reached EOF (cable unplugged)")
                    break
                }

                if (bytesRead > 0) {
                    totalBytes += bytesRead

                    // Scan byte stream for NAL start codes (0x00 00 00 01)
                    nalParser.feedBytes(readBuffer, 0, bytesRead) { nalUnit ->
                        totalNalUnits++

                        // Dequeue input buffer from MediaCodec, copy NAL bytes,
                        // queue for decoding, and release output buffer to render frame
                        h264Decoder.decodeNalUnit(nalUnit)
                    }

                    _pipelineState.value = _pipelineState.value.copy(
                        bytesRead = totalBytes,
                        nalUnitsParsed = totalNalUnits
                    )
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "AOA reader coroutine cancelled cleanly.")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in AOA reader coroutine", e)
            _pipelineState.value = _pipelineState.value.copy(lastError = e.message)
        } finally {
            teardown(reason = "Reader coroutine loop exited")
        }
    }

    /**
     * Performs graceful teardown of the codec, reader thread/coroutine, and parser.
     */
    @Synchronized
    fun teardown(reason: String = "Teardown requested") {
        if (!isRunning.getAndSet(false)) {
            return
        }

        Log.i(TAG, "Executing graceful teardown. Reason: $reason")

        // 1. Cancel the reader coroutine on Dispatchers.IO
        readerJob?.cancel()
        readerJob = null

        // 2. Cancel telemetry forwarding
        telemetryJob?.cancel()
        telemetryJob = null

        // 3. Clear NAL parser buffer
        nalParser.reset()

        // 4. Stop and release hardware MediaCodec
        h264Decoder.teardown(reason = reason)

        _pipelineState.value = _pipelineState.value.copy(
            isPipelineActive = false,
            isReaderRunning = false
        )
        Log.i(TAG, "AoaVideoPipeline teardown complete.")
    }

    /**
     * Complete lifecycle disposal when activity or service is destroyed.
     */
    fun destroy() {
        teardown(reason = "Pipeline destroy")
        statusMonitorJob?.cancel()
        pipelineScope.cancel()
    }
}

