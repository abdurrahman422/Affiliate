package com.ownerlink.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.chat.ChatMessage
import com.ownerlink.agent.chat.ContactCard
import com.ownerlink.agent.chat.ContactCode
import com.ownerlink.agent.chat.MessageState
import com.ownerlink.agent.network.ConnectionState
import com.ownerlink.agent.pairing.PairingJson
import com.ownerlink.agent.pairing.QrEncoder
import com.ownerlink.agent.rtc.CallPhase
import com.ownerlink.agent.rtc.CallUiState
import com.ownerlink.agent.service.ConnectionService
import com.ownerlink.agent.util.Permissions
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.text.DateFormat
import java.util.Date

private enum class Tab { Chats, People, Settings }

@Composable
fun OwnerLinkAppUi() {
    val app = OwnerLinkApp.instance
    var onboarded by remember { mutableStateOf(app.store.onboardingComplete) }
    LaunchedEffect(Unit) { ConnectionService.start(app) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (onboarded) Messenger() else Onboarding { app.store.onboardingComplete = true; onboarded = true }
    }
}

@Composable
private fun Onboarding(done: () -> Unit) {
    val app = OwnerLinkApp.instance
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(app.store.friendlyName) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            app.store.profileUri = uri.toString()
        }
        step = 2
    }
    when (step) {
        0 -> SetupPage("Welcome to PrivatChat", "Private conversations for people you trust.") {
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true)
            Button(enabled = name.trim().length in 1..80, onClick = { app.store.friendlyName = name.trim(); step = 1 }) { Text("Continue") }
        }
        1 -> SetupPage("Add a profile picture", "This is optional and stays on your phone unless you share it with a contact.") {
            Button(onClick = { photo.launch(arrayOf("image/*")) }) { Text("Choose Photo") }
            OutlinedButton(onClick = { step = 2 }) { Text("Skip") }
        }
        else -> PermissionSetup(done)
    }
}

@Composable
private fun SetupPage(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp)); Text(subtitle); Spacer(Modifier.height(28.dp)); content()
    }
}

@Composable
private fun PermissionSetup(done: () -> Unit) {
    val context = LocalContext.current
    var detailed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    fun requestRecommended() {
        runtime.launch(buildList {
            if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.POST_NOTIFICATIONS); add(Manifest.permission.READ_MEDIA_IMAGES); add(Manifest.permission.READ_MEDIA_VIDEO) }
            else add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO)
        }.toTypedArray())
    }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Complete setup", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Enable the permissions you want PrivatChat to use for messages, calls, files, and optional dashboard access.")
        Button(onClick = { requestRecommended(); detailed = true }) { Text("Enable All Recommended Permissions") }
        OutlinedButton(onClick = { detailed = true }) { Text("Set Up Individually") }
        if (detailed) {
            PermissionRow("Notifications", Permissions.hasNotifications(context), "Message and call alerts") { runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
            PermissionRow("Photos & Videos", Permissions.hasMedia(context), "Sending media and dashboard Gallery") { runtime.launch(Permissions.mediaPermissions()) }
            PermissionRow("Camera", granted(context, Manifest.permission.CAMERA), "QR scanning and accepted video calls") { runtime.launch(arrayOf(Manifest.permission.CAMERA)) }
            PermissionRow("Microphone", granted(context, Manifest.permission.RECORD_AUDIO), "Accepted video calls") { runtime.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
            PermissionRow("Files", Permissions.hasAllFiles(), "Optional dashboard file browsing") { Permissions.openAllFilesSettings(context) }
            PermissionRow("Remote Screen", Permissions.accessibilityEnabled(context), "Optional dashboard screen control") { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            PermissionRow("Background reliability", Permissions.batteryOptimizationIgnored(context), "More reliable incoming delivery") { Permissions.openBatterySettings(context) }
        }
        TextButton(onClick = done) { Text("Continue with Limited Access") }
    }
}

@Composable
private fun PermissionRow(name: String, ready: Boolean, reason: String, action: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold); Text(reason, style = MaterialTheme.typography.bodySmall); Text(if (ready) "Ready" else "Not enabled", color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        TextButton(onClick = action) { Text(if (ready) "Settings" else "Enable") }
    } }
}

