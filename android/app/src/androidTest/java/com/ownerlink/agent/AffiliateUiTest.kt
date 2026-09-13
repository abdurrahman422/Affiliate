package com.ownerlink.agent

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.ownerlink.agent.affiliate.TaskField
import com.ownerlink.agent.ui.DynamicField
import com.ownerlink.agent.ui.AffiliateSettings
import com.ownerlink.agent.ui.theme.PrivatChatTheme
import com.ownerlink.agent.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test

class AffiliateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun keepVisible() {
        compose.activity.setShowWhenLocked(true)
        compose.activity.setTurnScreenOn(true)
    }

    @Test fun schemaFieldRendersRequiredLabelAndHelp() {
        keepVisible()
        compose.setContent {
            PrivatChatTheme(ThemeMode.LIGHT) {
                DynamicField(TaskField("public_url", "url", "Public URL", true, helpText = "Use an authorized public link"), "", {}, null)
            }
        }
        compose.onNodeWithText("Public URL *").assertExists()
        compose.onNodeWithText("Use an authorized public link").assertExists()
    }

    @Test fun settingsContainOnlyProductSectionsAndNamePersists() {
        keepVisible()
        val oldName = OwnerLinkApp.instance.store.friendlyName
        compose.setContent { PrivatChatTheme(ThemeMode.LIGHT) { AffiliateSettings() } }
        listOf("Profile", "Text Downloader", "Download My Data", "Appearance", "App Information", "Software Version", "Build Number").forEach {
            compose.onNodeWithText(it, substring = true).assertExists()
        }
        assert(compose.onAllNodesWithText("Connect with Dashboard").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Remote Screen", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Device Access", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Notifications", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Change Name").performClick()
        compose.onNodeWithText("Name").performTextClearance()
        compose.onNodeWithText("Name").performTextInput("Premium Tester")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Premium Tester").assertExists()
        assert(OwnerLinkApp.instance.store.friendlyName == "Premium Tester")
        OwnerLinkApp.instance.store.friendlyName = oldName
    }

    @Test fun dashboardCodeCanBeOpenedAndCopiedWhenUnpaired() {
        keepVisible()
        val app = OwnerLinkApp.instance
        compose.setContent { PrivatChatTheme(ThemeMode.LIGHT) { AffiliateSettings() } }
        compose.onAllNodesWithText("Connect with Dashboard")[1].performClick()
        if (app.store.paired) compose.onNodeWithText("Dashboard connected").assertExists() else {
            compose.onNodeWithContentDescription("Dashboard QR Code").assertExists()
            compose.onNodeWithText("Copy Dashboard Code").performClick()
        }
    }
}
