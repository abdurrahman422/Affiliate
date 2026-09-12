package com.ownerlink.agent

import android.app.Application
import com.ownerlink.agent.network.DeviceSocket
import com.ownerlink.agent.chat.ChatController
import com.ownerlink.agent.chat.ChatIdentity
import com.ownerlink.agent.chat.ChatIdentityProvider
import com.ownerlink.agent.chat.ChatStore
import com.ownerlink.agent.security.SecureStore
import com.ownerlink.agent.service.KeepAliveWorker
import com.ownerlink.agent.rtc.PeerRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class OwnerLinkApp : Application() {
    lateinit var store: SecureStore
        private set
    lateinit var socket: DeviceSocket
        private set
    lateinit var identity: ChatIdentity
        private set
    lateinit var chat: ChatController
        private set
    lateinit var rtc: PeerRtcManager
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = SecureStore(this)
        identity = ChatIdentityProvider.loadOrCreate(store)
        var controllerRef: ChatController? = null
        var peerRef: PeerRtcManager? = null
        socket = DeviceSocket(this, store, appScope, identity) { type, payload ->
            peerRef?.handle(type, payload) { controllerRef?.contact(it)?.name ?: "PrivatChat contact" }
            controllerRef?.handleServer(type, payload)
        }
        val controller = ChatController(
            this, ChatStore(this), identity, appScope, store.deviceId,
            sendServer = { socket.send(it) },
            sendPeer = { contactId, text -> peerRef?.sendData(contactId, text) == true },
            ensurePeer = { contactId -> peerRef?.ensureDataSession(contactId) },
        )
        controllerRef = controller
        val peer = PeerRtcManager(
            this, identity.contactId, appScope, { socket.send(it) },
            onData = controller::handlePeer,
            onReady = controller::onPeerReady,
        )
        peerRef = peer
        chat = controller
        rtc = peer
        KeepAliveWorker.schedule(this)
    }

    companion object {
        lateinit var instance: OwnerLinkApp
            private set
    }
}
