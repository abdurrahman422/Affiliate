package com.ownerlink.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.util.Size
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.BuildConfig
import com.ownerlink.agent.R
import com.ownerlink.agent.chat.ChatMessage
import com.ownerlink.agent.chat.ContactCard
import com.ownerlink.agent.chat.MessageState
import com.ownerlink.agent.pairing.QrEncoder
import com.ownerlink.agent.rtc.CallPhase
import com.ownerlink.agent.rtc.CallUiState
import com.ownerlink.agent.service.ConnectionService
import com.ownerlink.agent.ui.theme.ThemeMode
import com.ownerlink.agent.util.Permissions
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.text.DateFormat
import java.util.Date
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tab { Chats, People, Settings }

@Composable
fun OwnerLinkAppUi() {
    val app = OwnerLinkApp.instance
    var onboarded by remember {
        mutableStateOf(app.store.onboardingComplete && app.store.firstName.isNotBlank() &&
            app.store.lastName.isNotBlank() && app.store.nickname.isNotBlank() &&
            app.store.dateOfBirth.isNotBlank() && app.store.phoneNumber.isNotBlank() && app.store.profileUri != null)
    }
    LaunchedEffect(Unit) { ConnectionService.start(app) }
    Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
        if (onboarded) Messenger() else Onboarding { app.store.onboardingComplete = true; onboarded = true }
    }
}