@Composable
private fun Messenger() {
    val app = OwnerLinkApp.instance
    val call by app.rtc.call.collectAsState()
    var tab by remember { mutableStateOf(Tab.Chats) }
    var active by remember { mutableStateOf<ContactCard?>(null) }
    Scaffold(bottomBar = { if (active == null && call.phase !in setOf(CallPhase.INCOMING, CallPhase.CONNECTING, CallPhase.ACTIVE)) NavigationBar {
        Tab.entries.forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Text(item.name.take(1)) }, label = { Text(item.name) }) }
    } }) { padding -> Box(Modifier.padding(padding).fillMaxSize()) {
        when {
            call.phase in setOf(CallPhase.INCOMING, CallPhase.OUTGOING, CallPhase.CONNECTING, CallPhase.ACTIVE, CallPhase.FAILED) -> CallScreen(call)
            active != null -> ChatScreen(active!!) { active = null }
            tab == Tab.Chats -> ChatsScreen { active = it }
            tab == Tab.People -> PeopleScreen { active = it }
            else -> SettingsScreen()
        }
    } }
}

@Composable
private fun ChatsScreen(open: (ContactCard) -> Unit) {
    val chat = OwnerLinkApp.instance.chat
    val generation by chat.generation.collectAsState()
    val contacts = remember(generation) { chat.contacts() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Chats", Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        if (contacts.isEmpty()) item { EmptyState("No conversations yet", "Add someone from People to start chatting.") }
        items(contacts, key = { it.contactId }) { card ->
            val messages = chat.messages(card.contactId); val last = messages.lastOrNull(); val unread = messages.count { !it.outgoing && it.state == MessageState.DELIVERED }
            ListItem(
                headlineContent = { Text(card.name, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(last?.let { if (it.kind == "text") it.body else "Attachment: ${it.attachmentName}" } ?: "Start a conversation", maxLines = 1) },
                leadingContent = { Avatar(card.name) },
                trailingContent = { Column(horizontalAlignment = Alignment.End) { last?.let { Text(shortTime(it.createdAt), style = MaterialTheme.typography.labelSmall) }; if (unread > 0) AssistChip(onClick = {}, label = { Text(unread.toString()) }) } },
                modifier = Modifier.clickable { open(card) },
            )
        }
    }
}

@Composable
private fun PeopleScreen(open: (ContactCard) -> Unit) {
    val app = OwnerLinkApp.instance
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val generation by app.chat.generation.collectAsState()
    var pasted by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    val mine = remember(app.store.friendlyName) { ContactCode.encode(ContactCard(app.identity.contactId, app.identity.publicKey, app.store.friendlyName)) }
    val qr = remember(mine) { QrEncoder.encode(mine) }
    fun add(raw: String) { runCatching { ContactCode.parse(raw) }.onSuccess { app.chat.addContact(it); open(it) }.onFailure { error = it.message ?: "Invalid contact code" } }
    val scanner = remember { GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()) }
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("People", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("My PrivatChat ID", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) { ProfileAvatar(app.store.friendlyName); Spacer(Modifier.size(10.dp)); Column { Text(app.store.friendlyName, fontWeight = FontWeight.SemiBold); Text(app.identity.contactId, style = MaterialTheme.typography.labelSmall) } }
        Image(qr.asImageBitmap(), "My contact QR", Modifier.fillMaxWidth().height(210.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(mine)); toast(context, "Contact code copied") }) { Text("Copy My Code") }
            OutlinedButton(onClick = { val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, mine) }; context.startActivity(Intent.createChooser(intent, "Share PrivatChat code")) }) { Text("Share") }
        }
        HorizontalDivider(); Text("Add People", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { scanner.startScan().addOnSuccessListener { it.rawValue?.let(::add) }.addOnFailureListener { error = "QR scanner is unavailable. Paste the code instead." } }) { Text("Scan QR") }
        OutlinedTextField(pasted, { pasted = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Paste code") }, minLines = 2)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = pasted.isNotBlank(), onClick = { add(pasted) }) { Text("Add Code") }
        HorizontalDivider(); Text("Contacts", style = MaterialTheme.typography.titleLarge)
        app.chat.contacts().forEach { card -> ListItem(headlineContent = { Text(card.name) }, leadingContent = { Avatar(card.name) }, modifier = Modifier.clickable { open(card) }) }
    }
}

