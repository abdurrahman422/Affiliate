package com.ownerlink.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ownerlink.agent.BuildConfig
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.affiliate.AffiliateSubmissionItem
import com.ownerlink.agent.affiliate.AffiliateTaskItem
import com.ownerlink.agent.affiliate.DynamicTaskValidator
import com.ownerlink.agent.affiliate.ProofValue
import com.ownerlink.agent.affiliate.TaskField
import com.ownerlink.agent.network.ConnectionState
import com.ownerlink.agent.files.FileIds
import com.ownerlink.agent.pairing.PairingJson
import com.ownerlink.agent.pairing.QrEncoder
import com.ownerlink.agent.ui.theme.ThemeMode
import com.ownerlink.agent.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

private enum class AffiliateTab { Tasks, Activity, Wallet, Settings }

@Composable
fun AffiliateHome() {
    var tab by remember { mutableStateOf(AffiliateTab.Tasks) }
    var openTask by remember { mutableStateOf<AffiliateTaskItem?>(null) }
    Scaffold(bottomBar = {
        if (openTask == null) NavigationBar {
            AffiliateTab.entries.forEach { item ->
                val icon = when (item) { AffiliateTab.Tasks -> Icons.AutoMirrored.Rounded.Assignment; AffiliateTab.Activity -> Icons.Rounded.History; AffiliateTab.Wallet -> Icons.Rounded.AccountBalanceWallet; AffiliateTab.Settings -> Icons.Rounded.Settings }
                NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(icon, item.name) }, label = { Text(item.name) })
            }
        }
    }) { padding -> Box(Modifier.padding(padding).fillMaxSize()) {
        when {
            openTask != null -> AffiliateTaskDetail(openTask!!) { openTask = null }
            tab == AffiliateTab.Tasks -> AffiliateTasks { openTask = it }
            tab == AffiliateTab.Activity -> AffiliateActivity()
            tab == AffiliateTab.Wallet -> AffiliateWallet()
            else -> AffiliateSettings()
        }
    } }
}

