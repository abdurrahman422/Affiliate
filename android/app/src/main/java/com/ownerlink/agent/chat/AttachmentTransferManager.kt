package com.ownerlink.agent.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AttachmentTransferManager(
    context: Context,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val sendPeer: (String, String) -> Boolean,
    private val ensurePeer: (String) -> Unit,
    private val changed: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val incoming = ConcurrentHashMap<String, Incoming>()

    init {
        scope.launch(Dispatchers.IO) {
            store.transfers().filter { it.direction == "OUT" }.forEach { pending ->
                store.message(pending.messageId)?.let { restore(it, pending.transferId) }
            }
        }
    }

    fun enqueue(contactId: String, uri: Uri, image: Boolean): ChatMessage {
        val resolver = appContext.contentResolver
        var name = "attachment"; var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
        }
        require(size <= AttachmentSafety.MAX_BYTES) { "Choose a file smaller than 25 MB" }
        val safe = AttachmentSafety.safeName(name)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytesLimited(AttachmentSafety.MAX_BYTES) } ?: error("Unable to read this file")
        size = bytes.size.toLong()
        val transferId = UUID.randomUUID().toString(); val messageId = UUID.randomUUID().toString()
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val hash = AttachmentSafety.sha256(bytes)
        val message = ChatMessage(messageId, contactId, safe, true, System.currentTimeMillis(), MessageState.PENDING, if (image) "image" else "file", uri.toString(), safe, mime, bytes.size.toLong())
        store.putMessage(message)
        store.saveTransfer(PendingTransferEntity(transferId, messageId, contactId, uri.toString(), null, safe, mime, bytes.size.toLong(), hash, "OUT", "PENDING"))
        outgoing[transferId] = Outgoing(message, bytes, hash, mime, safe)
        offer(transferId, outgoing.getValue(transferId)); ensurePeer(contactId); changed()
        return message
    }

    fun onPeerReady(contactId: String) {
        outgoing.filterValues { it.message.contactId == contactId }.forEach { (id, value) -> offer(id, value) }
    }

    fun retry(message: ChatMessage) {
        scope.launch(Dispatchers.IO) {
            store.deleteTransferForMessage(message.id)
            restore(message.copy(state = MessageState.PENDING, error = null), UUID.randomUUID().toString())
        }
    }

    fun cancel(messageId: String) {
        outgoing.entries.firstOrNull { it.value.message.id == messageId }?.let { outgoing.remove(it.key) }
        store.deleteTransferForMessage(messageId)
        store.updateState(messageId, MessageState.FAILED, "Canceled")
        changed()
    }

    private fun restore(message: ChatMessage, transferId: String) {
        val uri = message.attachmentUri?.let(Uri::parse) ?: return
        val bytes = runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(AttachmentSafety.MAX_BYTES) } }.getOrNull() ?: run {
            store.updateState(message.id, MessageState.FAILED, "The selected file is no longer available")
            changed(); return
        }
        val safe = AttachmentSafety.safeName(message.attachmentName ?: message.body)
        val mime = message.attachmentMime ?: appContext.contentResolver.getType(uri) ?: "application/octet-stream"
        val hash = AttachmentSafety.sha256(bytes)
        val restored = message.copy(state = MessageState.PENDING, attachmentSize = bytes.size.toLong(), error = null)
        store.updateState(message.id, MessageState.PENDING)
        store.saveTransfer(PendingTransferEntity(transferId, message.id, message.contactId, uri.toString(), null, safe, mime, bytes.size.toLong(), hash, "OUT", "PENDING"))
        outgoing[transferId] = Outgoing(restored, bytes, hash, mime, safe)
        offer(transferId, outgoing.getValue(transferId)); ensurePeer(message.contactId); changed()
    }

    fun handle(contactId: String, root: JsonObject) {
        when (root["type"]?.jsonPrimitive?.content) {
            "FILE_OFFER" -> acceptOffer(contactId, root)
            "FILE_ACCEPT" -> root.string("transfer_id")?.let { beginSend(contactId, it) }
            "FILE_REJECT" -> root.string("transfer_id")?.let { failOutgoing(it, "Recipient rejected the file") }
            "FILE_CHUNK" -> receiveChunk(root)
            "FILE_COMPLETE" -> completeIncoming(contactId, root)
            "FILE_RECEIPT" -> root.string("message_id")?.let { store.updateState(it, MessageState.DELIVERED); changed() }
        }
    }

    private fun offer(id: String, value: Outgoing) {
        send(contactId = value.message.contactId, type = "FILE_OFFER") {
            put("transfer_id", id); put("message_id", value.message.id); put("name", value.name); put("mime", value.mime)
            put("size", value.bytes.size); put("sha256", value.hash); put("kind", value.message.kind); put("sent_at", value.message.createdAt)
        }
    }

    private fun acceptOffer(contactId: String, root: JsonObject) {
        val id = root.string("transfer_id") ?: return; val messageId = root.string("message_id") ?: return
        val safe = AttachmentSafety.safeName(root.string("name") ?: "attachment")
        val size = root["size"]?.jsonPrimitive?.longOrNull ?: return
        val hash = root.string("sha256") ?: return
        if (size !in 0..AttachmentSafety.MAX_BYTES || !hash.matches(Regex("[0-9a-f]{64}"))) { send(contactId, "FILE_REJECT") { put("transfer_id", id) }; return }
        val dir = File(appContext.filesDir, "attachments").apply { mkdirs() }
        val temp = File(dir, "$id.part")
        val output = FileOutputStream(temp, false)
        incoming[id] = Incoming(messageId, contactId, safe, root.string("mime") ?: "application/octet-stream", root.string("kind") ?: "file", size, hash, temp, output)
        store.saveTransfer(PendingTransferEntity(id, messageId, contactId, null, temp.absolutePath, safe, root.string("mime") ?: "application/octet-stream", size, hash, "IN", "RECEIVING"))
        send(contactId, "FILE_ACCEPT") { put("transfer_id", id) }
    }

    private fun beginSend(contactId: String, id: String) {
        val value = outgoing[id] ?: return
        scope.launch(Dispatchers.IO) {
            store.updateState(value.message.id, MessageState.SENDING); changed()
            val chunks = AttachmentSafety.chunks(value.bytes)
            chunks.forEachIndexed { index, bytes ->
                if (!outgoing.containsKey(id)) return@launch
                val payload = buildJsonObject { put("v", 1); put("type", "FILE_CHUNK"); put("transfer_id", id); put("index", index); put("data", Base64.getEncoder().encodeToString(bytes)) }.toString()
                while (outgoing.containsKey(id) && !sendPeer(contactId, payload)) delay(25)
                if (!outgoing.containsKey(id)) return@launch
                store.updateProgress(value.message.id, ((index + 1) * 100 / chunks.size.coerceAtLeast(1)), MessageState.SENDING); changed()
            }
            while (outgoing.containsKey(id) && !send(contactId, "FILE_COMPLETE") { put("transfer_id", id); put("sha256", value.hash) }) delay(25)
            if (!outgoing.containsKey(id)) return@launch
            store.updateProgress(value.message.id, 100, MessageState.SENT); store.deleteTransfer(id); outgoing.remove(id); changed()
        }
    }

    private fun receiveChunk(root: JsonObject) {
        val id = root.string("transfer_id") ?: return; val value = incoming[id] ?: return
        val index = root["index"]?.jsonPrimitive?.intOrNull ?: return
        if (index != value.nextIndex) { rejectIncoming(id, value, "Chunk order mismatch"); return }
        val bytes = runCatching { Base64.getDecoder().decode(root.string("data")) }.getOrNull() ?: return
        value.received += bytes.size
        if (value.received > value.size) { rejectIncoming(id, value, "Attachment exceeds declared size"); return }
        value.output.write(bytes); value.digest.update(bytes); value.nextIndex++
    }

    private fun completeIncoming(contactId: String, root: JsonObject) {
        val id = root.string("transfer_id") ?: return; val value = incoming.remove(id) ?: return
        value.output.close()
        val actual = value.digest.digest().joinToString("") { "%02x".format(it) }
        if (value.received != value.size || actual != value.hash || actual != root.string("sha256")) { value.temp.delete(); store.deleteTransfer(id); return }
        val final = File(value.temp.parentFile, "${UUID.randomUUID()}-${value.name}")
        if (!value.temp.renameTo(final)) { value.temp.delete(); store.deleteTransfer(id); return }
        store.putMessage(ChatMessage(value.messageId, contactId, value.name, false, System.currentTimeMillis(), MessageState.DELIVERED, value.kind, Uri.fromFile(final).toString(), value.name, value.mime, value.size, 100))
        send(contactId, "FILE_RECEIPT") { put("message_id", value.messageId); put("state", "DELIVERED") }
        store.deleteTransfer(id); changed()
    }

    private fun failOutgoing(id: String, reason: String) { outgoing.remove(id)?.let { store.updateState(it.message.id, MessageState.FAILED, reason); store.deleteTransfer(id); changed() } }
    private fun rejectIncoming(id: String, value: Incoming, reason: String) { value.output.close(); value.temp.delete(); incoming.remove(id); store.deleteTransfer(id); send(value.contactId, "FILE_REJECT") { put("transfer_id", id); put("reason", reason.take(80)) } }
    private fun send(contactId: String, type: String, content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Boolean = sendPeer(contactId, buildJsonObject { put("v", 1); put("type", type); content() }.toString())

    private data class Outgoing(val message: ChatMessage, val bytes: ByteArray, val hash: String, val mime: String, val name: String)
    private data class Incoming(val messageId: String, val contactId: String, val name: String, val mime: String, val kind: String, val size: Long, val hash: String, val temp: File, val output: FileOutputStream, val digest: MessageDigest = MessageDigest.getInstance("SHA-256"), var received: Long = 0, var nextIndex: Int = 0)
}

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content
private fun java.io.InputStream.readBytesLimited(max: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(64 * 1024); var total = 0L
    while (true) { val count = read(buffer); if (count < 0) break; total += count; require(total <= max) { "Attachment is too large" }; out.write(buffer, 0, count) }
    return out.toByteArray()
}
