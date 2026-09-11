package com.ownerlink.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ownerlink.agent.security.SecureStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = SecureStore(context.applicationContext)
        if (store.serviceWanted && store.serverUrl.isNotBlank()) {
            ConnectionService.start(context.applicationContext)
        }
    }
}
