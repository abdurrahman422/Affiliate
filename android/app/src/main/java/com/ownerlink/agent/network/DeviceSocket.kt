package com.ownerlink.agent.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ownerlink.agent.security.SecureStore
import com.ownerlink.agent.util.Battery
import com.ownerlink.agent.util.Urls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionState {
    Idle, Connecting, WaitingForPair, Online, Offline, Revoked
}

class DeviceSocket(
    context: Context,
    private val store: SecureStore,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val stopRequested = AtomicBoolean(false)
    private var backoffMs = 1000L
    private var socket: WebSocket? = null
    private val _state = MutableStateFlow(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val commands = CommandHandler(
        appContext,
        store,
        scope,
        sendJson = { text -> socket?.send(text) },
        sendBytes = { bytes -> socket?.send(ByteString.of(*bytes)) },
    )

    private val heartbeat = object : Runnable {
        override fun run() {
            if (_state.value == ConnectionState.Online || _state.value == ConnectionState.WaitingForPair) {
                val (battery, charging) = Battery.snapshot(appContext)
                socket?.send(
                    Protocol.envelope(
                        Protocol.HEARTBEAT,
                        store.deviceId,
                        buildJsonObject {
                            if (battery != null) put("battery", battery)
                            if (charging != null) put("charging", charging)
                        },
                    ),
                )
                handler.postDelayed(this, 15_000)
            }
        }
    }

    @Synchronized
    fun connect() {
        stopRequested.set(false)
        if (store.serverUrl.isBlank()) {
            _message.value = "Set a server URL first"
            return
        }
        open()
    }

    @Synchronized
    fun disconnect(user: Boolean) {
        if (user) stopRequested.set(true)
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(heartbeat)
        commands.stopStream()
        socket?.close(1000, "disconnect")
        socket = null
        _state.value = if (user) ConnectionState.Idle else ConnectionState.Offline
    }

    fun retryNow() {
        if (stopRequested.get()) return
        handler.removeCallbacks(reconnectRunnable)
        backoffMs = 1000L
        handler.post { open() }
    }

    private val reconnectRunnable = Runnable {
        if (!stopRequested.get()) open()
    }

    private fun open() {
        if (stopRequested.get()) return
        val wsUrl = Urls.toDeviceWebSocket(store.serverUrl)
        _state.value = ConnectionState.Connecting
        _message.value = "Connecting to $wsUrl"
        Log.i(TAG, "opening $wsUrl")
        val req = Request.Builder().url(wsUrl).build()
        socket?.cancel()
        socket = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "socket open")
            backoffMs = 1000L
            val session = store.sessionToken
            if (store.paired && !session.isNullOrBlank()) {
                webSocket.send(
                    Protocol.envelope(
                        Protocol.DEVICE_AUTH,
                        store.deviceId,
                        buildJsonObject { put("session_token", session) },
                    ),
                )
            } else {
                if (store.pairingToken.isBlank()) store.regeneratePairingToken()
                webSocket.send(
                    Protocol.envelope(
                        Protocol.UNPAIRED_HELLO,
                        store.deviceId,
                        buildJsonObject {
                            put("pairing_token", store.pairingToken)
                            put("name", store.friendlyName)
                            put("app_version", "1.0.0")
                        },
                    ),
                )
            }
            handler.removeCallbacks(heartbeat)
            handler.post(heartbeat)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            commands.stopStream()
            handler.removeCallbacks(heartbeat)
            if (code == 4001 || _state.value == ConnectionState.Revoked) return
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure", t)
            commands.stopStream()
            handler.removeCallbacks(heartbeat)
            _message.value = "Cannot reach server: ${t.message ?: "network error"}"
            scheduleReconnect()
        }
    }

    private fun handleText(text: String) {
        val root = Protocol.json.parseToJsonElement(text).jsonObject
        val type = root.str("type") ?: return
        val requestId = root.str("request_id")
        val payload = root["payload"] as? JsonObject ?: buildJsonObject {}
        when (type) {
            Protocol.UNPAIRED_ACK -> {
                _state.value = ConnectionState.WaitingForPair
                _message.value = "Waiting for owner to scan QR"
            }
            Protocol.PAIR_SUCCESS -> {
                val session = payload.str("session_token") ?: return
                store.markPaired(session)
                _state.value = ConnectionState.Online
                _message.value = "Paired and connected"
            }
            Protocol.AUTH_OK -> {
                store.paired = true
                _state.value = ConnectionState.Online
                _message.value = "Connected"
            }
            Protocol.REVOKED -> {
                store.markRevoked()
                _state.value = ConnectionState.Revoked
                _message.value = "Device removed. A new QR is ready."
                stopRequested.set(false)
                handler.postDelayed({ open() }, 500)
            }
            Protocol.ERROR -> {
                val code = payload.str("code")
                val msg = payload.str("message") ?: "Error"
                if (code == "ALREADY_PAIRED" || code == "AUTH_FAILED" || code == "NOT_PAIRED") {
                    if (!store.paired) _message.value = msg
                    if (code == "AUTH_FAILED" || code == "NOT_PAIRED") {
                        store.markRevoked()
                        _state.value = ConnectionState.WaitingForPair
                    }
                }
            }
            Protocol.LIST_GALLERY,
            Protocol.LIST_FILES,
            Protocol.DOWNLOAD_FILE,
            Protocol.GET_THUMBNAIL,
            Protocol.GET_SCREEN_FRAME,
            Protocol.START_SCREEN_STREAM,
            Protocol.STOP_SCREEN_STREAM,
            Protocol.REMOTE_GESTURE,
            Protocol.GET_STATUS,
            -> commands.handle(type, requestId, payload)
        }
    }

    private fun scheduleReconnect() {
        if (stopRequested.get()) {
            _state.value = ConnectionState.Idle
            return
        }
        _state.value = ConnectionState.Offline
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        _message.value = "Reconnecting in ${backoffMs / 1000}s…"
    }

    companion object {
        private const val TAG = "OwnerLinkSocket"
    }
}
