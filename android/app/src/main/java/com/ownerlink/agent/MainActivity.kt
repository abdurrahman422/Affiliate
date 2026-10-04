package com.ownerlink.agent

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ownerlink.agent.ui.OwnerLinkAppUi
import com.ownerlink.agent.ui.theme.PrivatChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.IS_MASTER) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge()
        setContent {
            val appearance by (application as OwnerLinkApp).appearance.collectAsState()
            PrivatChatTheme(appearance) {
                OwnerLinkAppUi()
            }
        }
    }
}
