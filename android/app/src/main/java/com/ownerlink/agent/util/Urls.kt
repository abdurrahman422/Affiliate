package com.ownerlink.agent.util

import android.net.Uri
import com.ownerlink.agent.BuildConfig

object Urls {
    fun normalizeAndValidate(raw: String, allowHttp: Boolean = BuildConfig.ALLOW_INSECURE_HTTP): String {
        val trimmed = raw.trim().trimEnd('/')
        val uri = Uri.parse(trimmed)
        val scheme = uri.scheme?.lowercase()
        require(!uri.host.isNullOrBlank()) { "Enter a server URL, for example https://ownerlink.example.com" }
        when (scheme) {
            "https" -> Unit
            "http" -> require(allowHttp) { "Production builds require HTTPS. Use a debug APK for LAN HTTP." }
            else -> error("URL must start with https://")
        }
        return trimmed
    }

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

    fun hostLabel(serverUrl: String): String {
        return Uri.parse(serverUrl).host ?: serverUrl
    }

    fun reachable(serverUrl: String, timeoutMs: Int = 5000): Boolean {
        val url = java.net.URL(serverUrl.trim().trimEnd('/') + "/health")
        val conn = url.openConnection() as java.net.HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.connect()
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
