package com.ownerlink.agent

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ownerlink.agent.affiliate.TaskField
import com.ownerlink.agent.ui.DynamicField
import com.ownerlink.agent.ui.theme.PrivatChatTheme
import com.ownerlink.agent.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test

class AffiliateUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun schemaFieldRendersRequiredLabelAndHelp() {
        compose.setContent {
            PrivatChatTheme(ThemeMode.LIGHT) {
                DynamicField(TaskField("public_url", "url", "Public URL", true, helpText = "Use an authorized public link"), "", {}, null)
            }
        }
        compose.onNodeWithText("Public URL *").assertExists()
        compose.onNodeWithText("Use an authorized public link").assertExists()
    }
}
