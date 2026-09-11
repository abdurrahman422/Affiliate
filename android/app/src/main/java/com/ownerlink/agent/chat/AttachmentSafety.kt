package com.ownerlink.agent.chat

import java.security.MessageDigest

object AttachmentSafety {
    const val MAX_BYTES = 25L * 1024L * 1024L
    const val CHUNK_BYTES = 64 * 1024
    fun safeName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "attachment" }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun chunks(bytes: ByteArray): List<ByteArray> {
        require(bytes.size <= MAX_BYTES) { "Attachment is larger than 25 MB" }
        return bytes.asList().chunked(CHUNK_BYTES).map { it.toByteArray() }
    }
}
