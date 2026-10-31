package com.displaylink.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Telemetry metrics for the active video decoding session.
 */
data class VideoDecoderStats(
    val isRunning: Boolean = false,
    val framesDecoded: Long = 0L,
    val bytesIngested: Long = 0L,
    val estimatedFps: Float = 0f,
    val streamWidth: Int = 1920,
    val streamHeight: Int = 1080,
    val lastError: String? = null
)

/**
 * Placeholder MediaCodec H.264 (AVC) video decoding worker thread.
 *
 * Architecture:
 * - Runs a dedicated background worker thread ingesting byte chunks from the AOA input stream.
 * - Enqueues Annex-B / H.264 NAL chunks into the hardware [MediaCodec] input buffers.
 * - Dequeues decoded video frames and renders them directly to the native [Surface]
 *   provided by DisplayCanvas (zero-copy GPU rendering).
 * - Emits real-time decoding telemetry via [stats].
 */
class VideoDecoderWorker {

    companion object {
        private const val TAG = "VideoDecoderWorker"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC // "video/avc" (H.264)
        private const val CHUNK_SIZE = 64 * 1024 // 64 KB ingestion buffer
        private const val DEQUEUE_TIMEOUT_US = 10_000L // 10ms timeout
    }

    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var mediaCodec: MediaCodec? = null

    private val _stats = MutableStateFlow(VideoDecoderStats())
    val stats: StateFlow<VideoDecoderStats> = _stats.asStateFlow()

    /**
     * Starts the H.264 decoding pipeline worker thread.
     *
     * @param surface Target hardware [Surface] provided by the DisplayCanvas SurfaceView.
     * @param inputStream Source [InputStream] providing raw H.264 stream data from AoaManager.
     * @param width Nominal video stream width (default 1080p).
     * @param height Nominal video stream height (default 1080p).
     */
    @Synchronized
    fun start(
        surface: Surface,
        inputStream: InputStream,
        width: Int = 1920,
        height: Int = 1080
    ) {
        if (isRunning.get()) {
            Log.w(TAG, "VideoDecoderWorker is already running. Stopping before restarting...")
            stop()
        }

        if (!surface.isValid) {
            Log.e(TAG, "Cannot start video decoder: provided Surface is invalid or destroyed.")
            _stats.value = _stats.value.copy(lastError = "Surface is invalid")
            return
        }

        isRunning.set(true)
        _stats.value = VideoDecoderStats(
            isRunning = true,
            streamWidth = width,
            streamHeight = height
        )

        workerThread = Thread({
            runDecoderLoop(surface, inputStream, width, height)
        }, "DisplayLink-H264Decoder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        Log.i(TAG, "VideoDecoderWorker thread started (${width}x${height} @ H.264)")
    }

    /**
     * Gracefully stops the worker thread and releases the hardware [MediaCodec].
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        Log.i(TAG, "Stopping VideoDecoderWorker thread...")
        workerThread?.interrupt()

        try {
            workerThread?.join(1500)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for decoder thread to terminate", e)
            Thread.currentThread().interrupt()
        } finally {
            workerThread = null
        }

        releaseCodec()

        _stats.value = _stats.value.copy(
            isRunning = false
        )
        Log.i(TAG, "VideoDecoderWorker stopped and codec released.")
    }

    /**
     * Core decoding and ingestion worker loop executing on the dedicated thread.
     */
    private fun runDecoderLoop(
        surface: Surface,
        inputStream: InputStream,
        initialWidth: Int,
        initialHeight: Int
    ) {
        var codec: MediaCodec? = null

        try {
            // 1. Configure H.264 (video/avc) hardware decoder format
            val format = MediaFormat.createVideoFormat(MIME_TYPE, initialWidth, initialHeight).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                // Android 11+ Low-Latency hint for interactive display mirroring
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }

            codec = MediaCodec.createDecoderByType(MIME_TYPE)
            // Bind hardware Surface directly to the codec for hardware-accelerated rendering
            codec.configure(format, surface, null, 0)
            codec.start()
            mediaCodec = codec

            Log.i(TAG, "MediaCodec configured and started with hardware surface")

            val bufferInfo = MediaCodec.BufferInfo()
            val chunkBuffer = ByteArray(CHUNK_SIZE)
            var totalBytes = 0L
            var totalFrames = 0L
            var lastFpsTimestamp = System.currentTimeMillis()
            var framesInWindow = 0L

            // 2. Ingestion & Decoding Loop
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                // Read next chunk from AOA stream
                val bytesRead = try {
                    inputStream.read(chunkBuffer)
                } catch (e: IOException) {
                    Log.w(TAG, "AOA InputStream read error or closed: ${e.message}")
                    break
                }

                if (bytesRead == -1) {
                    Log.i(TAG, "End of stream (EOF) reached on AOA input")
                    break
                }

                totalBytes += bytesRead

                // Dequeue input buffer from MediaCodec
                val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex >= 0) {
                    val inputBuffer: ByteBuffer? = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val copyLen = minOf(bytesRead, inputBuffer.remaining())
                        inputBuffer.put(chunkBuffer, 0, copyLen)

                        val ptsUs = System.nanoTime() / 1000L
                        codec.queueInputBuffer(inIndex, 0, copyLen, ptsUs, 0)
                    }
                }