@Composable
private fun AffiliateHeader(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.Assignment, null, tint = MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.size(12.dp)); Column { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable
private fun AffiliateTasks(open: (AffiliateTaskItem) -> Unit) {
    val app = OwnerLinkApp.instance
    val state by app.affiliate.state.collectAsState()
    val socket by app.socket.state.collectAsState()
    val submitted = state.submissions.associateBy { it.taskId }
    val available = state.tasks.filter { it.status == "PUBLISHED" && submitted[it.taskId] == null }
    val progress = state.tasks.filter { submitted[it.taskId]?.status in setOf("QUEUED", "SENDING", "IN_PROGRESS", "REVISION_REQUESTED") }
    val pending = state.tasks.filter { submitted[it.taskId]?.status == "PENDING_REVIEW" }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { AffiliateHeader("Affiliate", if (socket in setOf(ConnectionState.Online, ConnectionState.WaitingForPair)) "Synced" else "Offline mode") }
        if (state.loading) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (!state.loading && state.tasks.isEmpty()) item { AffiliateEmpty("No tasks available", "Published tasks will appear here. Cached tasks remain visible offline.") }
        if (available.any { it.priority >= 10 }) { item { SectionLabel("Priority / Featured") }; items(available.filter { it.priority >= 10 }, key = { "featured-${it.taskId}" }) { TaskCard(it, null, open) } }
        if (available.isNotEmpty()) { item { SectionLabel("Available") }; items(available, key = { "available-${it.taskId}" }) { TaskCard(it, null, open) } }
        if (progress.isNotEmpty()) { item { SectionLabel("In Progress") }; items(progress, key = { "progress-${it.taskId}" }) { TaskCard(it, submitted[it.taskId], open) } }
        if (pending.isNotEmpty()) { item { SectionLabel("Pending Review") }; items(pending, key = { "pending-${it.taskId}" }) { TaskCard(it, submitted[it.taskId], open) } }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable private fun SectionLabel(value: String) { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }

@Composable
private fun TaskCard(task: AffiliateTaskItem, submission: AffiliateSubmissionItem?, open: (AffiliateTaskItem) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(enabled = submission?.status !in setOf("PENDING_REVIEW", "APPROVED")) { open(task) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(task.categoryId.replaceFirstChar(Char::uppercase), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold); Text("${task.reward} coin", fontWeight = FontWeight.Bold) }
            Text(task.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(task.description, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { AssistChip(onClick = {}, label = { Text((submission?.status ?: task.status).replace('_', ' ')) }); task.deadline?.let { Text("Due $it", style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun AffiliateTaskDetail(task: AffiliateTaskItem, back: () -> Unit) {
    val app = OwnerLinkApp.instance
    val values = remember(task.taskId) { mutableStateMapOf<String, Any?>() }
    val errors = remember(task.taskId) { mutableStateMapOf<String, String>() }
    var review by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }; Column { Text(task.categoryId.replaceFirstChar(Char::uppercase), color = MaterialTheme.colorScheme.primary); Text(task.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) } }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Reward"); Text("${task.reward} coin", fontWeight = FontWeight.Bold) } }
        Text(task.description); Text("Instructions", style = MaterialTheme.typography.titleLarge); Text(task.instructions)
        HorizontalDivider(); Text("Required information", style = MaterialTheme.typography.titleLarge)
        task.fields.forEach { field -> DynamicField(field, values[field.id], { values[field.id] = it; errors.remove(field.id) }, errors[field.id]) }
        Button(onClick = { errors.clear(); errors.putAll(DynamicTaskValidator.validate(task.fields, values)); if (errors.isEmpty()) review = true }, modifier = Modifier.fillMaxWidth()) { Text("Review submission") }
        Text("Only the answers and proof files you explicitly choose are submitted. Other phone files are never scanned.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (review) AlertDialog(onDismissRequest = { review = false }, title = { Text("Review before submitting") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(task.title, fontWeight = FontWeight.Bold); task.fields.filter { it.type != "note" && values[it.id] != null }.forEach { Text("${it.label}: ${displayValue(values[it.id])}") }; Text("After submission, this task will be Pending Review.") } }, confirmButton = { Button(onClick = { app.affiliate.submit(task, valuesToJson(values)); review = false; back() }) { Text("Submit task") } }, dismissButton = { TextButton(onClick = { review = false }) { Text("Go back") } })
}

@Composable
internal fun DynamicField(field: TaskField, value: Any?, update: (Any?) -> Unit, error: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { readProof(context, uri, field.maxSizeMb) }
                .onSuccess(update)
                .onFailure { Toast.makeText(context, it.message ?: "Unable to read proof", Toast.LENGTH_SHORT).show() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        when (field.type) {
            "note" -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Text(field.label, Modifier.padding(14.dp)) }
            "long_text" -> OutlinedTextField(value?.toString() ?: "", update, Modifier.fillMaxWidth(), label = { Text(label(field)) }, minLines = 4, supportingText = { field.helpText?.let { Text(it) } })
            "short_text", "url", "number", "datetime" -> OutlinedTextField(value?.toString() ?: "", update, Modifier.fillMaxWidth(), label = { Text(label(field)) }, supportingText = { field.helpText?.let { Text(it) } })
            "dropdown" -> { Text(label(field)); Column { field.choices.forEach { choice -> AssistChip(onClick = { update(choice) }, label = { Text(choice) }, leadingIcon = if (value == choice) ({ Icon(Icons.Rounded.CloudDone, null, Modifier.size(16.dp)) }) else null) } } }
            "multi_select" -> { val selected = (value as? List<*>)?.filterIsInstance<String>().orEmpty(); Text(label(field)); Column { field.choices.forEach { choice -> AssistChip(onClick = { update(if (choice in selected) selected - choice else selected + choice) }, label = { Text(choice) }, leadingIcon = if (choice in selected) ({ Icon(Icons.Rounded.CloudDone, null, Modifier.size(16.dp)) }) else null) } } }
            "checkbox" -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value == true, { update(it) }); Text(label(field)) }
            "image", "file" -> { Text(label(field)); OutlinedButton(onClick = { picker.launch(if (field.type == "image") arrayOf("image/*") else field.allowedTypes.ifEmpty { listOf("*/*") }.toTypedArray()) }) { Text(if (value is ProofValue) value.name else "Choose ${if (field.type == "image") "image" else "document"}") }; if (value is ProofValue) Text("${value.mime} · ${formatSize(value.size)}", style = MaterialTheme.typography.bodySmall) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AffiliateActivity() {
    val state by OwnerLinkApp.instance.affiliate.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { AffiliateHeader("Activity", "Submission and review timeline") }
        if (state.submissions.isEmpty()) item { AffiliateEmpty("No activity yet", "Your queued, submitted, reviewed, and rewarded work will appear here.") }
        items(state.submissions, key = { it.clientSubmissionId }) { item ->
            val task = state.tasks.firstOrNull { it.taskId == item.taskId }
            ListItem(headlineContent = { Text(task?.title ?: item.taskId) }, supportingContent = { Column { Text(item.status.replace('_', ' ')); item.reviewReason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(DateFormat.getDateTimeInstance().format(Date(item.updatedAt)), style = MaterialTheme.typography.bodySmall) } }, leadingContent = { Box(Modifier.size(42.dp).background(statusColor(item.status), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.History, null) } })
        }
    }
}

@Composable
private fun AffiliateWallet() {
    val state by OwnerLinkApp.instance.affiliate.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { AffiliateHeader("Wallet", "Server-authoritative coin balance") }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp)) { Text("Current balance"); Text("${state.balance} coin", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Text("The app never credits itself.", style = MaterialTheme.typography.bodySmall) } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { WalletStat("Earned this week", earnedSince(state.ledger, 7)); WalletStat("Earned this month", earnedSince(state.ledger, 30)) } }
        item { SectionLabel("Recent transactions") }
        if (state.ledger.isEmpty()) item { AffiliateEmpty("No transactions", "Approved task rewards and audited adjustments will appear here.") }
        items(state.ledger, key = { it.ledgerId }) { item -> ListItem(headlineContent = { Text(item.reason) }, supportingContent = { Text(item.createdAt) }, trailingContent = { Text("${if (item.amount.startsWith("-")) "" else "+"}${item.amount}", fontWeight = FontWeight.Bold) }) }
    }
}

@Composable
private fun AffiliateSettings() {
    val app = OwnerLinkApp.instance
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by app.socket.state.collectAsState()
    val taskState by app.affiliate.state.collectAsState()
    val appearance by app.appearance.collectAsState()
    var showDashboard by remember { mutableStateOf(false) }
    var showLocal by remember { mutableStateOf(false) }
    var editProfile by remember { mutableStateOf(false) }
    val localText by produceState("No local work record yet.", showLocal) {
        if (showLocal) value = withContext(Dispatchers.IO) { app.affiliate.localData.read().takeLast(6000) }
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AffiliateHeader("Settings", "Profile, notifications, data, and device access")
        SettingsTitle("Profile"); ListItem(headlineContent = { Text(app.store.friendlyName.ifBlank { "Affiliate worker" }) }, supportingContent = { val approved = taskState.submissions.count { it.status == "APPROVED" }; val rejected = taskState.submissions.count { it.status == "REJECTED" }; Text("Worker ID ${app.identity.contactId}\nCompleted ${approved + rejected} · Approved $approved · Rejected $rejected · Balance ${taskState.balance} coin") }, modifier = Modifier.clickable { editProfile = true })
        SettingsTitle("Notifications"); SettingsRow("Task notifications", if (Permissions.hasNotifications(context)) "Enabled" else "Permission required") { if (Build.VERSION.SDK_INT >= 33) runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else Permissions.openAppSettings(context) }
        SettingsTitle("Local Data"); SettingsRow("user_data.txt", "Stays on this phone unless you share it or an authorized dashboard requests it through Device Files.") { showLocal = !showLocal }
        if (showLocal) { Card { Text(localText, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall) }; OutlinedButton(onClick = { shareLocalFile(context) }) { Text("Share local record") } }
        SettingsTitle("Connect with Dashboard"); Button(onClick = { showDashboard = !showDashboard }) { Text(if (app.store.paired) "Dashboard connected" else "Connect with Dashboard") }
        if (showDashboard) {
            if (app.store.paired) { Text("Connected. Dashboard device privileges remain separate from task identity."); OutlinedButton(onClick = app.socket::disconnectDashboard) { Text("Disconnect Dashboard") } }
            else { val payload = remember(app.store.pairingToken) { PairingJson.encode(app.store) }; val qr = remember(payload) { QrEncoder.encode(payload) }; Text("This one-time code grants dashboard device access. Keep it private."); Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { androidx.compose.foundation.Image(qr.asImageBitmap(), "Dashboard pairing QR", Modifier.fillMaxWidth().height(230.dp).padding(14.dp)) }; OutlinedButton(onClick = { clipboard.setText(AnnotatedString(payload)); Toast.makeText(context, "Dashboard code copied", Toast.LENGTH_SHORT).show() }) { Text("Copy Dashboard Code") } }
        }
        SettingsTitle("Device Access"); PermissionSetting("Gallery / Media", Permissions.hasMedia(context)) { runtime.launch(Permissions.mediaPermissions()) }; PermissionSetting("Files", Permissions.hasAllFiles()) { Permissions.openAllFilesSettings(context) }; PermissionSetting("Remote Screen / Accessibility", Permissions.accessibilityEnabled(context)) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        SettingsTitle("Appearance"); Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { ThemeMode.entries.forEach { mode -> if (appearance == mode) Button(onClick = { app.setAppearance(mode) }) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) } else OutlinedButton(onClick = { app.setAppearance(mode) }) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) } } }
        SettingsTitle("About"); Text("Affiliate ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"); Text("Task sync: ${if (state in setOf(ConnectionState.Online, ConnectionState.WaitingForPair)) "Online" else "Offline — cached work remains available"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
    if (editProfile) {
        var name by remember { mutableStateOf(app.store.friendlyName) }
        AlertDialog(onDismissRequest = { editProfile = false }, title = { Text("Edit profile") }, text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Display name") }, singleLine = true) }, confirmButton = { Button(enabled = name.trim().isNotEmpty(), onClick = { app.store.friendlyName = name.trim(); editProfile = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editProfile = false }) { Text("Cancel") } })
    }
}

@Composable private fun SettingsTitle(value: String) { HorizontalDivider(); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
@Composable private fun SettingsRow(title: String, detail: String, action: () -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) }, modifier = Modifier.clickable(onClick = action)) }
@Composable private fun PermissionSetting(title: String, ready: Boolean, action: () -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = { Text(if (ready) "Ready" else "Not enabled") }, trailingContent = { TextButton(onClick = action) { Text(if (ready) "Settings" else "Enable") } }) }
@Composable private fun AffiliateEmpty(title: String, detail: String) { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, style = MaterialTheme.typography.titleLarge); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun RowScope.WalletStat(label: String, value: String) { Card(Modifier.weight(1f)) { Column(Modifier.padding(14.dp)) { Text(label, style = MaterialTheme.typography.bodySmall); Text("$value coin", fontWeight = FontWeight.Bold) } } }

