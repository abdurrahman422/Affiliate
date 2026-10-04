package com.ownerlink.agent.rtc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RtcIceServer(
    val urls: List<String>,
    val username: String = "",
    val credential: String = "",
)

object IceServerConfig {
    fun parse(payload: JsonObject): List<RtcIceServer> = runCatching {
        payload["ice_servers"]?.jsonArray.orEmpty().mapNotNull { element ->
            val value = element.jsonObject
            val urls = value["urls"]?.jsonArray.orEmpty().mapNotNull { item ->
                item.jsonPrimitive.contentOrNull?.trim()?.takeIf {
                    it.startsWith("stun:") || it.startsWith("stuns:") || it.startsWith("turn:") || it.startsWith("turns:")
                }
            }.take(8)
            if (urls.isEmpty()) null else RtcIceServer(
                urls,
                value["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                value["credential"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.take(8)
    }.getOrDefault(emptyList())
}
