package com.ownerlink.agent

import com.ownerlink.agent.affiliate.AffiliateJson
import com.ownerlink.agent.affiliate.AffiliateQueueRules
import com.ownerlink.agent.affiliate.DynamicTaskValidator
import com.ownerlink.agent.affiliate.ProofValue
import com.ownerlink.agent.affiliate.TaskField
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AffiliateTaskTest {
    @Test fun dynamicFieldsParseAndValidate() {
        val raw = buildJsonObject {
            put("task_id", "task-1"); put("category_id", "youtube"); put("title", "Metadata QA")
            put("description", "Review metadata"); put("instructions", "Check the public listing"); put("reward", "0.5")
            put("status", "PUBLISHED"); put("priority", 10)
            put("field_schema", buildJsonArray {
                add(buildJsonObject { put("id", "public_url"); put("type", "url"); put("label", "Public URL"); put("required", true) })
                add(buildJsonObject { put("id", "quality"); put("type", "dropdown"); put("label", "Quality"); put("required", true); put("choices", buildJsonArray { add(JsonPrimitive("Pass")); add(JsonPrimitive("Needs improvement")) }) })
            })
        }
        val task = AffiliateJson.task(raw)
        assertEquals(2, task.fields.size)
        assertTrue(DynamicTaskValidator.validate(task.fields, mapOf("public_url" to "https://example.com/watch", "quality" to "Pass")).isEmpty())
        assertTrue(DynamicTaskValidator.validate(task.fields, mapOf("public_url" to "javascript:bad", "quality" to "Other")).isNotEmpty())
    }

    @Test fun fileRulesAndSensitiveKeysAreRejected() {
        val field = TaskField("proof", "image", "Screenshot", true, maxSizeMb = 1, allowedTypes = listOf("image/png"))
        assertTrue(DynamicTaskValidator.validate(listOf(field), mapOf("proof" to ProofValue("proof.png", "image/png", 100, "a".repeat(64), "content://safe"))).isEmpty())
        assertFalse(DynamicTaskValidator.validate(listOf(field), mapOf("proof" to ProofValue("proof.jpg", "image/jpeg", 2_000_000, "a".repeat(64), "content://safe"))).isEmpty())
        assertTrue(DynamicTaskValidator.validate(emptyList(), mapOf("session_token" to "never-store")).containsKey("session_token"))
    }

    @Test fun queuedSubmissionRetriesAfterOfflineAttempt() {
        assertEquals("QUEUED", AffiliateQueueRules.afterAttempt(false))
        assertEquals("SENDING", AffiliateQueueRules.afterAttempt(true))
        assertTrue(AffiliateQueueRules.shouldRetry("QUEUED"))
        assertFalse(AffiliateQueueRules.shouldRetry("PENDING_REVIEW"))
    }
}
