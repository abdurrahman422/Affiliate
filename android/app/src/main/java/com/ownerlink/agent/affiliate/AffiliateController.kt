package com.ownerlink.agent.affiliate

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import com.ownerlink.agent.network.Protocol
import com.ownerlink.agent.network.str

data class AffiliateUiState(
    val categories: List<AffiliateCategoryItem> = emptyList(),
    val tasks: List<AffiliateTaskItem> = emptyList(),
    val submissions: List<AffiliateSubmissionItem> = emptyList(),
    val ledger: List<AffiliateLedgerItem> = emptyList(),
    val balance: String = "0",
    val dashboardRewardClaimed: Boolean = false,
    val loading: Boolean = true,
    val message: String? = null,
)

class AffiliateController(
    context: Context,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val authToken: () -> String?,
    private val profileJson: () -> String?,
    private val sendServer: (String) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val store = AffiliateStore(context)
    val localData = UserDataFile(context)
    private val notifications = AffiliateNotifications(context)
    private val _state = MutableStateFlow(AffiliateUiState())
    val state: StateFlow<AffiliateUiState> = _state

    init {
        refreshLocal()
        // WebSocket publish events remain the fast path. This low-frequency full
        // refresh recovers automatically if an event was missed during sleep or
        // a Render reconnect without creating another socket.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60_000)
                requestSync(forceFull = true)
            }
        }
    }

    fun handle(type: String, payload: JsonObject) {
        when (type) {
            Protocol.AUTH_OK, Protocol.UNPAIRED_ACK, Protocol.PAIR_SUCCESS, Protocol.CHAT_IDENTITY_ACK -> requestSync(forceFull = true)
            Protocol.TASK_SYNC -> applySync(payload)
            Protocol.TASK_SUBMISSION_ACK -> {
                (payload["submission"] as? JsonObject)?.let { saveServerSubmission(it) }
                // A successful submission can unlock the next server-held sequence item. Request
                // a full snapshot so its older creation revision does not hide it behind cursor.
                requestSync(forceFull = true)
            }
            Protocol.SUBMISSION_STATUS_CHANGED, Protocol.REVISION_REQUESTED, Protocol.COIN_CREDITED -> {
                (payload["submission"] as? JsonObject)?.let { saveServerSubmission(it) }
                val title = (payload["submission"] as? JsonObject)?.str("task_title") ?: "Task update"
                notifications.show(type, title)
                requestSync()
            }
            Protocol.TASK_PUBLISHED, Protocol.TASK_UPDATED, Protocol.TASK_PAUSED, Protocol.TASK_CLOSED -> {
                notifications.show(type, "Task list updated")
                // A full snapshot removes stale cached tasks after pause/archive/delete and
                // also reveals newly published items regardless of their original revision.
                requestSync(forceFull = true)
            }
        }
    }

    fun requestSync(forceFull: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val cursor = if (forceFull) 0 else store.dao.cursor()?.toIntOrNull() ?: 0
            val sent = sendServer(Protocol.envelope(Protocol.TASK_SYNC_REQUEST, deviceId, buildJsonObject { put("cursor", cursor); authToken()?.let { put("auth_token", it) } }))
            Log.i(TAG, "task sync requested full=$forceFull sent=$sent")
            retryPending()
        }
    }

    fun submit(task: AffiliateTaskItem, values: JsonObject) {
        scope.launch(Dispatchers.IO) {
            val localId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val entity = AffiliateSubmissionEntity(localId, localId, task.taskId, task.title, task.reward, "QUEUED", values.toString(), null, now, now)
            store.dao.saveSubmission(entity)
            rebuildRecord()
            send(entity)
            refreshLocalNow()
            AffiliateSyncWorker.enqueue(appContext)
        }
    }

    fun retryAndSync() {
        scope.launch(Dispatchers.IO) { retryPending(); requestSync() }
    }

    fun backgroundAttempt(): Boolean {
        retryPending()
        val cursor = store.dao.cursor()?.toIntOrNull() ?: 0
        return sendServer(Protocol.envelope(Protocol.TASK_SYNC_REQUEST, deviceId, buildJsonObject { put("cursor", cursor); authToken()?.let { put("auth_token", it) } }))
    }

    private fun retryPending() { store.dao.pending().forEach(::send) }

    private fun send(entity: AffiliateSubmissionEntity) {
        val fields = runCatching { Protocol.json.parseToJsonElement(entity.fieldsJson) as JsonObject }.getOrElse { JsonObject(emptyMap()) }
        val sent = sendServer(Protocol.envelope(Protocol.TASK_SUBMIT, deviceId, buildJsonObject {
            put("task_id", entity.taskId); put("client_submission_id", entity.clientSubmissionId); put("fields", fields); authToken()?.let { put("auth_token", it) }
        }))
        store.dao.saveSubmission(entity.copy(status = AffiliateQueueRules.afterAttempt(sent), updatedAt = System.currentTimeMillis()))
    }

    private fun applySync(payload: JsonObject) {
        scope.launch(Dispatchers.IO) {
            val categories = AffiliateJson.objects(payload["categories"]).map { AffiliateCategoryItem(it.str("category_id").orEmpty(), it.str("name").orEmpty()) }.filter { it.categoryId.isNotBlank() }
            val taskObjects = AffiliateJson.objects(payload["tasks"])
            Log.i(TAG, "task sync received tasks=${taskObjects.size} full=${payload["full"]?.jsonPrimitive?.contentOrNull}")
            val entities = taskObjects.map { value ->
                val task = AffiliateJson.task(value)
                AffiliateTaskEntity(task.taskId, task.categoryId, task.title, task.description, task.instructions, task.reward,
                    task.status, task.priority, task.deadline, (value["field_schema"] ?: JsonArray(emptyList())).toString(),
                    value["revision"]?.jsonPrimitive?.intOrNull ?: 0, task.taskType, task.actionUrl, task.actionDurationSeconds)
            }
            if (payload["full"]?.jsonPrimitive?.contentOrNull == "true") store.dao.clearTasks()
            store.dao.saveTasks(entities)
            AffiliateJson.objects(payload["submissions"]).forEach(::saveServerSubmissionNow)
            val wallet = payload["wallet"] as? JsonObject
            store.dao.clearLedger()
            val ledger = AffiliateJson.objects(wallet?.get("ledger")).map { value -> AffiliateLedgerEntity(
                value.str("ledger_id") ?: return@map AffiliateLedgerEntity(UUID.randomUUID().toString(), null, "0", "Invalid", "0", ""),
                value.str("task_id"), value.str("amount") ?: "0", value.str("reason") ?: "", value.str("balance_after") ?: "0", value.str("created_at") ?: "",
            ) }
            store.dao.saveLedger(ledger)
            store.dao.saveSync(AffiliateSyncEntity(value = payload["cursor"]?.jsonPrimitive?.contentOrNull ?: "0"))
            val dashboardRewardClaimed = wallet?.get("dashboard_reward_claimed")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            rebuildRecord(); refreshLocalNow(wallet?.str("balance"), categories, dashboardRewardClaimed)
        }
    }

    private fun saveServerSubmission(value: JsonObject) { scope.launch(Dispatchers.IO) { saveServerSubmissionNow(value); rebuildRecord(); refreshLocalNow() } }

    private fun saveServerSubmissionNow(value: JsonObject) {
        val clientId = value.str("client_submission_id") ?: return
        val previous = store.dao.byClientId(clientId)
        val taskId = value.str("task_id") ?: previous?.taskId ?: return
        val task = store.dao.task(taskId)
        val now = System.currentTimeMillis()
        store.dao.saveSubmission(AffiliateSubmissionEntity(
            submissionId = value.str("submission_id") ?: previous?.submissionId ?: clientId,
            clientSubmissionId = clientId, taskId = taskId, taskTitle = value.str("task_title") ?: previous?.taskTitle ?: task?.title ?: taskId,
            reward = value.str("reward") ?: previous?.reward ?: task?.reward ?: "0", status = value.str("status") ?: "PENDING_REVIEW",
            fieldsJson = (value["submitted_fields"] ?: previous?.fieldsJson?.let { Protocol.json.parseToJsonElement(it) } ?: JsonObject(emptyMap())).toString(),
            reviewReason = value.str("review_reason"), createdAt = previous?.createdAt ?: now, updatedAt = now,
        ))
    }

    private fun refreshLocal() { scope.launch(Dispatchers.IO) { refreshLocalNow() } }

    private fun refreshLocalNow(balanceOverride: String? = null, categoryOverride: List<AffiliateCategoryItem>? = null,
                                dashboardRewardOverride: Boolean? = null) {
        val taskEntities = store.dao.tasks()
        val submissions = store.dao.submissions()
        val ledger = store.dao.ledger()
        val tasks = taskEntities.map { entity -> AffiliateJson.task(buildJsonObject {
            put("task_id", entity.taskId); put("category_id", entity.categoryId); put("title", entity.title); put("description", entity.description)
            put("instructions", entity.instructions); put("reward", entity.reward); put("status", entity.status); put("priority", entity.priority)
            put("task_type", entity.taskType); entity.actionUrl?.let { put("action_url", it) }; put("action_duration_seconds", entity.actionDurationSeconds)
            if (entity.deadline != null) put("end_at", entity.deadline); put("field_schema", Protocol.json.parseToJsonElement(entity.schemaJson))
        }) }
        val uiSubmissions = submissions.map { AffiliateSubmissionItem(it.submissionId, it.clientSubmissionId, it.taskId, it.status, it.fieldsJson, it.createdAt, it.updatedAt, it.reviewReason) }
        val uiLedger = ledger.map { AffiliateLedgerItem(it.ledgerId, it.taskId, it.amount, it.reason, it.balanceAfter, it.createdAt) }
        val balance = balanceOverride ?: ledger.firstOrNull()?.balanceAfter ?: _state.value.balance
        val dashboardRewardClaimed = dashboardRewardOverride
            ?: ledger.any { it.reason.equals("Dashboard connection bonus", ignoreCase = true) }
            || _state.value.dashboardRewardClaimed
        _state.value = AffiliateUiState(categoryOverride ?: _state.value.categories, tasks, uiSubmissions, uiLedger,
            balance, dashboardRewardClaimed, loading = false)
    }

    private fun rebuildRecord() = localData.rebuild(store.dao.tasks(), store.dao.submissions(), store.dao.ledger(), profileJson())

    private companion object { const val TAG = "AffiliateSync" }

}
