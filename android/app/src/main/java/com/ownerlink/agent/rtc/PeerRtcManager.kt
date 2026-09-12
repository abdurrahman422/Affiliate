package com.ownerlink.agent.rtc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.ownerlink.agent.BuildConfig
import com.ownerlink.agent.MainActivity
import com.ownerlink.agent.R
import com.ownerlink.agent.network.Protocol
import com.ownerlink.agent.network.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class CallPhase { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED, FAILED }
data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val contactId: String? = null,
    val contactName: String = "",
    val callId: String? = null,
    val muted: Boolean = false,
    val error: String? = null,
)

class PeerRtcManager(
    context: Context,
    private val ownContactId: String,
    private val scope: CoroutineScope,
    private val sendSignal: (String) -> Boolean,
    private val onData: (String, String) -> Unit,
    private val onReady: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val _call = MutableStateFlow(CallUiState())
    val call: StateFlow<CallUiState> = _call
    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var frontCamera = true

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglDenseContext(), true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglDenseContext()))
            .createPeerConnectionFactory()
    }

    private fun eglDenseContext() = eglBase.eglBaseContext

    fun ensureDataSession(contactId: String) {
        if (sessions[contactId]?.channel?.state() == DataChannel.State.OPEN) return
        if (ownContactId > contactId) {
            route(Protocol.PEER_CONNECT_REQUEST, contactId, buildJsonObject {})
            return
        }
        val session = session(contactId)
        if (session.channel == null) attachChannel(session, session.pc.createDataChannel("privatchat-v1", DataChannel.Init()))
        createOffer(session)
    }

    fun sendData(contactId: String, text: String): Boolean {
        val channel = sessions[contactId]?.channel ?: return false
        if (channel.state() != DataChannel.State.OPEN || channel.bufferedAmount() > MAX_BUFFERED) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    fun handle(type: String, payload: JsonObject, contactName: (String) -> String) {
        val from = payload.str("from") ?: payload.str("contact_id")
        when (type) {
            Protocol.PEER_SIGNAL -> if (from != null) handlePeerSignal(from, payload)
            Protocol.PEER_CONNECT_REQUEST -> if (from != null) ensureDataSession(from)
            Protocol.CALL_INVITE -> if (from != null && _call.value.phase == CallPhase.IDLE) {
                val callId = payload.str("call_id") ?: return
                _call.value = CallUiState(CallPhase.INCOMING, from, contactName(from), callId)
                showIncomingNotification(contactName(from))
            }
            Protocol.CALL_RINGING -> if (_call.value.callId == payload.str("call_id")) _call.value = _call.value.copy(phase = CallPhase.OUTGOING)
            Protocol.CALL_ACCEPT -> if (_call.value.callId == payload.str("call_id") && from != null) {
                if (!hasCallPermissions()) { failCall("Camera and microphone permissions are required"); return }
                startMedia(from); _call.value = _call.value.copy(phase = CallPhase.CONNECTING); createOffer(session(from))
            }
            Protocol.CALL_DECLINE -> if (_call.value.callId == payload.str("call_id")) finish(CallPhase.ENDED, "Call declined")
            Protocol.CALL_END -> if (_call.value.callId == payload.str("call_id")) finish(CallPhase.ENDED, null)
        }
    }

    fun startCall(contactId: String, name: String) {
        if (_call.value.phase !in setOf(CallPhase.IDLE, CallPhase.ENDED, CallPhase.FAILED)) return
        val callId = UUID.randomUUID().toString()
        _call.value = CallUiState(CallPhase.OUTGOING, contactId, name, callId)
        route(Protocol.CALL_INVITE, contactId, buildJsonObject { put("call_id", callId) })
        scope.launch { delay(CALL_TIMEOUT_MS); if (_call.value.callId == callId && _call.value.phase != CallPhase.ACTIVE) { route(Protocol.CALL_END, contactId, buildJsonObject { put("call_id", callId) }); failCall("Call timed out") } }
    }

    fun ringIncoming() {
        val value = _call.value
        if (value.phase == CallPhase.INCOMING && value.contactId != null && value.callId != null) route(Protocol.CALL_RINGING, value.contactId, buildJsonObject { put("call_id", value.callId) })
    }

    fun acceptCall(): Boolean {
        val value = _call.value
        if (value.phase != CallPhase.INCOMING || value.contactId == null || value.callId == null) return false
        if (!hasCallPermissions()) return false
        startMedia(value.contactId)
        cancelIncomingNotification()
        _call.value = value.copy(phase = CallPhase.CONNECTING)
        route(Protocol.CALL_ACCEPT, value.contactId, buildJsonObject { put("call_id", value.callId) })
        return true
    }

    fun declineCall() {
        val value = _call.value
        if (value.contactId != null && value.callId != null) route(Protocol.CALL_DECLINE, value.contactId, buildJsonObject { put("call_id", value.callId) })
        cancelIncomingNotification(); finish(CallPhase.ENDED, null)
    }

    fun endCall() {
        val value = _call.value
        if (value.contactId != null && value.callId != null) route(Protocol.CALL_END, value.contactId, buildJsonObject { put("call_id", value.callId) })
        finish(CallPhase.ENDED, null)
    }

    fun toggleMute() {
        val muted = !_call.value.muted
        audioTrack?.setEnabled(!muted)
        _call.value = _call.value.copy(muted = muted)
    }

    fun switchCamera() {
        val camera = capturer as? org.webrtc.CameraVideoCapturer ?: return
        camera.switchCamera(null)
        frontCamera = !frontCamera
    }

    fun shutdown() {
        finish(CallPhase.ENDED, null)
        sessions.values.forEach { it.channel?.dispose(); it.pc.close(); it.pc.dispose() }
        sessions.clear(); factory.dispose(); eglBase.release()
    }

    private fun session(contactId: String): PeerSession = sessions.computeIfAbsent(contactId) {
        val config = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder(BuildConfig.PRIVATCHAT_STUN_URL).createIceServer())).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val holder = arrayOfNulls<PeerSession>(1)
        val observer = observer(contactId) { holder[0] }
        val pc = requireNotNull(factory.createPeerConnection(config, observer)) { "Unable to create peer connection" }
        PeerSession(contactId, pc).also { holder[0] = it }
    }

    private fun observer(contactId: String, session: () -> PeerSession?) = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> if (_call.value.contactId == contactId && _call.value.phase == CallPhase.CONNECTING) _call.value = _call.value.copy(phase = CallPhase.ACTIVE)
                PeerConnection.IceConnectionState.FAILED -> if (_call.value.contactId == contactId) failCall("Direct connection failed. A TURN server may be required on this network.")
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) { candidate ?: return; signal(contactId, "ice", null, candidate) }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) { if (channel != null && session()?.channel == null) attachChannel(session() ?: return, channel) }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) { (receiver?.track() as? VideoTrack)?.let { _remoteVideo.value = it } }
    }

    private fun attachChannel(session: PeerSession, channel: DataChannel) {
        session.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) { if (channel.bufferedAmount() < MAX_BUFFERED / 2) onReady(session.contactId) }
            override fun onStateChange() { if (channel.state() == DataChannel.State.OPEN) onReady(session.contactId) }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                onData(session.contactId, bytes.toString(Charsets.UTF_8))
            }
        })
    }

    private fun createOffer(session: PeerSession) = session.pc.createOffer(object : SimpleSdpObserver() {
        override fun onCreateSuccess(description: SessionDescription?) { description ?: return; session.pc.setLocalDescription(SimpleSdpObserver(), description); signal(session.contactId, "offer", description, null) }
    }, MediaConstraints())

    private fun handlePeerSignal(from: String, payload: JsonObject) {
        val session = session(from)
        when (payload.str("signal")) {
            "offer" -> {
                val sdp = payload.str("sdp") ?: return
                session.pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() { session.pc.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(description: SessionDescription?) { description ?: return; session.pc.setLocalDescription(SimpleSdpObserver(), description); signal(from, "answer", description, null) }
                    }, MediaConstraints()) }
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            "answer" -> payload.str("sdp")?.let { session.pc.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, it)) }
            "ice" -> session.pc.addIceCandidate(IceCandidate(payload.str("mid"), payload.str("line")?.toIntOrNull() ?: 0, payload.str("candidate") ?: return))
        }
    }

    private fun signal(to: String, kind: String, sdp: SessionDescription?, ice: IceCandidate?) = route(Protocol.PEER_SIGNAL, to, buildJsonObject {
        put("signal", kind); if (sdp != null) put("sdp", sdp.description)
        if (ice != null) { put("mid", ice.sdpMid ?: ""); put("line", ice.sdpMLineIndex.toString()); put("candidate", ice.sdp) }
    })

    private fun route(type: String, to: String, extra: JsonObject): Boolean = sendSignal(Protocol.envelope(type, "", buildJsonObject {
        put("to", to); extra.forEach { (key, value) -> put(key, value) }
    }))

    private fun startMedia(contactId: String) {
        val session = session(contactId)
        if (audioTrack == null) { audioSource = factory.createAudioSource(MediaConstraints()); audioTrack = factory.createAudioTrack("privatchat-audio", audioSource).also { session.pc.addTrack(it, listOf("privatchat")) } }
        if (_localVideo.value == null) {
            val chosen = createCapturer() ?: run { failCall("No camera is available"); return }
            capturer = chosen; videoSource = factory.createVideoSource(false)
            chosen.initialize(SurfaceTextureHelper.create("PrivatChatCamera", eglDenseContext()), appContext, videoSource!!.capturerObserver)
            chosen.startCapture(640, 480, 24)
            _localVideo.value = factory.createVideoTrack("privatchat-video", videoSource).also { session.pc.addTrack(it, listOf("privatchat")) }
        } else {
            audioTrack?.let { if (session.pc.senders.none { sender -> sender.track()?.id() == it.id() }) session.pc.addTrack(it, listOf("privatchat")) }
            _localVideo.value?.let { if (session.pc.senders.none { sender -> sender.track()?.id() == it.id() }) session.pc.addTrack(it, listOf("privatchat")) }
        }
    }

    private fun createCapturer(): VideoCapturer? {
        val enumerator: CameraEnumerator = Camera2Enumerator(appContext)
        val desired = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull()
        return desired?.let { enumerator.createCapturer(it, null) }
    }

    private fun hasCallPermissions() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun failCall(reason: String) { finish(CallPhase.FAILED, reason) }
    private fun finish(phase: CallPhase, error: String?) {
        cancelIncomingNotification()
        runCatching { capturer?.stopCapture() }; capturer?.dispose(); capturer = null
        _localVideo.value?.dispose(); _localVideo.value = null; _remoteVideo.value = null
        audioTrack?.dispose(); audioTrack = null
        videoSource?.dispose(); videoSource = null; audioSource?.dispose(); audioSource = null
        sessions.values.forEach { session -> session.pc.senders.filter { it.track()?.kind() in setOf("audio", "video") }.forEach { session.pc.removeTrack(it) } }
        _call.value = _call.value.copy(phase = phase, error = error)
    }

    private fun showIncomingNotification(name: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CALL_CHANNEL, "PrivatChat calls", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(appContext, CALL_NOTIFICATION, Intent(appContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(appContext, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Incoming video call").setContentText(name)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open).setAutoCancel(true).build()
        manager.notify(CALL_NOTIFICATION, notification)
    }

    private fun cancelIncomingNotification() = (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(CALL_NOTIFICATION)

    private data class PeerSession(val contactId: String, val pc: PeerConnection, var channel: DataChannel? = null)
    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) { Log.w(TAG, "SDP create failed") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "SDP set failed") }
    }

    companion object { private const val TAG = "PrivatChatRTC"; private const val MAX_BUFFERED = 512L * 1024L; private const val CALL_TIMEOUT_MS = 30_000L; private const val CALL_CHANNEL = "privatchat_calls"; private const val CALL_NOTIFICATION = 77 }
}
