package com.ownerlink.agent.util

object Urls {
    fun toDeviceWebSocket(serverUrl: String): String {
        val base = serverUrl.trim().trimEnd('/')
        return when {
            base.startsWith("https://", ignoreCase = true) ->
                "wss://" + base.substring("https://".length) + "/ws/device"
            base.startsWith("http://", ignoreCase = true) ->
                "ws://" + base.substring("http://".length) + "/ws/device"
            else -> error("Invalid server URL")
        }
    }

}