@Composable
private fun Onboarding(done: () -> Unit) {
    val app = OwnerLinkApp.instance
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var firstName by remember { mutableStateOf(app.store.firstName) }
    var lastName by remember { mutableStateOf(app.store.lastName) }
    var nickname by remember { mutableStateOf(app.store.nickname.ifBlank { app.store.friendlyName }) }
    var dateOfBirth by remember { mutableStateOf(app.store.dateOfBirth) }
    var phone by remember { mutableStateOf(app.store.phoneNumber) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            app.store.profileUri = uri.toString()
        }
        step = 5
    }
    val validDob = dateOfBirth.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
    AnimatedContent(
        targetState = step,
        transitionSpec = {
            (slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(320))) togetherWith
                (slideOutHorizontally(tween(320)) { -it / 3 } + fadeOut(tween(220)))
        },
        label = "profile-onboarding",
    ) { current ->
        when (current) {
            0 -> SetupPage(current, 6, "Let’s start with your name", "This is how your profile begins.", Icons.Rounded.PersonOutline) {
                OutlinedTextField(firstName, { firstName = it.take(50) }, Modifier.fillMaxWidth(), label = { Text("First name") }, singleLine = true)
                OutlinedTextField(lastName, { lastName = it.take(50) }, Modifier.fillMaxWidth(), label = { Text("Last name") }, singleLine = true)
                PremiumContinue(firstName.trim().isNotEmpty() && lastName.trim().isNotEmpty()) { app.store.firstName = firstName.trim(); app.store.lastName = lastName.trim(); step = 1 }
            }
            1 -> SetupPage(current, 6, "Choose your nickname", "Friends will see this name in conversations.", Icons.Rounded.Badge, back = { step = 0 }) {
                OutlinedTextField(nickname, { nickname = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Nickname / username") }, singleLine = true)
                PremiumContinue(nickname.trim().length >= 2) { app.store.nickname = nickname.trim(); app.store.friendlyName = nickname.trim(); step = 2 }
            }
            2 -> SetupPage(current, 6, "Your date of birth", "Enter it in YYYY-MM-DD format.", Icons.Rounded.Cake, back = { step = 1 }) {
                OutlinedTextField(dateOfBirth, { dateOfBirth = it.filter { ch -> ch.isDigit() || ch == '-' }.take(10) }, Modifier.fillMaxWidth(), label = { Text("Date of birth") }, placeholder = { Text("1998-12-31") }, singleLine = true)
                PremiumContinue(validDob) { app.store.dateOfBirth = dateOfBirth; step = 3 }
            }
            3 -> SetupPage(current, 6, "Add your phone number", "Used only as part of this device profile.", Icons.Rounded.PhoneAndroid, back = { step = 2 }) {
                OutlinedTextField(phone, { phone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(20) }, Modifier.fillMaxWidth(), label = { Text("Phone number") }, placeholder = { Text("+880…") }, singleLine = true)
                PremiumContinue(phone.filter(Char::isDigit).length >= 7) { app.store.phoneNumber = phone.trim(); step = 4 }
            }
            4 -> SetupPage(current, 6, "Make it yours", "Choose one clear profile photo.", Icons.Rounded.PhotoCamera, back = { step = 3 }) {
                Button(onClick = { photo.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Icon(Icons.Rounded.PhotoCamera, null); Spacer(Modifier.size(10.dp)); Text("Choose profile photo") }
            }
            else -> PermissionSetup(done)
        }
    }
}

@Composable
private fun SetupPage(step: Int, total: Int, title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, back: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val pulse by rememberInfiniteTransition(label = "onboarding-glow").animateFloat(0.72f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f), MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (back != null) TextButton(onClick = back) { Text("Back") } else Spacer(Modifier.size(64.dp))
                Spacer(Modifier.weight(1f)); Text("${step + 1} / $total", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(total) { index -> Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) } }
            Spacer(Modifier.weight(.35f))
            Box(Modifier.size(82.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF8A4DFF)))).border(1.dp, Color.White.copy(alpha = .35f * pulse), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(40.dp), tint = Color.White) }
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)), elevation = CardDefaults.cardElevation(8.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.size(8.dp)); Text("Private by design · stored on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun PremiumContinue(enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Continue", Modifier.weight(1f)); Icon(Icons.Rounded.ArrowForward, null) }
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
        PrivacyChatMark(64)
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Choose what Privacy Chat may use. You stay in control and can change these later in Android settings.")
        Button(onClick = { OwnerLinkApp.instance.store.permissionsSeen = true; requestRecommended(); detailed = true }) { Text(stringResource(R.string.enable_recommended)) }
        OutlinedButton(onClick = { detailed = true }) { Text(stringResource(R.string.setup_individually)) }
        if (detailed) {
            PermissionRow("Notifications", Permissions.hasNotifications(context), "Message and call alerts") { runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
            PermissionRow("Photos & Videos", Permissions.hasMedia(context), "Sending media and dashboard Gallery") { runtime.launch(Permissions.mediaPermissions()) }
            PermissionRow("Camera", granted(context, Manifest.permission.CAMERA), "QR scanning and accepted video calls") { runtime.launch(arrayOf(Manifest.permission.CAMERA)) }
            PermissionRow("Microphone", granted(context, Manifest.permission.RECORD_AUDIO), "Accepted video calls") { runtime.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
            PermissionRow("Files", Permissions.hasAllFiles(), "Access files you choose to make available") { Permissions.openAllFilesSettings(context) }
            PermissionRow("Background reliability", Permissions.batteryOptimizationIgnored(context), "More reliable incoming delivery") { Permissions.openBatterySettings(context) }
        }
        TextButton(onClick = done) { Text(stringResource(R.string.limited_access)) }
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
    Scaffold(bottomBar = { if (active == null && call.phase !in setOf(CallPhase.INCOMING, CallPhase.CONNECTING, CallPhase.RECONNECTING, CallPhase.ACTIVE)) NavigationBar {
        Tab.entries.forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = {
            Icon(when (item) { Tab.Chats -> Icons.Rounded.ChatBubbleOutline; Tab.People -> Icons.Rounded.Groups; Tab.Settings -> Icons.Rounded.Settings }, contentDescription = item.name)
        }, label = { Text(item.name) }) }
    } }) { padding -> Box(Modifier.padding(padding).fillMaxSize()) {
        when {
            call.phase in setOf(CallPhase.INCOMING, CallPhase.OUTGOING, CallPhase.CONNECTING, CallPhase.RECONNECTING, CallPhase.ACTIVE, CallPhase.FAILED) -> CallScreen(call)
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
        item { Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            PrivacyChatMark(46); Spacer(Modifier.size(12.dp)); Text(if (BuildConfig.IS_MASTER) "Privacy Chat Master" else "Privacy Chat", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            ProfileAvatar(OwnerLinkApp.instance.store.friendlyName, 42)
        } }
        if (contacts.isEmpty()) item { EmptyState(stringResource(R.string.no_conversations), stringResource(R.string.add_someone)) }
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
    val contactResult by app.chat.contactResult.collectAsState()
    var pasted by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    val mine = app.identity.contactId
    val qr = remember(mine) { QrEncoder.encode(mine) }
    fun add(raw: String) { error = null; app.chat.addContactCode(raw) }
    val scanner = remember { GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()) }
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("People", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("My Privacy Chat Code", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) { ProfileAvatar(app.store.friendlyName); Spacer(Modifier.size(10.dp)); Column { Text(app.store.friendlyName, fontWeight = FontWeight.SemiBold); Text(app.identity.contactId, style = MaterialTheme.typography.labelSmall) } }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Image(qr.asImageBitmap(), "My contact QR code", Modifier.fillMaxWidth().height(230.dp).padding(14.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(mine)); toast(context, "Contact code copied") }) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.size(8.dp)); Text("Copy My Code") }
            OutlinedButton(onClick = { val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, mine) }; context.startActivity(Intent.createChooser(intent, "Share Privacy Chat code")) }) { Text("Share") }
        }
        HorizontalDivider(); Text("Add People", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { scanner.startScan().addOnSuccessListener { it.rawValue?.let(::add) }.addOnFailureListener { error = "QR scanner is unavailable. Paste the code instead." } }) { Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text("Scan QR") }
        OutlinedTextField(pasted, { pasted = it.trim().take(22); error = null; app.chat.clearContactResult() }, Modifier.fillMaxWidth(), label = { Text("22-character code") }, singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        contactResult?.let { Text(it, color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(enabled = pasted.isNotBlank(), onClick = { add(pasted) }) { Text("Add Code") }
        HorizontalDivider(); Text("Contacts", style = MaterialTheme.typography.titleLarge)
        app.chat.contacts().forEach { card -> ListItem(headlineContent = { Text(card.name) }, supportingContent = { Text("Open conversation") }, leadingContent = { Avatar(card.name) }, trailingContent = { TextButton(onClick = { app.chat.removeContact(card.contactId) }) { Text("Remove") } }, modifier = Modifier.clickable { open(card) }) }
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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }; Avatar(contact.name); Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) { Text(contact.name, fontWeight = FontWeight.Bold); Text(if (presence[contact.contactId] == true) "Online" else "Offline — messages will retry", style = MaterialTheme.typography.labelSmall) }
            IconButton(onClick = { app.rtc.startCall(contact.contactId, contact.name, false) }) { Icon(Icons.Rounded.Call, "Start audio call") }
            IconButton(onClick = { app.rtc.startCall(contact.contactId, contact.name, true) }) { Icon(Icons.Rounded.Videocam, "Start video call") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { message -> MessageBubble(message, { app.chat.retryMessage(message.id, contact.contactId) }, { app.chat.cancelAttachment(message.id) }) }
        }
        if (attachmentMenu) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { attachmentMenu = false; picker.launch(arrayOf("image/*")) }, label = { Text("Photo") })
            AssistChip(onClick = { attachmentMenu = false; picker.launch(arrayOf("application/pdf", "text/plain", "application/zip", "application/octet-stream")) }, label = { Text("Document/File") })
        }
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { attachmentMenu = !attachmentMenu }) { Icon(Icons.Rounded.AttachFile, stringResource(R.string.attach_content)) }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.message_hint)) }, maxLines = 4)
            IconButton(enabled = text.trim().isNotEmpty(), onClick = { app.chat.sendText(contact.contactId, text); text = "" }) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.send_message), tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, retry: () -> Unit, cancel: () -> Unit) {
    val context = LocalContext.current
    val preview = if (message.kind == "image") rememberThumbnail(message.attachmentUri, 720) else null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
        val bubble = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        Card(colors = CardDefaults.cardColors(containerColor = bubble), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(0.82f)) { Column(Modifier.padding(10.dp)) {
            if (message.kind == "image") {
                if (preview != null) Image(preview.asImageBitmap(), message.attachmentName ?: "Shared photo", Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).clickable { openAttachment(context, message) }, contentScale = ContentScale.Crop)
                else Text("Photo: ${message.attachmentName ?: message.body}", fontWeight = FontWeight.SemiBold)
            }
            else if (message.kind == "file") Row(Modifier.fillMaxWidth().clickable { openAttachment(context, message) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, null); Spacer(Modifier.size(10.dp)); Column { Text(message.attachmentName ?: message.body, fontWeight = FontWeight.SemiBold); message.attachmentSize?.let { Text(formatBytes(it), style = MaterialTheme.typography.labelSmall) } }
            }
            else Text(message.body)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Text(shortTime(message.createdAt), style = MaterialTheme.typography.labelSmall); if (message.outgoing) Text(" · ${message.state.name.lowercase()}", style = MaterialTheme.typography.labelSmall) }
            if (message.progress in 1..99) CircularProgressIndicator(progress = { message.progress / 100f }, Modifier.size(22.dp))
            if (message.state == MessageState.FAILED || message.state == MessageState.PENDING) TextButton(onClick = retry) { Text("Retry") }
            if (message.kind != "text" && message.outgoing && message.state in setOf(MessageState.PENDING, MessageState.SENDING)) TextButton(onClick = cancel) { Text("Cancel transfer") }
        } }
    }
}

