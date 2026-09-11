package com.ownerlink.agent.chat

import com.ownerlink.agent.network.Protocol
import com.ownerlink.agent.network.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class ChatController(private val store: ChatStore, private val identity: ChatIdentity, private val sendRaw: (String) -> Boolean) {
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation

    fun contacts() = store.contacts()
    fun messages(contactId: String) = store.messages(contactId)
    fun addContact(card: ContactCard) { store.saveContact(card); changed() }

    fun sendText(deviceId: String, contactId: String, body: String) {
        val clean = body.trim()
        if (clean.isEmpty()) return
        val message = ChatMessage(UUID.randomUUID().toString(), contactId, clean, true, System.currentTimeMillis(), MessageState.PENDING)
        store.putMessage(message); changed()
        send(message, deviceId)
    }

    fun retryPending(deviceId: String) = store.pending().forEach { send(it, deviceId) }

    private fun send(message: ChatMessage, deviceId: String) {
        val peer = store.contact(message.contactId) ?: return
        val sealed = CryptoBox.seal(message.body, identity.privateKey, peer.publicKey)
        val accepted = sendRaw(Protocol.envelope(Protocol.PEER_MESSAGE, deviceId, buildJsonObject {
            put("to", message.contactId); put("message_id", message.id); put("kind", "text")
            put("ciphertext", sealed.ciphertext); put("nonce", sealed.nonce); put("created_at", message.createdAt)
        }, message.id))
        if (accepted) { store.updateState(message.id, MessageState.SENT); changed() }
    }

    fun handle(type: String, payload: JsonObject) {
        when (type) {
            Protocol.PEER_MESSAGE -> {
                val id = payload.str("message_id") ?: return
                val from = payload.str("from") ?: return
                val ciphertext = payload.str("ciphertext") ?: return
                val nonce = payload.str("nonce") ?: return
                val peer = store.contact(from) ?: return
                val body = runCatching { CryptoBox.open(SealedText(ciphertext, nonce), identity.privateKey, peer.publicKey) }.getOrNull() ?: return
                if (store.putMessage(ChatMessage(id, from, body, false, System.currentTimeMillis(), MessageState.DELIVERED))) changed()
            }
            Protocol.PEER_DELIVER -> {
                val id = payload.str("message_id") ?: return
                if (payload["delivered"]?.toString() == "true") store.updateState(id, MessageState.DELIVERED)
                changed()
            }
            Protocol.PEER_DELIVERED -> payload.str("message_id")?.let { store.updateState(it, MessageState.DELIVERED); changed() }
            Protocol.PEER_READ -> payload.str("message_id")?.let { store.updateState(it, MessageState.READ); changed() }
        }
    }

    private fun changed() { _generation.value += 1 }
}