@Composable
private fun ChatScreen(contact: ContactCard, onBack: () -> Unit) {
    val app = OwnerLinkApp.instance; val context = LocalContext.current
    val generation by app.chat.generation.collectAsState(); val presence by app.chat.presence.collectAsState()
    var text by remember { mutableStateOf("") }; var attachmentMenu by remember { mutableStateOf(false) }
    val messages = remember(generation, contact.contactId) { app.chat.messages(contact.contactId) }
    LaunchedEffect(contact.contactId) { app.chat.markRead(contact.contactId); app.rtc.ensureDataSession(contact.contactId) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val isImage = context.contentResolver.getType(uri)?.startsWith("image/") == true
            app.chat.sendAttachment(contact.contactId, uri, isImage).onFailure { toast(context, it.message ?: "Unable to send attachment") }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }; Avatar(contact.name); Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) { Text(contact.name, fontWeight = FontWeight.Bold); Text(if (presence[contact.contactId] == true) "Online" else "Offline — messages will retry", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { app.rtc.startCall(contact.contactId, contact.name) }) { Text("Video Call") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { message -> MessageBubble(message) { app.chat.retryMessage(message.id, contact.contactId) } }
        }
        if (attachmentMenu) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { attachmentMenu = false; picker.launch(arrayOf("image/*")) }, label = { Text("Photo") })
            AssistChip(onClick = { attachmentMenu = false; picker.launch(arrayOf("application/pdf", "text/plain", "application/zip", "application/octet-stream")) }, label = { Text("Document/File") })
        }
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { attachmentMenu = !attachmentMenu }) { Text("+") }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4)
            Button(enabled = text.trim().isNotEmpty(), onClick = { app.chat.sendText(contact.contactId, text); text = "" }) { Text("Send") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.82f)) { Column(Modifier.padding(10.dp)) {
            if (message.kind == "image") Text("Photo: ${message.attachmentName ?: message.body}", fontWeight = FontWeight.SemiBold)
            else if (message.kind == "file") Text("Document: ${message.attachmentName ?: message.body}", fontWeight = FontWeight.SemiBold)
            else Text(message.body)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Text(shortTime(message.createdAt), style = MaterialTheme.typography.labelSmall); if (message.outgoing) Text(" · ${message.state.name.lowercase()}", style = MaterialTheme.typography.labelSmall) }
            if (message.progress in 1..99) CircularProgressIndicator(progress = { message.progress / 100f }, Modifier.size(22.dp))
            if (message.state == MessageState.FAILED || message.state == MessageState.PENDING) TextButton(onClick = retry) { Text("Retry") }
        } }
    }
}

@Composable
private fun CallScreen(call: CallUiState) {
    val app = OwnerLinkApp.instance; val context = LocalContext.current
    val local by app.rtc.localVideo.collectAsState(); val remote by app.rtc.remoteVideo.collectAsState()
    var acceptAfterPermission by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> if (acceptAfterPermission && result.values.all { it }) app.rtc.acceptCall() else toast(context, "Camera and microphone permissions are required"); acceptAfterPermission = false }
    if (call.phase == CallPhase.INCOMING) LaunchedEffect(call.callId) { app.rtc.ringIncoming() }
    Box(Modifier.fillMaxSize().background(Color(0xFF101318))) {
        if (remote != null && call.phase == CallPhase.ACTIVE) VideoSurface(remote, Modifier.fillMaxSize()) else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Avatar(call.contactName, 88); Spacer(Modifier.height(14.dp)); Text(call.contactName, color = Color.White, style = MaterialTheme.typography.headlineMedium); Text(callLabel(call), color = Color.LightGray); call.error?.let { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(20.dp)) } }
        if (local != null && call.phase in setOf(CallPhase.CONNECTING, CallPhase.ACTIVE)) VideoSurface(local, Modifier.align(Alignment.TopEnd).padding(16.dp).size(120.dp, 170.dp))
        Row(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (call.phase) {
                CallPhase.INCOMING -> { Button(onClick = app.rtc::declineCall) { Text("Decline") }; Button(onClick = { if (granted(context, Manifest.permission.CAMERA) && granted(context, Manifest.permission.RECORD_AUDIO)) app.rtc.acceptCall() else { acceptAfterPermission = true; permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) } }) { Text("Accept") } }
                CallPhase.ACTIVE, CallPhase.CONNECTING -> { OutlinedButton(onClick = app.rtc::toggleMute) { Text(if (call.muted) "Unmute" else "Mute") }; OutlinedButton(onClick = app.rtc::switchCamera) { Text("Switch") }; Button(onClick = app.rtc::endCall) { Text("End") } }
                CallPhase.OUTGOING -> Button(onClick = app.rtc::endCall) { Text("Cancel") }
                CallPhase.FAILED -> Button(onClick = app.rtc::endCall) { Text("Close") }
                else -> Unit
            }
        }
    }
}

@Composable
private fun VideoSurface(track: VideoTrack?, modifier: Modifier) {
    val egl = OwnerLinkApp.instance.rtc.eglBase.eglBaseContext
    AndroidView(
        factory = { SurfaceViewRenderer(it).apply { init(egl, null); setMirror(false); track?.addSink(this) } },
        modifier = modifier,
        onRelease = { view -> track?.removeSink(view); view.release() },
        update = { view -> track?.addSink(view) },
    )
}

