package com.displaylink.app.video

import android.content.Context
import com.displaylink.app.aoa.AoaDiagnosticUtility
import com.displaylink.app.aoa.AoaManager
import com.displaylink.app.aoa.AoaMockTestHarness
import com.displaylink.app.aoa.MockUsbAccessory
import com.displaylink.app.ui.components.ConnectionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for [AoaVideoPipeline] verifying coroutine reader ingestion,
 * NAL unit routing, and teardown upon accessory detachment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AoaVideoPipelineTest {

    private lateinit var harness: AoaMockTestHarness
    private lateinit var aoaManager: AoaManager
    private lateinit var h264Decoder: H264Decoder
    private lateinit var pipeline: AoaVideoPipeline

    @Before
    fun setUp() {
        harness = AoaMockTestHarness()
        aoaManager = AoaManager(
            context = StubContext(),
            usbAdapter = harness,
            diagnostics = AoaDiagnosticUtility()
        )
        h264Decoder = H264Decoder()
        pipeline = AoaVideoPipeline(
            aoaManager = aoaManager,
            h264Decoder = h264Decoder
        )
    }

    @Test
    fun testInitialPipelineState() {
        val state = pipeline.pipelineState.value
        assertFalse(state.isPipelineActive)
        assertFalse(state.isReaderRunning)
        assertEquals(0L, state.nalUnitsParsed)
        assertEquals(0L, state.bytesRead)
        assertNull(state.lastError)
    }

    @Test
    fun testTeardownWhenNotRunning_isSafe() {
        pipeline.teardown(reason = "Manual test")
        assertFalse(pipeline.pipelineState.value.isPipelineActive)
    }

    @Test
    fun testNalUnitIngestionFromStream() {
        // Stream containing SPS, PPS, and IDR NAL units
        val testStream = ByteArrayInputStream(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E,
                0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte(),
                0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(),
                0x00, 0x00, 0x00, 0x01 // terminator
            )
        )

        val parser = NalUnitParser()
        val extractedNals = mutableListOf<ByteArray>()

        val buffer = ByteArray(64)
        var bytesRead = testStream.read(buffer)
        while (bytesRead != -1) {
            parser.feedBytes(buffer, 0, bytesRead) {
                extractedNals.add(it)
            }
            bytesRead = testStream.read(buffer)
        }

        assertEquals(3, extractedNals.size)
        assertEquals(NalType.SPS, parser.parseNalType(extractedNals[0]))
        assertEquals(NalType.PPS, parser.parseNalType(extractedNals[1]))
        assertEquals(NalType.IDR_SLICE, parser.parseNalType(extractedNals[2]))
    }

    @Test
    fun testAutoTeardownWhenAccessoryDetaches() = runTest {
        val testAccessory = MockUsbAccessory()
        harness.grantPermission(testAccessory)
        aoaManager.openAccessoryConnection(testAccessory)
        assertEquals(ConnectionStatus.CONNECTED, aoaManager.connectionStatus.value)

        // Simulate physical detachment
        harness.simulateUnexpectedDisconnection(aoaManager, testAccessory)
        assertEquals(ConnectionStatus.AWAITING, aoaManager.connectionStatus.value)

        advanceUntilIdle()

        // Pipeline should be safely torn down
        assertFalse(pipeline.pipelineState.value.isPipelineActive)
    }

    @Test
    fun testTeardownOnAppBackgrounded_freesResourcesAndCancelsCoroutines() = runTest {
        val testAccessory = MockUsbAccessory()
        harness.grantPermission(testAccessory)
        aoaManager.openAccessoryConnection(testAccessory)
        assertEquals(ConnectionStatus.CONNECTED, aoaManager.connectionStatus.value)

        // When app moves to background, teardown is called
        pipeline.teardown(reason = "App moved to background (onStop)")

        advanceUntilIdle()

        val state = pipeline.pipelineState.value
        assertFalse("Pipeline should not be active when backgrounded", state.isPipelineActive)
        assertFalse("Reader coroutine should be stopped", state.isReaderRunning)
        assertFalse("Decoder should be inactive", h264Decoder.isDecoding.value)
    }

    @Test
    fun testTelemetryForwardingFromDecoderToAoaManager() = runTest {
        val testAccessory = MockUsbAccessory()
        harness.grantPermission(testAccessory)
        aoaManager.openAccessoryConnection(testAccessory)
        assertEquals(ConnectionStatus.CONNECTED, aoaManager.connectionStatus.value)

        val testStream = java.io.ByteArrayOutputStream()
        aoaManager.telemetryStreamOverride = testStream

        // Trigger buffer bloat on decoder
        h264Decoder.checkBufferBloat(latencyMs = 100f, queueSize = 20)

        Thread.sleep(150)

        val output = testStream.toString("UTF-8")
        assertTrue(output.contains("\"command\": \"reduce_bitrate\""))
    }
}

private class StubContext : com.displaylink.app.aoa.ContextWrapperStub() {
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.displaylink.app"
}