@Composable
private fun CallScreen(call: CallUiState) {
    val app = OwnerLinkApp.instance; val context = LocalContext.current
    val local by app.rtc.localVideo.collectAsState(); val remote by app.rtc.remoteVideo.collectAsState()
    var acceptAfterPermission by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> if (acceptAfterPermission && result.values.all { it }) app.rtc.acceptCall() else toast(context, if (call.video) "Camera and microphone permissions are required" else "Microphone permission is required"); acceptAfterPermission = false }
    if (call.phase == CallPhase.INCOMING) LaunchedEffect(call.callId) { app.rtc.ringIncoming() }
    Box(Modifier.fillMaxSize().background(Color(0xFF101318))) {
        if (call.video && remote != null && call.phase == CallPhase.ACTIVE) VideoSurface(remote, Modifier.fillMaxSize()) else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Avatar(call.contactName, 88); Spacer(Modifier.height(14.dp)); Text(call.contactName, color = Color.White, style = MaterialTheme.typography.headlineMedium); Text(callLabel(call), color = Color.LightGray); call.error?.let { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(20.dp)) } }
        if (call.video && local != null && call.phase in setOf(CallPhase.CONNECTING, CallPhase.RECONNECTING, CallPhase.ACTIVE)) VideoSurface(local, Modifier.align(Alignment.TopEnd).padding(16.dp).size(120.dp, 170.dp))
        Row(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (call.phase) {
                CallPhase.INCOMING -> { Button(onClick = app.rtc::declineCall) { Text("Decline") }; Button(onClick = { val required = if (call.video) arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) else arrayOf(Manifest.permission.RECORD_AUDIO); if (required.all { granted(context, it) }) app.rtc.acceptCall() else { acceptAfterPermission = true; permissions.launch(required) } }) { Text("Accept") } }
                CallPhase.ACTIVE, CallPhase.CONNECTING, CallPhase.RECONNECTING -> { OutlinedButton(onClick = app.rtc::toggleMute) { Text(if (call.muted) "Unmute" else "Mute") }; OutlinedButton(onClick = app.rtc::switchCamera) { Text("Switch") }; Button(onClick = app.rtc::endCall) { Text("End") } }
                CallPhase.OUTGOING -> Button(onClick = app.rtc::endCall) { Text("Cancel") }
                CallPhase.FAILED -> Button(onClick = app.rtc::endCall) { Text("Close") }
                else -> Unit
            }
        }
    }
}