@Composable
private fun SettingsScreen() {
    val app = OwnerLinkApp.instance; val context = LocalContext.current; val clipboard = LocalClipboardManager.current
    val state by app.socket.state.collectAsState(); var showDashboard by remember { mutableStateOf(false) }; var editProfile by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Profile", style = MaterialTheme.typography.titleLarge); ListItem(headlineContent = { Text(app.store.friendlyName) }, supportingContent = { Text("Edit name and profile picture") }, leadingContent = { ProfileAvatar(app.store.friendlyName) }, modifier = Modifier.clickable { editProfile = true })
        Text("Account / Identity", style = MaterialTheme.typography.titleLarge); Text("PrivatChat ID: ${app.identity.contactId}", style = MaterialTheme.typography.bodySmall)
        Text("Messaging connection: ${connectionLabel(state)}")
        HorizontalDivider(); Text("Dashboard", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { showDashboard = !showDashboard }) { Text(if (app.store.paired) "Dashboard Connected" else "Connect with Dashboard") }
        if (showDashboard) DashboardPanel()
        HorizontalDivider(); Text("Device Access", style = MaterialTheme.typography.titleLarge)
        PermissionRow("Gallery", Permissions.hasMedia(context), "Dashboard photo/video access") { Permissions.openAppSettings(context) }
        PermissionRow("Files", Permissions.hasAllFiles(), "Dashboard file browsing") { Permissions.openAllFilesSettings(context) }
        PermissionRow("Remote Screen", Permissions.accessibilityEnabled(context), "Dashboard screen/control") { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        HorizontalDivider(); Text("About", style = MaterialTheme.typography.titleLarge); Text("PrivatChat 1.0 · Private peer-to-peer communication")
    }
    if (editProfile) ProfileDialog { editProfile = false }
}

@Composable
private fun DashboardPanel() {
    val app = OwnerLinkApp.instance; val clipboard = LocalClipboardManager.current; val context = LocalContext.current
    if (app.store.paired) {
        Text("Connected to Dashboard"); Text("Device: ${app.store.friendlyName}")
        OutlinedButton(onClick = { app.socket.disconnectDashboard() }) { Text("Disconnect Dashboard") }
    } else {
        val payload = remember(app.store.pairingToken) { PairingJson.encode(app.store) }; val qr = remember(payload) { QrEncoder.encode(payload) }
        Text("This one-time code grants dashboard access. Never share it as a contact code.")
        Image(qr.asImageBitmap(), "Dashboard pairing QR", Modifier.fillMaxWidth().height(220.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(payload)); toast(context, "Dashboard code copied") }) { Text("Copy Dashboard Code") }
            TextButton(onClick = { app.store.regeneratePairingToken(); app.socket.disconnect(false); app.socket.retryNow() }) { Text("Regenerate") }
        }
    }
}

@Composable
private fun ProfileDialog(close: () -> Unit) {
    val app = OwnerLinkApp.instance; val context = LocalContext.current; var name by remember { mutableStateOf(app.store.friendlyName) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; app.store.profileUri = uri.toString() } }
    AlertDialog(onDismissRequest = close, title = { Text("Edit profile") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }); OutlinedButton(onClick = { photo.launch(arrayOf("image/*")) }) { Text("Choose Profile Photo") } } }, confirmButton = { Button(enabled = name.trim().isNotEmpty(), onClick = { app.store.friendlyName = name.trim(); close() }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable private fun Avatar(name: String, size: Int = 48) { Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) { Text(name.trim().take(1).uppercase().ifBlank { "P" }, fontWeight = FontWeight.Bold) } }
@Composable private fun ProfileAvatar(name: String, size: Int = 48) {
    val context = LocalContext.current; val uri = OwnerLinkApp.instance.store.profileUri
    val bitmap = remember(uri) { uri?.let { runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(it))) }.getOrNull() } }
    if (bitmap == null) Avatar(name, size) else Image(bitmap.asImageBitmap(), "Profile picture", Modifier.size(size.dp).clip(CircleShape), contentScale = ContentScale.Crop)
}
@Composable private fun EmptyState(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, style = MaterialTheme.typography.titleLarge); Text(subtitle) } }
private fun granted(context: android.content.Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
private fun toast(context: android.content.Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
private fun shortTime(time: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
private fun connectionLabel(state: ConnectionState) = when (state) { ConnectionState.Online, ConnectionState.WaitingForPair -> "Online"; ConnectionState.Connecting -> "Connecting"; else -> "Offline — retrying automatically" }
private fun callLabel(call: CallUiState) = when (call.phase) { CallPhase.INCOMING -> "Incoming video call"; CallPhase.OUTGOING -> "Calling…"; CallPhase.CONNECTING -> "Connecting video…"; CallPhase.ACTIVE -> "Connected"; CallPhase.FAILED -> "Call failed"; else -> "Call ended" }
