package com.ownerlink.agent

import android.app.Application
import com.ownerlink.agent.network.DeviceSocket
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
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = SecureStore(this)
        socket = DeviceSocket(this, store, appScope)
        KeepAliveWorker.schedule(this)
    }

    companion object {
        lateinit var instance: OwnerLinkApp
            private set
    }
}
