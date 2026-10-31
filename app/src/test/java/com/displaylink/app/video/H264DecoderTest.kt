package com.displaylink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [H264Decoder] verifying desktop resolution presets,
 * initial states, and lifecycle safety.
 */
class H264DecoderTest {

    private lateinit var decoder: H264Decoder

    @Before
    fun setUp() {
        decoder = H264Decoder()
    }

    @Test
    fun testDesktopResolutionPresets() {
        assertEquals(1920, DesktopResolution.FHD_1080P.width)
        assertEquals(1080, DesktopResolution.FHD_1080P.height)

        assertEquals(2560, DesktopResolution.QHD_1440P.width)
        assertEquals(1440, DesktopResolution.QHD_1440P.height)

        assertEquals(3840, DesktopResolution.UHD_4K.width)
        assertEquals(2160, DesktopResolution.UHD_4K.height)

        assertEquals(1280, DesktopResolution.HD_720P.width)
        assertEquals(720, DesktopResolution.HD_720P.height)

        // Lookup from dimensions
        assertEquals(DesktopResolution.FHD_1080P, DesktopResolution.fromDimensions(1920, 1080))
        assertEquals(DesktopResolution.QHD_1440P, DesktopResolution.fromDimensions(2560, 1440))
        assertEquals(DesktopResolution.UHD_4K, DesktopResolution.fromDimensions(3840, 2160))
        assertEquals(DesktopResolution.HD_720P, DesktopResolution.fromDimensions(1280, 720))
        // Fallback
        assertEquals(DesktopResolution.FHD_1080P, DesktopResolution.fromDimensions(1000, 1000))
    }

    @Test
    fun testInitialState() {
        assertFalse(decoder.isDecoding.value)
        assertEquals(0L, decoder.framesRendered.value)
        assertEquals(0f, decoder.rollingFps.value)
        assertEquals(DesktopResolution.FHD_1080P, decoder.currentResolution.value)
        assertNull(decoder.lastError.value)
    }

    @Test
    fun testStopAndReleaseWhenNotRunning_isSafe() {
        decoder.stop()
        assertFalse(decoder.isDecoding.value)

        decoder.release()
        assertFalse(decoder.isDecoding.value)
    }

    @Test
    fun testFeedChunk_whenNotRunning_doesNotCrash() {
        val dummyData = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65)
        decoder.feedChunk(dummyData)
        assertEquals(0L, decoder.framesRendered.value)
    }

    @Test
    fun testBuildTelemetryMessage_containsExpectedFields() {
        val json = decoder.buildTelemetryMessage(
            command = "reduce_bitrate",
            latencyMs = 75.4f,
            queueSize = 20,
            droppedNals = 5L
        )

        assertTrue(json.contains("\"command\": \"reduce_bitrate\""))
        assertTrue(json.contains("\"latency_ms\": 75.4"))
        assertTrue(json.contains("\"queue_size\": 20"))
        assertTrue(json.contains("\"dropped_nals\": 5"))
    }

    @Test
    fun testBufferBloatDetection_triggersReduceBitrateCallback() {
        var receivedMessage: String? = null
        decoder.onTelemetryMessage = { msg ->
            receivedMessage = msg
        }

        // Trigger buffer bloat with high latency
        decoder.checkBufferBloat(latencyMs = 120f, queueSize = 5, recentDropped = 0L)

        assertNotNull(receivedMessage)
        assertTrue(receivedMessage!!.contains("\"command\": \"reduce_bitrate\""))
        assertTrue(receivedMessage!!.contains("\"latency_ms\": 120.0"))
    }

    @Test
    fun testBufferBloatDetection_triggersOnHighQueueSize() {
        var receivedMessage: String? = null
        decoder.onTelemetryMessage = { msg ->
            receivedMessage = msg
        }

        // Trigger buffer bloat with queue bloat (> 15)
        decoder.checkBufferBloat(latencyMs = 20f, queueSize = 25, recentDropped = 0L)

        assertNotNull(receivedMessage)
        assertTrue(receivedMessage!!.contains("\"command\": \"reduce_bitrate\""))
        assertTrue(receivedMessage!!.contains("\"queue_size\": 25"))
    }
}

