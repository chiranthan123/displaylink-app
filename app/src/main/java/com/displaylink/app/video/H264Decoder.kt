package com.displaylink.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Standard desktop resolution presets for DisplayLink docking monitors.
 */
enum class DesktopResolution(val width: Int, val height: Int, val label: String) {
    HD_720P(1280, 720, "1280x720 (720p)"),
    FHD_1080P(1920, 1080, "1920x1080 (1080p FHD)"),
    QHD_1440P(2560, 1440, "2560x1440 (1440p 2K)"),
    UHD_4K(3840, 2160, "3840x2160 (4K UHD)");

    companion object {
        fun fromDimensions(w: Int, h: Int): DesktopResolution {
            return entries.firstOrNull { it.width == w && it.height == h } ?: FHD_1080P
        }
    }
}

/**
 * Low-latency, hardware-accelerated H.264 (AVC) video decoder.
 *
 * Key features:
 * - Uses [MediaCodec.createDecoderByType] with [MediaFormat.MIMETYPE_VIDEO_AVC]
 * - Configured with desktop resolution [DesktopResolution.FHD_1080P] and low-latency flags
 * - Binds the native [Surface] from DisplayCanvas so decoded frames render directly to screen
 * - Supports discrete NAL unit ingestion via [decodeNalUnit]
 * - Operates via an asynchronous callback on a dedicated background [HandlerThread]
 *   or synchronous ingestion from AOA stream reader coroutines
 */
class H264Decoder {

    companion object {
        private const val TAG = "H264Decoder"
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC // "video/avc"
        private const val DEFAULT_FRAME_RATE = 60
        private const val INPUT_CHUNK_SIZE = 64 * 1024 // 64 KB
        private const val DEQUEUE_TIMEOUT_US = 10_000L // 10ms

        // Buffer bloat detection thresholds
        const val LATENCY_BLOAT_THRESHOLD_MS = 60f   // Latency > 60ms triggers warning
        const val QUEUE_BLOAT_THRESHOLD = 15         // More than 15 pending chunks triggers warning
        const val DROPPED_NAL_BLOAT_THRESHOLD = 3L   // 3 or more dropped NAL units triggers warning
        const val TELEMETRY_COOLDOWN_MS = 1500L      // Minimum interval between reduce_bitrate alerts
    }

    private var mediaCodec: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var codecHandler: Handler? = null
    private var streamWorkerThread: Thread? = null

    private val isConfigured = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    // Telemetry and statistics
    private val framesRenderedCount = AtomicLong(0L)
    private val bytesIngestedCount = AtomicLong(0L)
    private val droppedNalCount = AtomicLong(0L)
    private var lastTelemetryEmissionTime = 0L

    private val _isDecoding = MutableStateFlow(false)
    val isDecoding: StateFlow<Boolean> = _isDecoding.asStateFlow()

    private val _currentResolution = MutableStateFlow(DesktopResolution.FHD_1080P)
    val currentResolution: StateFlow<DesktopResolution> = _currentResolution.asStateFlow()

    private val _framesRendered = MutableStateFlow(0L)
    val framesRendered: StateFlow<Long> = _framesRendered.asStateFlow()

    private val _rollingFps = MutableStateFlow(0f)
    val rollingFps: StateFlow<Float> = _rollingFps.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Latency & queue telemetry
    private val _decodingLatencyMs = MutableStateFlow(0f)
    val decodingLatencyMs: StateFlow<Float> = _decodingLatencyMs.asStateFlow()

    private val _currentQueueSize = MutableStateFlow(0)
    val currentQueueSize: StateFlow<Int> = _currentQueueSize.asStateFlow()

    private val _droppedNalUnits = MutableStateFlow(0L)
    val droppedNalUnits: StateFlow<Long> = _droppedNalUnits.asStateFlow()

    // Bi-directional telemetry flow & callback for AOA transmission
    private val _telemetryFlow = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val telemetryFlow: SharedFlow<String> = _telemetryFlow.asSharedFlow()

    var onTelemetryMessage: ((String) -> Unit)? = null

    // Queue of pending NAL chunks for asynchronous feeding
    private val pendingChunks = ConcurrentLinkedQueue<ByteArray>()

