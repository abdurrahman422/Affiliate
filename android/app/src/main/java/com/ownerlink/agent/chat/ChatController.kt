package com.ownerlink.agent.chat

import android.content.Context
import android.net.Uri
import com.ownerlink.agent.network.Protocol
import com.ownerlink.agent.network.bool
import com.ownerlink.agent.network.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

class ChatController(
    context: Context,
    private val store: ChatStore,
    private val identity: ChatIdentity,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val sendServer: (String) -> Boolean,
    private val sendPeer: (String, String) -> Boolean,
    private val ensurePeer: (String) -> Unit,
) {
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation
    private val _presence = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val presence: StateFlow<Map<String, Boolean>> = _presence
    private val transfers = AttachmentTransferManager(context, store, scope, sendPeer, ensurePeer, ::changed)

    fun contacts() = store.contacts()
    fun contact(id: String) = store.contact(id)
    fun messages(contactId: String) = store.messages(contactId)
    fun addContact(card: ContactCard) { store.saveContact(card); queryPresence(card.contactId); ensurePeer(card.contactId); changed() }
    fun removeContact(contactId: String) { store.removeContact(contactId); changed() }

    fun sendText(contactId: String, body: String) {
        val clean = body.trim().take(MAX_TEXT)
        if (clean.isEmpty()) return
        val message = ChatMessage(UUID.randomUUID().toString(), contactId, clean, true, System.currentTimeMillis(), MessageState.PENDING)
        store.putMessage(message); changed(); send(message)
    }

    fun sendAttachment(contactId: String, uri: Uri, image: Boolean): Result<ChatMessage> = runCatching { transfers.enqueue(contactId, uri, image) }

    fun retryPending() = store.pending().forEach(::send)
    fun retryMessage(messageId: String, contactId: String) = store.messages(contactId).firstOrNull { it.id == messageId }?.let {
        if (it.kind == "text") { store.updateState(it.id, MessageState.PENDING); send(it) } else transfers.retry(it)
    }
    fun cancelAttachment(messageId: String) = transfers.cancel(messageId)

    fun onPeerReady(contactId: String) {
        store.pending().filter { it.contactId == contactId && it.kind == "text" }.forEach(::send)
        transfers.onPeerReady(contactId)
        _presence.value = _presence.value + (contactId to true)
    }

    fun markRead(contactId: String) {
        store.markRead(contactId).forEach { id -> sendReceipt(contactId, id, MessageState.READ) }
        changed()
    }

    private fun send(message: ChatMessage) {
        if (message.kind != "text") { ensurePeer(message.contactId); return }
        val peer = store.contact(message.contactId) ?: return
        val sealed = CryptoBox.seal(message.body, identity.privateKey, peer.publicKey)
        val wire = buildJsonObject {
            put("v", 1); put("type", "CHAT_MESSAGE"); put("message_id", message.id); put("sender_id", identity.contactId)
            put("sent_at", message.createdAt); put("kind", "text"); put("ciphertext", sealed.ciphertext); put("nonce", sealed.nonce)
        }.toString()
        store.updateState(message.id, MessageState.SENDING); changed()
        if (sendPeer(message.contactId, wire)) store.updateState(message.id, MessageState.SENT)
        else { store.updateState(message.id, MessageState.PENDING); ensurePeer(message.contactId) }
        changed()
    }

    fun handleServer(type: String, payload: JsonObject) {
        when (type) {
            Protocol.CHAT_IDENTITY_ACK -> { retryPending(); contacts().forEach { queryPresence(it.contactId) } }
            Protocol.PRESENCE_UPDATE -> payload.str("contact_id")?.let { _presence.value = _presence.value + (it to (payload.bool("online") == true)); if (payload.bool("online") == true) ensurePeer(it) }
            // Backward-compatible small-message relay from previous builds.
            Protocol.PEER_MESSAGE -> receiveEncrypted(payload.str("from") ?: return, payload)
            Protocol.PEER_DELIVER -> payload.str("message_id")?.let { if (payload.bool("delivered") == true) store.updateState(it, MessageState.DELIVERED); changed() }
            Protocol.PEER_DELIVERED -> payload.str("message_id")?.let { store.updateState(it, MessageState.DELIVERED); changed() }
            Protocol.PEER_READ -> payload.str("message_id")?.let { store.updateState(it, MessageState.READ); changed() }
        }
    }

    fun handlePeer(contactId: String, text: String) {
        val root = runCatching { Protocol.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (root["type"]?.jsonPrimitive?.content) {
            "CHAT_MESSAGE" -> receiveEncrypted(contactId, root)
            "MESSAGE_RECEIPT" -> {
                val id = root.str("message_id") ?: return
                val state = runCatching { MessageState.valueOf(root.str("state") ?: "") }.getOrNull() ?: return
                if (state == MessageState.DELIVERED || state == MessageState.READ) store.updateState(id, state)
                changed()
            }
            else -> transfers.handle(contactId, root)
        }
    }

    private fun receiveEncrypted(from: String, payload: JsonObject) {
        val id = payload.str("message_id") ?: return
        val peer = store.contact(from) ?: return
        val body = runCatching { CryptoBox.open(SealedText(payload.str("ciphertext") ?: return, payload.str("nonce") ?: return), identity.privateKey, peer.publicKey) }.getOrNull() ?: return
        val created = payload["sent_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        if (store.putMessage(ChatMessage(id, from, body, false, created, MessageState.DELIVERED))) changed()
        sendReceipt(from, id, MessageState.DELIVERED)
    }

    private fun sendReceipt(contactId: String, id: String, state: MessageState) {
        sendPeer(contactId, buildJsonObject { put("v", 1); put("type", "MESSAGE_RECEIPT"); put("message_id", id); put("state", state.name); put("at", System.currentTimeMillis()) }.toString())
    }

    private fun queryPresence(contactId: String) { sendServer(Protocol.envelope(Protocol.PRESENCE_QUERY, deviceId, buildJsonObject { put("contact_id", contactId) })) }
    private fun changed() { _generation.value += 1 }

    companion object { private const val MAX_TEXT = 16_000 }
}
