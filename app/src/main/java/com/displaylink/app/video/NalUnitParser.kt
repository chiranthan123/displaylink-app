package com.displaylink.app.video

import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * NAL (Network Abstraction Layer) Unit types for H.264 (AVC).
 */
enum class NalType(val typeId: Int, val description: String) {
    UNSPECIFIED(0, "Unspecified"),
    NON_IDR_SLICE(1, "Non-IDR Slice (P/B-Frame)"),
    SLICE_DATA_A(2, "Slice Data Partition A"),
    SLICE_DATA_B(3, "Slice Data Partition B"),
    SLICE_DATA_C(4, "Slice Data Partition C"),
    IDR_SLICE(5, "IDR Slice (Keyframe I-Frame)"),
    SEI(6, "Supplemental Enhancement Information (SEI)"),
    SPS(7, "Sequence Parameter Set (SPS)"),
    PPS(8, "Picture Parameter Set (PPS)"),
    ACCESS_UNIT_DELIMITER(9, "Access Unit Delimiter (AUD)"),
    END_OF_SEQUENCE(10, "End of Sequence"),
    END_OF_STREAM(11, "End of Stream"),
    FILLER_DATA(12, "Filler Data"),
    UNKNOWN(-1, "Unknown NAL");

    companion object {
        fun fromHeaderByte(headerByte: Byte): NalType {
            val typeId = headerByte.toInt() and 0x1F
            return entries.firstOrNull { it.typeId == typeId } ?: UNKNOWN
        }
    }
}

/**
 * Stream-based NAL (Network Abstraction Layer) Unit Parser.
 *
 * Scans continuous raw byte streams (Annex-B) for H.264 start codes:
 * - 4-byte start code: 0x00 0x00 0x00 0x01
 * - 3-byte start code: 0x00 0x00 0x01
 *
 * Handles chunk fragmentation across read cycles, extracts complete NAL units,
 * and emits them for MediaCodec ingestion.
 */
class NalUnitParser {

    companion object {
        private const val MAX_BUFFER_CAPACITY = 4 * 1024 * 1024 // 4 MB safety limit
    }

    // Accumulation buffer for fragmented stream ingestion
    private var buffer = ByteArray(128 * 1024) // 128 KB initial buffer
    private var bufferLength = 0

    /**
     * Ingests a new slice of bytes from the AOA input stream, scans for start codes,
     * and invokes [onNalUnit] for every fully extracted NAL unit.
     */
    @Synchronized
    fun feedBytes(data: ByteArray, offset: Int = 0, length: Int = data.size, onNalUnit: (ByteArray) -> Unit) {
        if (length <= 0) return

        ensureCapacity(bufferLength + length)
        System.arraycopy(data, offset, buffer, bufferLength, length)
        bufferLength += length

        var searchIndex = 0

        while (true) {
            val firstStartCode = findStartCode(buffer, searchIndex, bufferLength)
            if (firstStartCode == null) {
                // Keep the trailing potential start code prefix bytes (up to 3 bytes)
                val retainCount = minOf(bufferLength, 3)
                if (retainCount > 0 && bufferLength > retainCount) {
                    System.arraycopy(buffer, bufferLength - retainCount, buffer, 0, retainCount)
                    bufferLength = retainCount
                }
                break
            }

            // Find the next start code to delineate the boundary of this NAL unit
            val nextStartCode = findStartCode(buffer, firstStartCode.index + firstStartCode.length, bufferLength)
            if (nextStartCode == null) {
                // NAL unit is not yet complete; wait for the next chunk read
                if (firstStartCode.index > 0) {
                    val remaining = bufferLength - firstStartCode.index
                    System.arraycopy(buffer, firstStartCode.index, buffer, 0, remaining)
                    bufferLength = remaining
                }
                break
            }

            // Extract the complete NAL unit including its start code
            val nalLength = nextStartCode.index - firstStartCode.index
            val nalUnit = ByteArray(nalLength)
            System.arraycopy(buffer, firstStartCode.index, nalUnit, 0, nalLength)

            onNalUnit(nalUnit)

            // Advance search position to the beginning of the next start code
            searchIndex = nextStartCode.index
        }
    }

    /**
     * Flushes any remaining bytes in the buffer as a final NAL unit.
     */
    @Synchronized
    fun flush(onNalUnit: (ByteArray) -> Unit) {
        if (bufferLength > 4) {
            val startCode = findStartCode(buffer, 0, bufferLength)
            if (startCode != null && startCode.index == 0) {
                val nal = ByteArray(bufferLength)
                System.arraycopy(buffer, 0, nal, 0, bufferLength)
                onNalUnit(nal)
            }
        }
        reset()
    }

    /**
     * Clears internal parser buffer.
     */
    @Synchronized
    fun reset() {
        bufferLength = 0
    }

    /**
     * Inspects the NAL unit type from an extracted NAL unit buffer.
     */
    fun parseNalType(nalUnit: ByteArray): NalType {
        val startCode = findStartCode(nalUnit, 0, nalUnit.size) ?: return NalType.UNKNOWN
        val headerIndex = startCode.index + startCode.length
        if (headerIndex < nalUnit.size) {
            return NalType.fromHeaderByte(nalUnit[headerIndex])
        }
        return NalType.UNKNOWN
    }

    /**
     * Returns true if the NAL unit represents an IDR keyframe, SPS, or PPS.
     */
    fun isKeyframeOrConfig(nalUnit: ByteArray): Boolean {
        return when (parseNalType(nalUnit)) {
            NalType.IDR_SLICE, NalType.SPS, NalType.PPS -> true
            else -> false
        }
    }

    private data class StartCodeMatch(val index: Int, val length: Int)

    /**
     * Searches for 4-byte (0x00 00 00 01) or 3-byte (0x00 00 01) start codes.
     */
    private fun findStartCode(bytes: ByteArray, startIndex: Int, endIndex: Int): StartCodeMatch? {
        val maxIndex = endIndex - 3
        var i = startIndex
        while (i < maxIndex) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                if (bytes[i + 2] == 1.toByte()) {
                    return StartCodeMatch(index = i, length = 3)
                } else if (i < endIndex - 3 && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                    return StartCodeMatch(index = i, length = 4)
                }
            }
            i++
        }
        return null
    }

    private fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity > buffer.size) {
            val newCapacity = minOf(maxOf(buffer.size * 2, requiredCapacity), MAX_BUFFER_CAPACITY)
            buffer = Arrays.copyOf(buffer, newCapacity)
        }
    }
}

