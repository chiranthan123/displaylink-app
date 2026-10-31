package com.displaylink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests verifying [VideoDecoderWorker] state machine, synthetic stream generation,
 * and error handling.
 */
class VideoDecoderWorkerTest {

    private lateinit var decoderWorker: VideoDecoderWorker

    @Before
    fun setUp() {
        decoderWorker = VideoDecoderWorker()
    }

    @Test
    fun testInitialState_isNotRunning() {
        val stats = decoderWorker.stats.value
        assertFalse("Decoder should initially not be running", stats.isRunning)
        assertEquals(0L, stats.framesDecoded)
        assertEquals(0L, stats.bytesIngested)
        assertEquals(1920, stats.streamWidth)
        assertEquals(1080, stats.streamHeight)
    }

    @Test
    fun testStop_whenNotRunning_isSafeNoOp() {
        decoderWorker.stop()
        assertFalse(decoderWorker.stats.value.isRunning)
    }

    @Test
    fun testSyntheticH264Stream_generatesValidAnnexBStartCodes() {
        val stream = decoderWorker.createSyntheticH264Stream()
        assertNotNull("Synthetic stream should not be null", stream)

        val buffer = ByteArray(4)
        val read = stream.read(buffer)
        assertEquals(4, read)

        // Annex-B 4-byte start code: 0x00, 0x00, 0x00, 0x01
        assertEquals(0x00.toByte(), buffer[0])
        assertEquals(0x00.toByte(), buffer[1])
        assertEquals(0x00.toByte(), buffer[2])
        assertEquals(0x01.toByte(), buffer[3])
    }

    @Test
    fun testDecoderStats_dataClassBehavior() {
        val stats = VideoDecoderStats(
            isRunning = true,
            framesDecoded = 120L,
            bytesIngested = 1024000L,
            estimatedFps = 59.8f,
            streamWidth = 1920,
            streamHeight = 1080,
            lastError = null
        )

        assertTrue(stats.isRunning)
        assertEquals(120L, stats.framesDecoded)
        assertEquals(1024000L, stats.bytesIngested)
        assertEquals(59.8f, stats.estimatedFps)
        assertEquals(1920, stats.streamWidth)
        assertEquals(1080, stats.streamHeight)
    }
}

