package com.ownerlink.agent

import com.ownerlink.agent.chat.AttachmentSafety
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentSafetyTest {
    @Test fun sanitizesTraversalAndUnsafeCharacters() {
        assertEquals("evil_.txt", AttachmentSafety.safeName("../../evil?.txt"))
    }

    @Test fun chunkJoinAndHashAreStable() {
        val bytes = ByteArray(AttachmentSafety.CHUNK_BYTES + 17) { (it % 251).toByte() }
        val joined = AttachmentSafety.chunks(bytes).flatMap { it.asList() }.toByteArray()
        assertArrayEquals(bytes, joined)
        assertEquals(64, AttachmentSafety.sha256(bytes).length)
        assertEquals(true, AttachmentSafety.verify(bytes, bytes.size.toLong(), AttachmentSafety.sha256(bytes)))
        assertEquals(false, AttachmentSafety.verify(bytes, bytes.size.toLong(), "0".repeat(64)))
    }
}
