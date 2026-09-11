package com.ownerlink.agent

import com.ownerlink.agent.network.BinaryFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BinaryFramesTest {
    @Test
    fun fileFrameRoundTrip() {
        val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val payload = "abc".toByteArray()
        val raw = BinaryFrames.frame(BinaryFrames.KIND_FILE, id, 4, payload, last = true)
        val parsed = BinaryFrames.parse(raw)
        assertEquals(BinaryFrames.KIND_FILE, parsed.kind)
        assertEquals(id, parsed.transferId)
        assertEquals(4, parsed.seq)
        assertTrue(parsed.flags and BinaryFrames.FLAG_LAST != 0)
        assertArrayEquals(payload, parsed.payload)
    }
}