    /**
     * Initializes and configures the H.264 [MediaCodec] targeting the hardware [Surface].
     *
     * @param surface Native Surface received from DisplayCanvas (SurfaceView).
     * @param resolution Desktop resolution preset (defaults to 1920x1080).
     * @param useAsyncMode If true, configures asynchronous MediaCodec callbacks on a dedicated HandlerThread.
     */
    @Synchronized
    fun configure(
        surface: Surface,
        resolution: DesktopResolution = DesktopResolution.FHD_1080P,
        useAsyncMode: Boolean = false
    ): Boolean {
        if (!surface.isValid) {
            val error = "Cannot configure H264Decoder: Surface is invalid or destroyed."
            Log.e(TAG, error)
            _lastError.value = error
            return false
        }

        // Release any existing codec session
        release()

        try {
            Log.i(TAG, "Initializing hardware decoder: $MIME_TYPE for ${resolution.label}")

            // 1. Create decoder by MIME type
            val codec = MediaCodec.createDecoderByType(MIME_TYPE)

            // 2. Build low-latency desktop MediaFormat
            val format = MediaFormat.createVideoFormat(MIME_TYPE, resolution.width, resolution.height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                // Low-latency decoder hint for Android 11+ (API 30+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }

                // Operating rate hint for real-time smoothness
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
            }

            // 3. Set up Asynchronous Callback mode on a dedicated background thread if requested
            if (useAsyncMode) {
                val thread = HandlerThread("H264Decoder-AsyncThread").apply { start() }
                handlerThread = thread
                val handler = Handler(thread.looper)
                codecHandler = handler

                codec.setCallback(createAsyncCallback(codec), handler)
                Log.i(TAG, "MediaCodec asynchronous callback registered on background thread")
            }

            // 4. Pass the Surface received from DisplayCanvas so decoded frames render directly
            codec.configure(format, surface, null, 0)
            mediaCodec = codec
            isConfigured.set(true)
            _currentResolution.value = resolution
            _lastError.value = null

            Log.i(TAG, "H264Decoder configured successfully with hardware Surface.")
            return true
        } catch (e: Exception) {
            val error = "Failed to configure H264Decoder: ${e.message}"
            Log.e(TAG, error, e)
            _lastError.value = error
            release()
            return false
        }
    }

    /**
     * Starts the configured [MediaCodec] pipeline.
     */
    @Synchronized
    fun start(): Boolean {
        val codec = mediaCodec ?: run {
            Log.e(TAG, "Cannot start: codec not configured")
            return false
        }

        if (isRunning.get()) {
            Log.w(TAG, "H264Decoder is already running")
            return true
        }

        return try {
            codec.start()
            isRunning.set(true)
            _isDecoding.value = true
            framesRenderedCount.set(0L)
            bytesIngestedCount.set(0L)
            Log.i(TAG, "H264Decoder started.")
            true
        } catch (e: Exception) {
            val error = "Failed to start MediaCodec: ${e.message}"
            Log.e(TAG, error, e)
            _lastError.value = error
            false
        }
    }

    /**
     * Ingests a complete H.264 NAL unit into MediaCodec, queues it for decoding,
     * and drains available output buffers to render decoded frames to the Surface.
     *
     * @param nalUnit Complete NAL byte array (including Annex-B start code).
     * @param ptsUs Presentation timestamp in microseconds.
     */
    @Synchronized
    fun decodeNalUnit(nalUnit: ByteArray, ptsUs: Long = System.nanoTime() / 1000L): Boolean {
        val codec = mediaCodec ?: return false
        if (!isRunning.get()) return false

        bytesIngestedCount.addAndGet(nalUnit.size.toLong())

        // 1. Dequeue input buffer from hardware codec
        val inIndex = try {
            codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        } catch (e: Exception) {
            Log.w(TAG, "Exception during dequeueInputBuffer: ${e.message}")
            return false
        }

        if (inIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                val len = minOf(nalUnit.size, inputBuffer.remaining())
                inputBuffer.put(nalUnit, 0, len)

                // Inspect NAL unit header (SPS = 7, PPS = 8)
                val nalType = if (nalUnit.size > 4) nalUnit[4].toInt() and 0x1F else 0
                val flags = if (nalType == 7 || nalType == 8) {
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                } else {
                    0
                }

                try {
                    codec.queueInputBuffer(inIndex, 0, len, ptsUs, flags)
                } catch (e: Exception) {
                    Log.w(TAG, "Error queuing NAL input buffer: ${e.message}")
                    return false
                }
            }
        } else {
            // inIndex < 0: hardware codec input queue is full, frame dropped / backlogged
            val dropped = droppedNalCount.incrementAndGet()
            _droppedNalUnits.value = dropped
            Log.w(TAG, "Decoder input buffer unavailable (dequeue returned $inIndex). Dropped NAL count: $dropped")
            checkBufferBloat(
                latencyMs = _decodingLatencyMs.value,
                queueSize = pendingChunks.size,
                recentDropped = 1L
            )
        }

