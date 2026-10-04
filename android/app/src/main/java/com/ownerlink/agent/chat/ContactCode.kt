package com.ownerlink.agent.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ContactCard(val contactId: String, val publicKey: String, val name: String)

object ContactCode {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(card: ContactCard): String = buildJsonObject {
        put("v", 1); put("type", "privatchat_contact"); put("contact_id", card.contactId)
        put("public_key", card.publicKey); put("name", card.name)
    }.toString()

    fun parse(raw: String): ContactCard {
        val root = json.parseToJsonElement(raw.trim()).jsonObject
        require(root["v"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported contact code" }
        require(root["type"]?.jsonPrimitive?.content == "privatchat_contact") { "This is not a PrivatChat contact code" }
        val id = root["contact_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        val key = root["public_key"]?.jsonPrimitive?.content?.trim().orEmpty()
        val name = root["name"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(id.length in 8..128 && key.length in 32..4096 && name.length in 1..80) { "Invalid contact code" }
        return ContactCard(id, key, name)
    }
}
