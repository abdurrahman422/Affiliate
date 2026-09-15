package com.ownerlink.agent

import android.app.Application
import com.ownerlink.agent.network.DeviceSocket
import com.ownerlink.agent.affiliate.AffiliateController
import com.ownerlink.agent.affiliate.AffiliateAccountController
import com.ownerlink.agent.affiliate.AffiliateSyncWorker
import com.ownerlink.agent.security.SecureStore
import com.ownerlink.agent.service.KeepAliveWorker
import com.ownerlink.agent.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class OwnerLinkApp : Application() {
    lateinit var store: SecureStore
        private set
    lateinit var socket: DeviceSocket
        private set
    lateinit var affiliate: AffiliateController
        private set
    lateinit var affiliateAccount: AffiliateAccountController
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _appearance = MutableStateFlow(ThemeMode.SYSTEM)
    val appearance: StateFlow<ThemeMode> = _appearance

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = SecureStore(this)
        _appearance.value = runCatching { ThemeMode.valueOf(store.appearance) }.getOrDefault(ThemeMode.SYSTEM)
        var affiliateRef: AffiliateController? = null
        socket = DeviceSocket(this, store, appScope) { type, payload ->
            affiliateRef?.handle(type, payload)
        }
        affiliate = AffiliateController(this, appScope, store.deviceId, { store.affiliateAuthToken }, { store.affiliateProfileJson }) { socket.send(it) }
        affiliateRef = affiliate
        affiliateAccount = AffiliateAccountController(this, store, appScope) { affiliate.requestSync() }
        KeepAliveWorker.schedule(this)
        AffiliateSyncWorker.schedule(this)
    }

    fun setAppearance(mode: ThemeMode) {
        store.appearance = mode.name
        _appearance.value = mode
    }

    companion object {
        lateinit var instance: OwnerLinkApp
            private set
    }
}
