package com.ownerlink.agent

import android.app.Application
import com.ownerlink.agent.network.DeviceSocket
import com.ownerlink.agent.chat.ChatController
import com.ownerlink.agent.chat.ChatIdentity
import com.ownerlink.agent.chat.ChatIdentityProvider
import com.ownerlink.agent.chat.ChatStore
import com.ownerlink.agent.security.SecureStore
import com.ownerlink.agent.service.KeepAliveWorker
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
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = SecureStore(this)
        identity = ChatIdentityProvider.loadOrCreate(store)
        lateinit var controller: ChatController
        socket = DeviceSocket(this, store, appScope, identity) { type, payload -> controller.handle(type, payload) }
        controller = ChatController(ChatStore(this), identity, store.deviceId) { socket.send(it) }
        chat = controller
        KeepAliveWorker.schedule(this)
    }

    companion object {
        lateinit var instance: OwnerLinkApp
            private set
    }
}