        // 2. Drain and render output buffers directly to the Surface
        drainOutputBuffers()
        return true
    }

    /**
     * Checks for buffer bloat conditions (high latency, bloated queue, or dropped NALs).
     * If detected, generates and emits a lightweight JSON control message to signal the host
     * streamer to reduce its bitrate or framerate.
     */
    fun checkBufferBloat(
        latencyMs: Float = _decodingLatencyMs.value,
        queueSize: Int = pendingChunks.size,
        recentDropped: Long = 0L
    ) {
        val now = System.currentTimeMillis()
        if (now - lastTelemetryEmissionTime < TELEMETRY_COOLDOWN_MS) {
            return
        }

        val isBloated = latencyMs > LATENCY_BLOAT_THRESHOLD_MS ||
                queueSize > QUEUE_BLOAT_THRESHOLD ||
                recentDropped >= DROPPED_NAL_BLOAT_THRESHOLD

        if (isBloated) {
            lastTelemetryEmissionTime = now
            val jsonMessage = buildTelemetryMessage(
                command = "reduce_bitrate",
                latencyMs = latencyMs,
                queueSize = queueSize,
                droppedNals = droppedNalCount.get()
            )
            Log.w(
                TAG,
                "Buffer bloat detected (latency=${latencyMs}ms, queue=$queueSize, dropped=$recentDropped). Emitting control message: $jsonMessage"
            )
            _telemetryFlow.tryEmit(jsonMessage)
            onTelemetryMessage?.invoke(jsonMessage)
        }
    }

    /**
     * Builds a lightweight JSON control message for host streaming telemetry.
     */
    fun buildTelemetryMessage(
        command: String = "reduce_bitrate",
        latencyMs: Float = _decodingLatencyMs.value,
        queueSize: Int = pendingChunks.size,
        droppedNals: Long = droppedNalCount.get()
    ): String {
        val latencyFormatted = String.format(Locale.US, "%.1f", latencyMs)
        return "{\"command\": \"$command\", \"latency_ms\": $latencyFormatted, \"queue_size\": $queueSize, \"dropped_nals\": $droppedNals}"
    }

    /**
     * Dequeues available decoded output buffers and renders them directly to the native Surface.
     */
    fun drainOutputBuffers() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            while (outIndex >= 0) {
                // Measure end-to-end decoding latency
                val nowUs = System.nanoTime() / 1000L
                if (bufferInfo.presentationTimeUs > 0) {
                    val latencyMs = (nowUs - bufferInfo.presentationTimeUs) / 1000f
                    if (latencyMs in 0f..5000f) {
                        val prev = _decodingLatencyMs.value
                        val smoothed = if (prev <= 0f) latencyMs else (prev * 0.7f + latencyMs * 0.3f)
                        _decodingLatencyMs.value = smoothed
                        checkBufferBloat(latencyMs = smoothed, queueSize = pendingChunks.size)
                    }
                }

                // render = true renders the frame onto the SurfaceView
                codec.releaseOutputBuffer(outIndex, /* render = */ true)
                val count = framesRenderedCount.incrementAndGet()
                _framesRendered.value = count

                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            }

            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                val w = format.getInteger(MediaFormat.KEY_WIDTH, _currentResolution.value.width)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT, _currentResolution.value.height)
                _currentResolution.value = DesktopResolution.fromDimensions(w, h)
                Log.i(TAG, "Decoder output format changed: ${w}x$h")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception draining output buffers: ${e.message}")
        }
    }

    /**
     * Feeds an H.264 byte chunk into the asynchronous input queue.
     */
    fun feedChunk(data: ByteArray) {
        if (!isRunning.get()) return
        pendingChunks.offer(data)
        val size = pendingChunks.size
        _currentQueueSize.value = size
        bytesIngestedCount.addAndGet(data.size.toLong())
        if (size > QUEUE_BLOAT_THRESHOLD) {
            checkBufferBloat(latencyMs = _decodingLatencyMs.value, queueSize = size)
        }
    }

    /**
     * Synchronous worker thread mode: ingests byte chunks directly from an AOA [InputStream]
     * and processes buffers synchronously on a dedicated background thread.
     */
    @Synchronized
    fun startStreamingFrom(surface: Surface, inputStream: InputStream, resolution: DesktopResolution = DesktopResolution.FHD_1080P) {
        stop()

        // Configure in synchronous mode (useAsyncMode = false)
        if (!configure(surface = surface, resolution = resolution, useAsyncMode = false)) {
            return
        }

        if (!start()) {
            return
        }

        streamWorkerThread = Thread({
            runSynchronousStreamingLoop(inputStream, resolution)
        }, "H264Decoder-StreamWorker").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        Log.i(TAG, "Synchronous streaming worker thread started for AOA ingestion")
    }

    /**
     * Synchronous buffer processing loop running on dedicated background thread.
     */
    private fun runSynchronousStreamingLoop(inputStream: InputStream, resolution: DesktopResolution) {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val chunk = ByteArray(INPUT_CHUNK_SIZE)

        var lastFpsTime = System.currentTimeMillis()
        var framesInSecond = 0L

        try {
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                val bytesRead = try {
                    inputStream.read(chunk)
                } catch (e: IOException) {
                    Log.w(TAG, "AOA stream read error or closed: ${e.message}")
                    break
                }

                if (bytesRead == -1) {
                    Log.i(TAG, "AOA InputStream reached EOF")
                    break
                }

                bytesIngestedCount.addAndGet(bytesRead.toLong())

                val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val copySize = minOf(bytesRead, inputBuffer.remaining())
                        inputBuffer.put(chunk, 0, copySize)
                        val ptsUs = System.nanoTime() / 1000L
                        codec.queueInputBuffer(inIndex, 0, copySize, ptsUs, 0)
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                while (outIndex >= 0) {
                    codec.releaseOutputBuffer(outIndex, true)

                    val rendered = framesRenderedCount.incrementAndGet()
                    framesInSecond++
                    _framesRendered.value = rendered

                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                }

                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    val w = newFormat.getInteger(MediaFormat.KEY_WIDTH, resolution.width)
                    val h = newFormat.getInteger(MediaFormat.KEY_HEIGHT, resolution.height)
                    _currentResolution.value = DesktopResolution.fromDimensions(w, h)
                    Log.i(TAG, "Decoder output format changed: ${w}x$h")
                }

                val now = System.currentTimeMillis()
                if (now - lastFpsTime >= 1000L) {
                    val fps = (framesInSecond * 1000f) / (now - lastFpsTime)
                    _rollingFps.value = fps
                    framesInSecond = 0L
                    lastFpsTime = now
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Error in synchronous H.264 stream decoding loop", e)
                _lastError.value = e.message
            }
        } finally {
            stop()
        }
    }

    /**
     * Creates an asynchronous [MediaCodec.Callback] executing on the background [HandlerThread].
     */
    private fun createAsyncCallback(codec: MediaCodec) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!isRunning.get()) return

            val chunk = pendingChunks.poll()
            if (chunk != null) {
                try {
                    val buffer: ByteBuffer? = codec.getInputBuffer(index)
                    if (buffer != null) {
                        buffer.clear()
                        val writeLen = minOf(chunk.size, buffer.remaining())
                        buffer.put(chunk, 0, writeLen)
                        val ptsUs = System.nanoTime() / 1000L
                        codec.queueInputBuffer(index, 0, writeLen, ptsUs, 0)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error queuing async input buffer: ${e.message}")
                }
            } else {
                try {
                    codec.queueInputBuffer(index, 0, 0, 0, 0)
                } catch (_: Exception) {}
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!isRunning.get()) return

            try {
                val nowUs = System.nanoTime() / 1000L
                if (info.presentationTimeUs > 0) {
                    val latencyMs = (nowUs - info.presentationTimeUs) / 1000f
                    if (latencyMs in 0f..5000f) {
                        val prev = _decodingLatencyMs.value
                        val smoothed = if (prev <= 0f) latencyMs else (prev * 0.7f + latencyMs * 0.3f)
                        _decodingLatencyMs.value = smoothed
                        checkBufferBloat(latencyMs = smoothed, queueSize = pendingChunks.size)
                    }
                }

                codec.releaseOutputBuffer(index, true)
                val total = framesRenderedCount.incrementAndGet()
                _framesRendered.value = total
            } catch (e: Exception) {
                Log.w(TAG, "Error rendering async output buffer: ${e.message}")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "MediaCodec asynchronous error [diagnostic=${e.diagnosticInfo}]", e)
            _lastError.value = "MediaCodec error: ${e.message}"
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val width = format.getInteger(MediaFormat.KEY_WIDTH, _currentResolution.value.width)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT, _currentResolution.value.height)
            _currentResolution.value = DesktopResolution.fromDimensions(width, height)
            Log.i(TAG, "MediaCodec output format updated: ${width}x$height")
        }
    }

    /**
     * Stops the decoding pipeline.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        _isDecoding.value = false
        Log.i(TAG, "Stopping H264Decoder...")

        streamWorkerThread?.interrupt()
        try {
            streamWorkerThread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            streamWorkerThread = null
        }

        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopping MediaCodec: ${e.message}")
        }

        pendingChunks.clear()
        _currentQueueSize.value = 0
        Log.i(TAG, "H264Decoder stopped.")
    }

    /**
     * Completely releases the MediaCodec and background threads.
     */
    @Synchronized
    fun release() {
        stop()

        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Exception releasing MediaCodec: ${e.message}")
        } finally {
            mediaCodec = null
            isConfigured.set(false)
        }

        handlerThread?.quitSafely()
        handlerThread = null
        codecHandler = null

        Log.i(TAG, "H264Decoder fully released.")
    }

    /**
     * Performs graceful teardown of the decoder when USB is detached.
     */
    fun teardown(reason: String = "USB detached") {
        Log.i(TAG, "Teardown called on H264Decoder. Reason: $reason")
        release()
    }
}
