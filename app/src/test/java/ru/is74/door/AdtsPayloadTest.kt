package ru.is74.door

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.is74.door.data.SegmentMuxException
import ru.is74.door.data.adtsPayloadOffset
import java.nio.ByteBuffer

class AdtsPayloadTest {
    private fun frame(crc: Boolean = false): ByteArray {
        val header = if (crc) 9 else 7
        val length = header + 11
        return ByteArray(length) { 0x31 }.also {
            it[0] = 0xff.toByte(); it[1] = (if (crc) 0xf0 else 0xf1).toByte()
            it[2] = 0x6c; it[3] = (0x40 or (length shr 11)).toByte()
            it[4] = (length shr 3).toByte(); it[5] = (((length and 7) shl 5) or 0x1f).toByte(); it[6] = 0xfc.toByte()
        }
    }
    @Test fun transportHeaderRemovedWithoutChangingAacPayload() {
        for (crc in listOf(false, true)) {
            val bytes = frame(crc)
            assertEquals(if (crc) 9 else 7, adtsPayloadOffset(ByteBuffer.wrap(bytes), bytes.size, true, 8_000, 1))
        }
    }
    @Test fun rawAacStartingWithSyncBytesIsNeverStripped() {
        val bytes = frame()
        assertEquals(0, adtsPayloadOffset(ByteBuffer.wrap(bytes), bytes.size, false))
    }
    @Test(expected = SegmentMuxException::class) fun truncatedFrameRejected() {
        val bytes = frame()
        adtsPayloadOffset(ByteBuffer.wrap(bytes), bytes.size - 1, true)
    }
    @Test(expected = SegmentMuxException::class) fun changedAudioRateRejected() {
        val bytes = frame()
        adtsPayloadOffset(ByteBuffer.wrap(bytes), bytes.size, true, 16_000, 1)
    }
    @Test(expected = SegmentMuxException::class) fun multipleRawBlocksRejectedExplicitly() {
        val bytes = frame().also { it[6] = 0xfd.toByte() }
        adtsPayloadOffset(ByteBuffer.wrap(bytes), bytes.size, true)
    }
}
