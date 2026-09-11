package com.ownerlink.agent.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.chat.ContactCard
import com.ownerlink.agent.chat.ContactCode
import com.ownerlink.agent.network.ConnectionState
import com.ownerlink.agent.pairing.PairingJson
import com.ownerlink.agent.pairing.QrEncoder
import com.ownerlink.agent.service.ConnectionService
import com.ownerlink.agent.util.Permissions

private enum class Tab { Chats, People, Settings }

@Composable fun OwnerLinkAppUi() {
    val app = OwnerLinkApp.instance
    var onboarded by remember { mutableStateOf(app.store.onboardingComplete) }
    LaunchedEffect(Unit) { ConnectionService.start(app) }
    Surface(Modifier.fillMaxSize()) {
        if (!onboarded) Onboarding { app.store.onboardingComplete = true; onboarded = true } else Messenger()
    }
}

@Composable private fun Onboarding(done: () -> Unit) {
    val app = OwnerLinkApp.instance
    var step by remember { mutableIntStateOf(if (app.store.friendlyName.isBlank()) 0 else 1) }
    var name by remember { mutableStateOf(app.store.friendlyName) }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PrivatChat", style = MaterialTheme.typography.headlineLarge)
        if (step == 0) {
            Text("Private conversations, directly connected.")
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Your name") })
            Text("Profile picture is optional and can be added later.")
            Button(enabled = name.trim().isNotEmpty(), onClick = { app.store.friendlyName = name.trim(); step = 1 }) { Text("Continue") }
        } else {
            Text("Recommended permissions")
            Text("Notifications keep messages visible. Camera and microphone are requested for calling; photos/files only when selected.")
            Button(onClick = { launcher.launch(buildList {
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO)
            }.toTypedArray()) }) { Text("Enable recommended permissions") }
            OutlinedButton(onClick = done) { Text("Continue to PrivatChat") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = android.net.Uri.parse("package:${context.packageName}") }) }) { Text("Open permission settings") }
        }
    }
}

@Composable private fun Messenger() {
    var tab by remember { mutableStateOf(Tab.Chats) }
    var active by remember { mutableStateOf<ContactCard?>(null) }
    Scaffold(bottomBar = { NavigationBar { Tab.entries.forEach { item ->
        NavigationBarItem(tab == item, { active = null; tab = item }, { Text(item.name.take(1)) }, label = { Text(item.name) })
    } } }) { padding -> Box(Modifier.padding(padding).fillMaxSize()) {
        when { active != null -> ChatScreen(active!!) { active = null }; tab == Tab.Chats -> ChatsScreen { active = it }; tab == Tab.People -> PeopleScreen { active = it }; else -> SettingsScreen() }
    } }
}

@Composable private fun ChatsScreen(open: (ContactCard) -> Unit) {
    val chat = OwnerLinkApp.instance.chat
    val generation by chat.generation.collectAsState()
    val contacts = remember(generation) { chat.contacts() }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Chats", style = MaterialTheme.typography.headlineMedium)
        if (contacts.isEmpty()) Text("No conversations yet. Add someone from People.")
        contacts.forEach { card -> ListItem({ Text(card.name) }, Modifier.clickable { open(card) }, supportingContent = { Text("Tap to open conversation") }) }
    }
}

@Composable private fun PeopleScreen(open: (ContactCard) -> Unit) {
    val app = OwnerLinkApp.instance
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val generation by app.chat.generation.collectAsState()
    var pasted by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    val mine = remember(app.store.friendlyName) { ContactCode.encode(ContactCard(app.identity.contactId, app.identity.publicKey, app.store.friendlyName)) }
    val qr = remember(mine) { QrEncoder.encode(mine) }
    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("People", style = MaterialTheme.typography.headlineMedium); Text("My QR")
        Image(qr.asImageBitmap(), "My PrivatChat contact QR", Modifier.fillMaxWidth().height(220.dp))
        Button(onClick = { clipboard.setText(AnnotatedString(mine)); Toast.makeText(context, "Contact code copied", Toast.LENGTH_SHORT).show() }) { Text("Copy My Code") }
        HorizontalDivider()
        OutlinedTextField(pasted, { pasted = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Paste contact code") }, minLines = 3)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { try { val card = ContactCode.parse(pasted); app.chat.addContact(card); pasted = ""; open(card) } catch (e: Exception) { error = e.message ?: "Invalid contact code" } }) { Text("Add contact") }
        Text("Contacts (${app.chat.contacts().size})", style = MaterialTheme.typography.titleMedium)
        app.chat.contacts().forEach { card -> ListItem({ Text(card.name) }, Modifier.clickable { open(card) }) }
    }
}

@Composable private fun ChatScreen(contact: ContactCard, onBack: () -> Unit) {
    val app = OwnerLinkApp.instance; val context = LocalContext.current
    val generation by app.chat.generation.collectAsState(); var text by remember { mutableStateOf("") }
    val messages = remember(generation, contact.contactId) { app.chat.messages(contact.contactId) }
    val attachment = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) Toast.makeText(context, "Attachment selected; peer transfer is not yet available", Toast.LENGTH_SHORT).show() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = onBack) { Text("Back") }; Text(contact.name, style = MaterialTheme.typography.titleLarge); TextButton(onClick = { Toast.makeText(context, "Video media is not yet available", Toast.LENGTH_SHORT).show() }) { Text("Video") } }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) { messages.forEach { m -> Text((if (m.outgoing) "You: " else "${contact.name}: ") + m.body + if (m.outgoing) "  ${m.state.name.lowercase()}" else "") } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TextButton(onClick = { attachment.launch(arrayOf("image/*", "application/pdf", "text/plain", "application/octet-stream")) }) { Text("+") }; OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }); Button(onClick = { app.chat.sendText(app.store.deviceId, contact.contactId, text); text = "" }) { Text("Send") } }
    }
}

@Composable private fun SettingsScreen() {
    val app = OwnerLinkApp.instance; val context = LocalContext.current; val clipboard = LocalClipboardManager.current
    val state by app.socket.state.collectAsState(); var showDashboard by remember { mutableStateOf(false) }
    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium); Text(app.store.friendlyName)
        Text("Connection: ${if (state == ConnectionState.Online || state == ConnectionState.WaitingForPair) "online" else state.name.lowercase()}")
        Text("Contact ID: ${app.identity.contactId}", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { showDashboard = !showDashboard }) { Text("Connect with Dashboard") }
        if (showDashboard) { val payload = remember(app.store.pairingToken, app.store.paired) { PairingJson.encode(app.store) }; val qr = remember(payload) { QrEncoder.encode(payload) }; Text("This one-time code grants dashboard access. Never share it as a contact code."); Image(qr.asImageBitmap(), "Dashboard pairing QR", Modifier.fillMaxWidth().height(220.dp)); OutlinedButton(onClick = { clipboard.setText(AnnotatedString(payload)); Toast.makeText(context, "Dashboard code copied", Toast.LENGTH_SHORT).show() }) { Text("Copy dashboard code") } }
        HorizontalDivider(); Text("Dashboard permissions")
        Text("Gallery: ${if (Permissions.hasMedia(context)) "ready" else "needs permission"}"); Text("Files: ${if (Permissions.hasAllFiles()) "ready" else "needs folder/all-files permission"}"); Text("Remote screen: ${if (Permissions.accessibilityEnabled(context)) "ready" else "needs Accessibility"}")
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility settings") }; TextButton(onClick = { Permissions.openAppSettings(context) }) { Text("App permission settings") }
    }
}
