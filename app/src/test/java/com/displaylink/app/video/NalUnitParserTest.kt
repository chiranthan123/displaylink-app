package com.displaylink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [NalUnitParser] verifying Annex-B start code detection,
 * stream fragmentation handling, and NAL type extraction.
 */
class NalUnitParserTest {

    private lateinit var parser: NalUnitParser

    @Before
    fun setUp() {
        parser = NalUnitParser()
    }

    @Test
    fun testParseMultipleNalUnitsInSingleChunk() {
        // Two NAL units: SPS (type 7) followed by PPS (type 8)
        val stream = byteArrayOf(
            // SPS
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E,
            // PPS
            0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte(),
            // Trailing start code
            0x00, 0x00, 0x00, 0x01, 0x65
        )

        val parsedNals = mutableListOf<ByteArray>()
        parser.feedBytes(stream) { parsedNals.add(it) }

        assertEquals(2, parsedNals.size)

        // First NAL is SPS (0x67 & 0x1F = 7)
        assertEquals(8, parsedNals[0].size)
        assertEquals(NalType.SPS, parser.parseNalType(parsedNals[0]))
        assertTrue(parser.isKeyframeOrConfig(parsedNals[0]))

        // Second NAL is PPS (0x68 & 0x1F = 8)
        assertEquals(8, parsedNals[1].size)
        assertEquals(NalType.PPS, parser.parseNalType(parsedNals[1]))
        assertTrue(parser.isKeyframeOrConfig(parsedNals[1]))
    }

    @Test
    fun testFragmentedStreamHandlingAcrossMultipleReads() {
        // An IDR slice split across two separate reads
        val chunk1 = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte()
        )
        val chunk2 = byteArrayOf(
            0x12, 0x34, 0x56,
            0x00, 0x00, 0x00, 0x01, 0x41 // Next slice start
        )

        val parsedNals = mutableListOf<ByteArray>()
        parser.feedBytes(chunk1) { parsedNals.add(it) }
        assertEquals(0, parsedNals.size) // Not yet complete

        parser.feedBytes(chunk2) { parsedNals.add(it) }
        assertEquals(1, parsedNals.size) // Complete IDR slice emitted

        val idrNal = parsedNals[0]
        assertEquals(10, idrNal.size) // 7 bytes from chunk1 + 3 bytes from chunk2
        assertEquals(NalType.IDR_SLICE, parser.parseNalType(idrNal))
    }

    @Test
    fun testStartCodeSplitAcrossChunkBoundary() {
        // The start code 0x00 0x00 0x00 0x01 is split: [0x00, 0x00] in chunk1, [0x00, 0x01] in chunk2
        val chunk1 = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42,
            0x00, 0x00 // partial start code
        )
        val chunk2 = byteArrayOf(
            0x00, 0x01, 0x68, 0x10, // finishes start code + PPS
            0x00, 0x00, 0x00, 0x01, 0x65 // trailing delimiter
        )

        val parsedNals = mutableListOf<ByteArray>()
        parser.feedBytes(chunk1) { parsedNals.add(it) }
        parser.feedBytes(chunk2) { parsedNals.add(it) }

        assertEquals(2, parsedNals.size)
        assertEquals(NalType.SPS, parser.parseNalType(parsedNals[0]))
        assertEquals(NalType.PPS, parser.parseNalType(parsedNals[1]))
    }

    @Test
    fun testThreeByteStartCodeSupport() {
        val stream = byteArrayOf(
            0x00, 0x00, 0x01, 0x67, 0x42,
            0x00, 0x00, 0x01, 0x68, 0x10,
            0x00, 0x00, 0x01, 0x65
        )

        val parsedNals = mutableListOf<ByteArray>()
        parser.feedBytes(stream) { parsedNals.add(it) }

        assertEquals(2, parsedNals.size)
        assertEquals(NalType.SPS, parser.parseNalType(parsedNals[0]))
        assertEquals(NalType.PPS, parser.parseNalType(parsedNals[1]))
    }

    @Test
    fun testResetClearsBuffer() {
        val partialChunk = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67)
        parser.feedBytes(partialChunk) {}

        parser.reset()

        val parsedNals = mutableListOf<ByteArray>()
        parser.flush { parsedNals.add(it) }
        assertEquals(0, parsedNals.size)
    }
}

