package com.ownerlink.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ownerlink.agent.ui.OwnerLinkAppUi
import com.ownerlink.agent.ui.theme.OwnerLinkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OwnerLinkTheme {
                OwnerLinkAppUi()
            }
        }
    }
}