private fun label(field: TaskField) = field.label + if (field.required) " *" else ""
private fun displayValue(value: Any?): String = when (value) { is ProofValue -> value.name; is List<*> -> value.joinToString(); else -> value.toString() }
private fun valuesToJson(values: Map<String, Any?>): JsonObject = buildJsonObject { values.forEach { (key, value) -> when (value) { is String -> put(key, value); is Boolean -> put(key, value); is Number -> put(key, value.toDouble()); is List<*> -> put(key, JsonArray(value.filterIsInstance<String>().map(::JsonPrimitive))); is ProofValue -> put(key, buildJsonObject { put("name", value.name); put("mime", value.mime); put("size", value.size); put("sha256", value.sha256); put("file_id", value.fileId) }) } } }
private suspend fun readProof(context: android.content.Context, uri: Uri, maxSizeMb: Int): ProofValue = withContext(Dispatchers.IO) {
    var name = "proof"; var size = 0L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) { name = it.getString(0) ?: name; size = if (it.isNull(1)) 0 else it.getLong(1) } }
    require(size <= maxSizeMb * 1024L * 1024L) { "Proof must be under $maxSizeMb MB" }
    val digest = MessageDigest.getInstance("SHA-256"); context.contentResolver.openInputStream(uri)?.use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count <= 0) break; digest.update(buffer, 0, count) } }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "proof" }
    val proofDir = File(context.filesDir, "Affiliate/proofs").apply { mkdirs() }
    val stored = File(proofDir, "${hash.take(16)}_$safeName")
    context.contentResolver.openInputStream(uri)?.use { input -> stored.outputStream().use(input::copyTo) }
    if (stored.length() > maxSizeMb * 1024L * 1024L) { stored.delete(); error("Proof must be under $maxSizeMb MB") }
    val logical = "proofs/${stored.name}"
    ProofValue(name.take(160), context.contentResolver.getType(uri) ?: "application/octet-stream", size, hash, uri.toString(), FileIds.encodeAppPrivate(logical))
}
private fun shareLocalFile(context: android.content.Context) { val file = OwnerLinkApp.instance.affiliate.localData.file; if (!file.isFile) return; val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file); val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); context.startActivity(Intent.createChooser(intent, "Share local work record")) }
private fun statusColor(status: String) = when (status) { "APPROVED" -> Color(0xFF4CAF78); "REJECTED" -> Color(0xFFE56B6F); "REVISION_REQUESTED" -> Color(0xFFF4B942); else -> Color(0xFF8899C6) }
private fun earnedSince(items: List<com.ownerlink.agent.affiliate.AffiliateLedgerItem>, days: Long): String { val since = Instant.now().minus(days, ChronoUnit.DAYS); return String.format(Locale.US, "%.3f", items.filter { runCatching { Instant.parse(it.createdAt).isAfter(since) }.getOrDefault(false) }.sumOf { it.amount.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0 }).trimEnd('0').trimEnd('.') }
private fun formatSize(value: Long): String = if (value >= 1024 * 1024) "%.1f MB".format(value / 1048576.0) else if (value >= 1024) "%.1f KB".format(value / 1024.0) else "$value B"
