package com.ownerlink.agent.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ownerlink.agent.security.SecureStore
import com.ownerlink.agent.chat.ChatIdentity
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

enum class ConnectionState { Idle, Connecting, WaitingForPair, Online, Offline, Revoked }

class DeviceSocket(
    context: Context,
    private val store: SecureStore,
    private val scope: CoroutineScope,
    private val chatIdentity: ChatIdentity,
    private val peerHandler: (String, JsonObject) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val stopRequested = AtomicBoolean(false)
    private val lifecycle = SocketLifecycle()
    private var socket: WebSocket? = null
    private val _state = MutableStateFlow(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    @Synchronized fun send(text: String): Boolean =
        lifecycle.status == SocketStatus.CONNECTED && socket?.send(text) == true

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).retryOnConnectionFailure(true).build()
    private val commands = CommandHandler(appContext, store, scope,
        sendJson = { socket?.send(it) }, sendBytes = { socket?.send(ByteString.of(*it)) })

    private val heartbeat = object : Runnable {
        override fun run() {
            if (lifecycle.status == SocketStatus.CONNECTED &&
                (_state.value == ConnectionState.Online || _state.value == ConnectionState.WaitingForPair)) {
                val (battery, charging) = Battery.snapshot(appContext)
                socket?.send(Protocol.envelope(Protocol.HEARTBEAT, store.deviceId, buildJsonObject {
                    if (battery != null) put("battery", battery)
                    if (charging != null) put("charging", charging)
                }))
                handler.postDelayed(this, 15_000)
            }
        }
    }
    private val reconnectRunnable = Runnable { synchronized(this) { open(lifecycle.reconnectFired()) } }

    /** Idempotent: CONNECTING and CONNECTED states never create/replace a socket. */
    @Synchronized fun connect() {
        stopRequested.set(false)
        if (store.serverUrl.isBlank()) { _message.value = "Set a server URL first"; return }
        open(lifecycle.connectRequested())
    }

    @Synchronized fun disconnect(user: Boolean) {
        if (user) stopRequested.set(true)
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(heartbeat)
        commands.stopStream()
        val old = socket
        socket = null
        lifecycle.disconnect() // makes every old OkHttp callback stale
        old?.close(1000, "disconnect")
        _state.value = if (user) ConnectionState.Idle else ConnectionState.Offline
    }

    /** Called by the foreground-service network monitor. Healthy sockets are untouched. */
    @Synchronized fun retryNow() {
        if (stopRequested.get()) return
        handler.removeCallbacks(reconnectRunnable)
        open(lifecycle.retryNow())
    }

    @Synchronized private fun open(attempt: Long?) {
        if (attempt == null || stopRequested.get()) return
        _state.value = ConnectionState.Connecting
        _message.value = "Connecting"
        Log.i(TAG, "socket connecting")
        val created = client.newWebSocket(Request.Builder().url(Urls.toDeviceWebSocket(store.serverUrl)).build(), listener(attempt))
        // OkHttp can invoke onOpen before newWebSocket returns. Keep the same socket either way.
        if (lifecycle.isCurrent(attempt)) { if (socket == null) socket = created } else created.cancel()
    }

    private fun listener(attempt: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(this@DeviceSocket) {
            if (!lifecycle.opened(attempt)) { webSocket.close(1000, "stale socket"); return@synchronized }
            socket = webSocket
            handler.removeCallbacks(reconnectRunnable)
            Log.i(TAG, "socket open")
            if (requiredHandshake(store.paired, store.sessionToken) == DeviceHandshake.DEVICE_AUTH) {
                webSocket.send(Protocol.envelope(Protocol.DEVICE_AUTH, store.deviceId, buildJsonObject { put("session_token", store.sessionToken!!) }))
                Log.i(TAG, "DEVICE_AUTH sent")
            } else {
                if (store.pairingToken.isBlank()) store.regeneratePairingToken()
                webSocket.send(Protocol.envelope(Protocol.UNPAIRED_HELLO, store.deviceId, buildJsonObject {
                    put("pairing_token", store.pairingToken); put("name", store.friendlyName); put("app_version", "1.0.0")
                }))
                Log.i(TAG, "UNPAIRED_HELLO sent")
            }
            webSocket.send(Protocol.envelope(Protocol.CHAT_IDENTITY_HELLO, store.deviceId, buildJsonObject {
                put("contact_id", chatIdentity.contactId); put("public_key", chatIdentity.publicKey)
                put("name", store.friendlyName.ifBlank { "PrivatChat user" })
            }))
            Log.i(TAG, "CHAT_IDENTITY_HELLO sent")
            handler.removeCallbacks(heartbeat)
            handler.post(heartbeat)
        }
        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@DeviceSocket) {
            if (lifecycle.isCurrent(attempt)) handleText(text)
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@DeviceSocket) {
            if (lifecycle.isCurrent(attempt)) webSocket.close(code, reason)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "socket closed code=$code")
            terminal(attempt, code, null)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.javaClass.simpleName}")
            terminal(attempt, null, t)
        }
    }

    @Synchronized private fun terminal(attempt: Long, closeCode: Int?, error: Throwable?) {
        if (closeCode == 4001 || stopRequested.get() || _state.value == ConnectionState.Revoked) return
        val delay = lifecycle.terminated(attempt) ?: return // stale/duplicate terminal callback
        socket = null
        commands.stopStream(); handler.removeCallbacks(heartbeat)
        _state.value = ConnectionState.Offline
        _message.value = if (error == null) "Disconnected" else "Cannot reach server"
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
        Log.i(TAG, "reconnect scheduled in ${delay}ms")
    }

    private fun handleText(text: String) {
        val root = Protocol.json.parseToJsonElement(text).jsonObject
        val type = root.str("type") ?: return
        val requestId = root.str("request_id")
        val payload = root["payload"] as? JsonObject ?: buildJsonObject {}
        when (type) {
            Protocol.CHAT_IDENTITY_ACK -> peerHandler(type, payload)
            Protocol.UNPAIRED_ACK -> { _state.value = ConnectionState.WaitingForPair; _message.value = "Waiting for owner to scan QR" }
            Protocol.PAIR_SUCCESS -> { val token = payload.str("session_token") ?: return; store.markPaired(token); _state.value = ConnectionState.Online; _message.value = "Paired and connected" }
            Protocol.AUTH_OK -> { store.paired = true; _state.value = ConnectionState.Online; _message.value = "Connected" }
            Protocol.CHAT_IDENTITY_ACK -> scope.coroutineContext.let { /* registration acknowledged */ }
            Protocol.PEER_MESSAGE, Protocol.PEER_DELIVER, Protocol.PEER_DELIVERED, Protocol.PEER_READ,
            Protocol.PEER_PRESENCE, Protocol.CALL_OFFER, Protocol.CALL_ANSWER, Protocol.CALL_ICE,
            Protocol.CALL_DECLINE, Protocol.CALL_END -> peerHandler(type, payload)
            Protocol.REVOKED -> {
                store.markRevoked(); _state.value = ConnectionState.Revoked; _message.value = "Device removed. A new QR is ready."
                stopRequested.set(false); disconnect(false); handler.postDelayed({ retryNow() }, 500)
            }
            Protocol.ERROR -> if (payload.str("code") in setOf("AUTH_FAILED", "NOT_PAIRED")) {
                store.markRevoked(); _state.value = ConnectionState.WaitingForPair
            }
            Protocol.LIST_GALLERY, Protocol.LIST_FILES, Protocol.DOWNLOAD_FILE, Protocol.GET_THUMBNAIL,
            Protocol.GET_SCREEN_FRAME, Protocol.START_SCREEN_STREAM, Protocol.STOP_SCREEN_STREAM,
            Protocol.REMOTE_GESTURE, Protocol.GET_STATUS -> commands.handle(type, requestId, payload)
        }
    }
    companion object { private const val TAG = "OwnerLinkSocket" }
}
