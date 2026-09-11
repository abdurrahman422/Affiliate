package com.ownerlink.agent

import com.ownerlink.agent.files.FileIds
import com.ownerlink.agent.files.FileRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileIdsTest {
    @Test
    fun galleryRoundTrip() {
        val id = FileIds.encodeGallery(99L)
        val ref = FileIds.decode(id) as FileRef.Gallery
        assertEquals(99L, ref.mediaId)
    }

    @Test
    fun pathMustStayInsideRoot() {
        val root = File(System.getProperty("java.io.tmpdir")).canonicalFile
        val child = File(root, "ownerlink-test-file.txt")
        FileIds.assertAllowedPath(child.path, listOf(root))
        var failed = false
        try {
            FileIds.assertAllowedPath(File(root.parentFile, "outside").path, listOf(root))
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }
}
