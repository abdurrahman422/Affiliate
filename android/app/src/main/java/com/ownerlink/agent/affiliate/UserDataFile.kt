package com.ownerlink.agent.affiliate

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.jsonPrimitive

class UserDataFile(context: Context) {
    private val directory = File(context.filesDir, "Affiliate")
    val file = File(directory, "user_data.txt")
    private val prohibited = Regex("(?i)(password|passwd|passcode|2fa|totp|otp|recovery.?code|cookie|session.?token|private.?key|dashboard.?token|secret)")

    @Synchronized
    fun rebuild(tasks: List<AffiliateTaskEntity>, submissions: List<AffiliateSubmissionEntity>, ledger: List<AffiliateLedgerEntity>, profileJson: String? = null) {
        directory.mkdirs()
        val taskNames = tasks.associate { it.taskId to it.title }
        val credits = ledger.filter { it.taskId != null }.groupBy { it.taskId }
        val body = buildString {
            appendLine("Affiliate local work record")
            appendLine("Updated: ${DateFormat.getDateTimeInstance().format(Date())}")
            appendLine("This file stays on this phone and is never part of automatic task sync.")
            val profile = profileJson?.let { runCatching { com.ownerlink.agent.network.Protocol.json.parseToJsonElement(it).jsonObject }.getOrNull() }
            profile?.let {
                appendLine("Name: ${safe(it["full_name"]?.jsonPrimitive?.content ?: "")}")
                appendLine("User ID: ${safe(it["user_id"]?.jsonPrimitive?.content ?: "")}")
                appendLine("Age: ${safe(it["age"]?.jsonPrimitive?.content ?: "")}")
                appendLine("Gender: ${safe(it["gender"]?.jsonPrimitive?.content ?: "")}")
                appendLine("Education: ${safe(it["education"]?.jsonPrimitive?.content ?: "")}")
                appendLine("Account created: ${safe(it["created_at"]?.jsonPrimitive?.content ?: "")}")
                appendLine("Withdrawal: ${safe(it["withdrawal_method"]?.jsonPrimitive?.content ?: "")} ${safe(it["withdrawal_number_masked"]?.jsonPrimitive?.content ?: "")}")
            }
            submissions.sortedBy { it.createdAt }.forEach { submission ->
                appendLine("==================================================")
                appendLine("Task: ${safe(taskNames[submission.taskId] ?: submission.taskTitle)}")
                appendLine("Task ID: ${safe(submission.taskId)}")
                appendLine("Submission ID: ${safe(submission.submissionId)}")
                appendLine("Date: ${DateFormat.getDateTimeInstance().format(Date(submission.createdAt))}")
                appendLine("Status: ${safe(submission.status)}")
                appendLine("Reward: ${safe(submission.reward)} coin")
                val parsed = runCatching { com.ownerlink.agent.network.Protocol.json.parseToJsonElement(submission.fieldsJson).jsonObject }.getOrNull()
                parsed?.forEach { (key, value) -> if (!prohibited.containsMatchIn(key)) appendLine("${safe(key.replace('_', ' ').replaceFirstChar(Char::uppercase))}: ${safe(value.toString().trim('"'))}") }
                credits[submission.taskId]?.lastOrNull()?.let { appendLine("Coin credit reference: ${safe(it.ledgerId)}") }
            }
            if (submissions.isEmpty()) appendLine("No local task activity yet.")
        }
        val temp = File(directory, "user_data.txt.tmp")
        temp.outputStream().buffered().use { output -> output.write(body.toByteArray(Charsets.UTF_8)); output.flush() }
        runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        rotateIfNeeded()
    }

    fun read(): String = if (file.isFile) file.readText(Charsets.UTF_8) else "No local task activity yet."

    fun exportTo(output: OutputStream) {
        require(file.isFile) { "No activity to export yet" }
        file.inputStream().use { it.copyTo(output) }
    }

    fun containsProhibited(text: String): Boolean = prohibited.containsMatchIn(text)

    private fun safe(value: String): String = if (prohibited.containsMatchIn(value)) "[excluded]" else value.replace(Regex("[\\r\\n]+"), " ").take(2048)

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() <= 2L * 1024 * 1024) return
        val archive = File(directory, "user_data_previous.txt")
        if (archive.exists()) archive.delete()
        file.copyTo(archive, overwrite = true)
        file.writeText("Affiliate local work record rotated. Previous entries remain in user_data_previous.txt\n", Charsets.UTF_8)
    }
}