@Composable
private fun VideoSurface(track: VideoTrack?, modifier: Modifier) {
    track ?: return
    val egl = OwnerLinkApp.instance.rtc.eglBase.eglBaseContext
    key(track.id()) {
        AndroidView(
            factory = { SurfaceViewRenderer(it).apply { init(egl, null); setMirror(false); track.addSink(this) } },
            modifier = modifier,
            onRelease = { view -> track.removeSink(view); view.release() },
        )
    }
}

@Composable
private fun SettingsScreen() {
    val app = OwnerLinkApp.instance
    val appearance by app.appearance.collectAsState()
    var editProfile by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), MaterialTheme.colorScheme.background))).padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { PrivacyChatMark(48); Spacer(Modifier.size(12.dp)); Column { Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text("Simple, private, yours", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Card(Modifier.fillMaxWidth().clickable { editProfile = true }, shape = RoundedCornerShape(26.dp), elevation = CardDefaults.cardElevation(6.dp)) {
            Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { ProfileAvatar(app.store.friendlyName, 62); Spacer(Modifier.size(16.dp)); Column(Modifier.weight(1f)) { Text(app.store.friendlyName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("${app.store.firstName} ${app.store.lastName}", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Edit profile", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }; Icon(Icons.Rounded.ArrowForward, null) }
        }
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeMode.entries.forEach { mode ->
            if (appearance == mode) Button(onClick = { app.setAppearance(mode) }) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) }
            else OutlinedButton(onClick = { app.setAppearance(mode) }) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) }
        } }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f))) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.size(8.dp)); Text("Privacy", style = MaterialTheme.typography.titleLarge) }; Text(stringResource(R.string.privacy_summary), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Text("About", style = MaterialTheme.typography.titleLarge); Text("${if (BuildConfig.IS_MASTER) "Privacy Chat Master" else "Privacy Chat"} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (editProfile) ProfileDialog { editProfile = false }
}

@Composable
private fun ProfileDialog(close: () -> Unit) {
    val app = OwnerLinkApp.instance; val context = LocalContext.current
    var first by remember { mutableStateOf(app.store.firstName) }; var last by remember { mutableStateOf(app.store.lastName) }
    var nickname by remember { mutableStateOf(app.store.nickname) }; var phone by remember { mutableStateOf(app.store.phoneNumber) }; var dob by remember { mutableStateOf(app.store.dateOfBirth) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; app.store.profileUri = uri.toString() } }
    AlertDialog(onDismissRequest = close, title = { Text("Edit profile") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(first, { first = it.take(50) }, label = { Text("First name") })
        OutlinedTextField(last, { last = it.take(50) }, label = { Text("Last name") })
        OutlinedTextField(nickname, { nickname = it.take(40) }, label = { Text("Username / nickname") })
        OutlinedTextField(dob, { dob = it.filter { ch -> ch.isDigit() || ch == '-' }.take(10) }, label = { Text("Date of birth") }, placeholder = { Text("YYYY-MM-DD") })
        OutlinedTextField(phone, { phone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(20) }, label = { Text("Phone number") })
        OutlinedButton(onClick = { photo.launch(arrayOf("image/*")) }) { Text("Choose Profile Photo") }
    } }, confirmButton = { Button(enabled = first.trim().isNotEmpty() && last.trim().isNotEmpty() && nickname.trim().isNotEmpty() && dob.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && phone.length >= 7, onClick = {
        app.store.firstName = first.trim(); app.store.lastName = last.trim(); app.store.nickname = nickname.trim(); app.store.dateOfBirth = dob; app.store.phoneNumber = phone.trim(); app.store.friendlyName = nickname.trim(); close()
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable private fun Avatar(name: String, size: Int = 48) { Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) { Text(name.trim().take(1).uppercase().ifBlank { "P" }, fontWeight = FontWeight.Bold) } }
@Composable private fun ProfileAvatar(name: String, size: Int = 48) {
    val uri = OwnerLinkApp.instance.store.profileUri
    val bitmap = rememberThumbnail(uri, size * 3)
    if (bitmap == null) Avatar(name, size) else Image(bitmap.asImageBitmap(), "Profile picture", Modifier.size(size.dp).clip(CircleShape), contentScale = ContentScale.Crop)
}
@Composable private fun EmptyState(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, style = MaterialTheme.typography.titleLarge); Text(subtitle) } }

@Composable
private fun PrivacyChatMark(size: Int) {
    Image(painterResource(R.drawable.privacy_chat_logo), "Privacy Chat", Modifier.size(size.dp), contentScale = ContentScale.Fit)
}

@Composable
private fun rememberThumbnail(rawUri: String?, targetPx: Int): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, rawUri, targetPx) {
        value = withContext(Dispatchers.IO) {
            rawUri?.let { raw ->
                runCatching {
                    val uri = Uri.parse(raw)
                    val source = if (uri.scheme == "file") ImageDecoder.createSource(File(requireNotNull(uri.path))) else ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val scale = minOf(1f, targetPx.toFloat() / maxOf(info.size.width, info.size.height).coerceAtLeast(1))
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                }.getOrNull()
            }
        }
    }
    return bitmap
}

private fun openAttachment(context: android.content.Context, message: ChatMessage) {
    val raw = message.attachmentUri ?: return
    val original = Uri.parse(raw)
    val uri = if (original.scheme == "file") {
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", File(requireNotNull(original.path))) }.getOrNull() ?: return
    } else original
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, message.attachmentMime ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure { toast(context, "No compatible app is available to open this attachment") }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024.0))
    value >= 1024 -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}

private fun granted(context: android.content.Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
private fun toast(context: android.content.Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
private fun shortTime(time: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
private fun callLabel(call: CallUiState) = when (call.phase) { CallPhase.INCOMING -> if (call.video) "Incoming video call" else "Incoming audio call"; CallPhase.OUTGOING -> "Calling…"; CallPhase.CONNECTING -> "Connecting…"; CallPhase.RECONNECTING -> "Reconnecting…"; CallPhase.ACTIVE -> "Connected"; CallPhase.FAILED -> "Call failed"; else -> "Call ended" }
