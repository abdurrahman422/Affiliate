package com.ownerlink.agent.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object Protocol {
    const val UNPAIRED_HELLO = "UNPAIRED_HELLO"
    const val DEVICE_AUTH = "DEVICE_AUTH"
    const val HEARTBEAT = "HEARTBEAT"
    const val RESPONSE = "RESPONSE"
    const val ERROR = "ERROR"
    const val PAIR_SUCCESS = "PAIR_SUCCESS"
    const val AUTH_OK = "AUTH_OK"
    const val REVOKED = "REVOKED"
    const val UNPAIRED_ACK = "UNPAIRED_ACK"
    const val LIST_GALLERY = "LIST_GALLERY"
    const val LIST_FILES = "LIST_FILES"
    const val DOWNLOAD_FILE = "DOWNLOAD_FILE"
    const val GET_THUMBNAIL = "GET_THUMBNAIL"
    const val GET_SCREEN_FRAME = "GET_SCREEN_FRAME"
    const val START_SCREEN_STREAM = "START_SCREEN_STREAM"
    const val STOP_SCREEN_STREAM = "STOP_SCREEN_STREAM"
    const val REMOTE_GESTURE = "REMOTE_GESTURE"
    const val GET_STATUS = "GET_STATUS"
    const val FILE_BEGIN = "FILE_BEGIN"
    const val FILE_END = "FILE_END"
    const val SCREEN_STATUS = "SCREEN_STATUS"
    const val CHAT_IDENTITY_HELLO = "CHAT_IDENTITY_HELLO"
    const val CHAT_IDENTITY_ACK = "CHAT_IDENTITY_ACK"
    const val CHAT_CHALLENGE_REQUEST = "CHAT_CHALLENGE_REQUEST"
    const val CHAT_CHALLENGE = "CHAT_CHALLENGE"
    const val PEER_MESSAGE = "PEER_MESSAGE"
    const val PEER_DELIVER = "PEER_DELIVER"
    const val PEER_DELIVERED = "PEER_DELIVERED"
    const val PEER_READ = "PEER_READ"
    const val PEER_PRESENCE = "PEER_PRESENCE"
    const val PRESENCE_QUERY = "PRESENCE_QUERY"
    const val PRESENCE_UPDATE = "PRESENCE_UPDATE"
    const val PEER_CONNECT_REQUEST = "PEER_CONNECT_REQUEST"
    const val PEER_CONNECT_ACCEPT = "PEER_CONNECT_ACCEPT"
    const val PEER_CONNECT_REJECT = "PEER_CONNECT_REJECT"
    const val PEER_SIGNAL = "PEER_SIGNAL"
    const val CALL_INVITE = "CALL_INVITE"
    const val CALL_RINGING = "CALL_RINGING"
    const val CALL_ACCEPT = "CALL_ACCEPT"
    const val CALL_OFFER = "CALL_OFFER"
    const val CALL_ANSWER = "CALL_ANSWER"
    const val CALL_ICE = "CALL_ICE"
    const val CALL_DECLINE = "CALL_DECLINE"
    const val CALL_END = "CALL_END"
    const val DASHBOARD_DISCONNECT = "DASHBOARD_DISCONNECT"
    const val RTC_CONFIG_REQUEST = "RTC_CONFIG_REQUEST"
    const val RTC_CONFIG = "RTC_CONFIG"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun envelope(type: String, deviceId: String, payload: JsonObject = buildJsonObject {}, requestId: String? = null): String {
        val obj = buildJsonObject {
            put("v", 1)
            put("type", type)
            if (requestId == null) put("request_id", JsonNull) else put("request_id", requestId)
            put("device_id", deviceId)
            put("payload", payload)
        }
        return obj.toString()
    }
}

fun JsonObject.str(key: String): String? {
    val el = this[key] ?: return null
    if (el is JsonNull) return null
    return el.jsonPrimitive.contentOrNull
}

fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
fun JsonObject.optDouble(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
fun JsonObject.bool(key: String): Boolean? {
    val p = this[key]?.jsonPrimitive ?: return null
    return p.contentOrNull?.toBooleanStrictOrNull() ?: p.contentOrNull?.let { it == "true" }
}

fun JsonElement.asObject(): JsonObject = jsonObject
fun jsonStr(value: String) = JsonPrimitive(value)
