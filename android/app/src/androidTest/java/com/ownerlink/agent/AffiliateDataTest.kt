package com.ownerlink.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ownerlink.agent.affiliate.AffiliateLedgerEntity
import com.ownerlink.agent.affiliate.AffiliateStore
import com.ownerlink.agent.affiliate.AffiliateSubmissionEntity
import com.ownerlink.agent.affiliate.AffiliateTaskEntity
import com.ownerlink.agent.affiliate.UserDataFile
import com.ownerlink.agent.calllog.CallLogRepository
import com.ownerlink.agent.security.SecureStore
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AffiliateDataTest {
    @Test fun affiliateSessionAndProfilePersistEncrypted() {
        val store = SecureStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val oldToken = store.affiliateAuthToken; val oldProfile = store.affiliateProfileJson
        store.affiliateAuthToken = "test-session-value"
        store.affiliateProfileJson = "{\"user_id\":\"user-test\"}"
        val restored = SecureStore(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals("test-session-value", restored.affiliateAuthToken)
        assertEquals("{\"user_id\":\"user-test\"}", restored.affiliateProfileJson)
        store.affiliateAuthToken = oldToken; store.affiliateProfileJson = oldProfile
    }
    @Test fun roomCacheSupportsOfflineTasksQueueWalletAndLocalTxtRedaction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("affiliate-room.db")
        val store = AffiliateStore(context)
        val task = AffiliateTaskEntity("task", "youtube", "Video Review", "Safe QA", "Review a public video", "0.5", "PUBLISHED", 10, null, "[]", 1)
        store.dao.saveTasks(listOf(task))
        val submission = AffiliateSubmissionEntity("local", "client", "task", "Video Review", "0.5", "QUEUED", "{\"public_url\":\"https://example.com\",\"password\":\"must-not-appear\"}", null, 1, 1)
        store.dao.saveSubmission(submission)
        val ledger = AffiliateLedgerEntity("ledger", "task", "0.5", "Approved", "0.5", "2026-09-13T00:00:00Z")
        store.dao.saveLedger(listOf(ledger))
        assertEquals("Video Review", store.dao.tasks().single().title)
        assertEquals("QUEUED", store.dao.pending().single().status)
        assertEquals("0.5", store.dao.ledger().single().balanceAfter)

        val local = UserDataFile(context)
        local.rebuild(store.dao.tasks(), store.dao.submissions(), store.dao.ledger())
        val text = local.read()
        assertTrue(text.contains("Video Review"))
        assertTrue(text.contains("https://example.com"))
        assertFalse(text.contains("must-not-appear"))
        assertFalse(text.lowercase().contains("password"))
        val exported = ByteArrayOutputStream().also(local::exportTo).toString(Charsets.UTF_8.name())
        assertEquals(text, exported)
    }

    @Test fun nameAndThemePersistInEncryptedSettings() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SecureStore(context)
        val oldName = store.friendlyName; val oldAppearance = store.appearance
        try {
            store.friendlyName = "Persisted Affiliate Name"; store.appearance = "DARK"
            val reopened = SecureStore(context)
            assertEquals("Persisted Affiliate Name", reopened.friendlyName)
            assertEquals("DARK", reopened.appearance)
        } finally { store.friendlyName = oldName; store.appearance = oldAppearance }
    }

    @Test fun callLogPermissionDeniedReturnsPrivateError() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (!CallLogRepository(context).hasPermission()) {
            val result = CallLogRepository(context).list(0, 20, null)
            assertEquals("CALL_LOG_PERMISSION_REQUIRED", result["code"]?.toString()?.trim('"'))
            assertFalse(result.toString().contains("number"))
        }
    }
}