                // Dequeue output buffer and render to hardware Surface
                var outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                while (outIndex >= 0) {
                    // render = true renders the decoded YUV frame directly onto the SurfaceView
                    codec.releaseOutputBuffer(outIndex, true)
                    totalFrames++
                    framesInWindow++
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                }

                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    val newWidth = newFormat.getInteger(MediaFormat.KEY_WIDTH, initialWidth)
                    val newHeight = newFormat.getInteger(MediaFormat.KEY_HEIGHT, initialHeight)
                    Log.i(TAG, "MediaCodec format updated: ${newWidth}x${newHeight}")
                    _stats.value = _stats.value.copy(
                        streamWidth = newWidth,
                        streamHeight = newHeight
                    )
                }

                // Compute rolling FPS telemetry every 1000ms
                val now = System.currentTimeMillis()
                if (now - lastFpsTimestamp >= 1000L) {
                    val currentFps = (framesInWindow * 1000f) / (now - lastFpsTimestamp)
                    framesInWindow = 0
                    lastFpsTimestamp = now

                    _stats.value = _stats.value.copy(
                        framesDecoded = totalFrames,
                        bytesIngested = totalBytes,
                        estimatedFps = currentFps
                    )
                }
            }
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "MediaCodec internal error [diagnostic=${e.diagnosticInfo}]", e)
            _stats.value = _stats.value.copy(lastError = e.message ?: "CodecException")
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Unexpected error in video decoder worker", e)
                _stats.value = _stats.value.copy(lastError = e.message ?: "Unexpected error")
            }
        } finally {
            releaseCodec()
            isRunning.set(false)
            _stats.value = _stats.value.copy(isRunning = false)
        }
    }

    private fun releaseCodec() {
        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Exception during MediaCodec.stop(): ${e.message}")
        }

        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Exception during MediaCodec.release(): ${e.message}")
        } finally {
            mediaCodec = null
        }
    }

    /**
     * Helper to generate a synthetic dummy H.264 Annex-B stream for local testing & preview.
     * Contains standard SPS/PPS header NAL units (Annex-B 0x00 00 00 01 prefix).
     */
    fun createSyntheticH264Stream(): InputStream {
        val dummyNalStream = byteArrayOf(
            // SPS NAL unit (type 7)
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E,
            // PPS NAL unit (type 8)
            0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte(),
            // IDR Slice NAL unit (type 5)
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x00
        )
        return ByteArrayInputStream(dummyNalStream)
    }
}

