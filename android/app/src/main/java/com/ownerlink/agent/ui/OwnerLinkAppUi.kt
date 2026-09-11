package com.ownerlink.agent.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ownerlink.agent.OwnerLinkApp
import com.ownerlink.agent.network.ConnectionState
import com.ownerlink.agent.pairing.PairingJson
import com.ownerlink.agent.pairing.QrEncoder
import com.ownerlink.agent.service.ConnectionService
import com.ownerlink.agent.util.Permissions
import com.ownerlink.agent.util.Urls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Dest { Setup, Permissions, Pairing, Connected }

@Composable
fun OwnerLinkAppUi() {
    val app = OwnerLinkApp.instance
    val store = app.store
    val conn by app.socket.state.collectAsState()
    val msg by app.socket.message.collectAsState()
    var dest by remember {
        mutableStateOf(
            when {
                store.serverUrl.isBlank() -> Dest.Setup
                !store.permissionsSeen -> Dest.Permissions
                !store.paired -> Dest.Pairing
                else -> Dest.Connected
            },
        )
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (dest) {
            Dest.Setup -> SetupScreen(
                initial = store.serverUrl,
                onSave = { url ->
                    store.serverUrl = url
                    dest = Dest.Permissions
                },
            )
            Dest.Permissions -> PermissionsScreen(
                onContinue = {
                    store.permissionsSeen = true
                    dest = if (store.paired) Dest.Connected else Dest.Pairing
                },
            )
            Dest.Pairing -> PairingScreen(
                conn = conn,
                msg = msg,
                onConnected = { dest = Dest.Connected },
                onPermissions = { dest = Dest.Permissions },
            )
            Dest.Connected -> ConnectedScreen(
                conn = conn,
                msg = msg,
                onPermissions = { dest = Dest.Permissions },
                onUnpaired = { dest = Dest.Pairing },
                onServer = { dest = Dest.Setup },
            )
        }
    }
    LaunchedEffect(conn) {
        if (conn == ConnectionState.Online) dest = Dest.Connected
        if (conn == ConnectionState.Revoked || (conn == ConnectionState.WaitingForPair && !store.paired)) {
            dest = Dest.Pairing
        }
    }
}

@Composable
private fun SetupScreen(initial: String, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("OwnerLink", style = MaterialTheme.typography.headlineMedium)
        Text("Enter the OwnerLink server URL. The phone must be able to reach it.")
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server URL") },
            placeholder = { Text("http://127.0.0.1:8000") },
            singleLine = true,
        )
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button(
            enabled = !checking,
            onClick = {
                scope.launch {
                    checking = true
                    error = null
                    try {
                        val url = Urls.normalizeAndValidate(value)
                        val ok = withContext(Dispatchers.IO) { Urls.reachable(url) }
                        if (!ok) {
                            error = "This phone cannot reach $url. Use USB with http://127.0.0.1:8000, or put the phone on the same Wi-Fi subnet as the PC."
                        } else {
                            onSave(url)
                        }
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        checking = false
                    }
                }
            },
        ) { Text(if (checking) "Checking…" else "Save and continue") }
        Text(
            "USB: keep the cable plugged in and use http://127.0.0.1:8000 after adb reverse. Wi-Fi: phone and PC must share the same subnet.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PermissionsScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var generation by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) generation++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val mediaOk = remember(generation) { Permissions.hasMedia(context) }
    val notifOk = remember(generation) { Permissions.hasNotifications(context) }
    val filesOk = remember(generation) { Permissions.hasAllFiles() }
    val accessOk = remember(generation) { Permissions.accessibilityEnabled(context) }
    val batteryOk = remember(generation) { Permissions.batteryOptimizationIgnored(context) }

    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { generation++ }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        generation++
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            val first = denied.first()
            if (Permissions.shouldOpenSettingsAfterDenial(activity, first) && !Permissions.hasMedia(context)) {
                Permissions.openAppSettings(context)
            }
        }
    }
    val saf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            OwnerLinkApp.instance.store.addSafTree(uri.toString())
            generation++
        }
    }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Permissions", style = MaterialTheme.typography.headlineMedium)
        Text("Grant photos/videos so Gallery works. Grant all-files or pick a folder for Files.")
        StatusRow("Notifications", notifOk) {
            if (Build.VERSION.SDK_INT >= 33) notif.launch(Permissions.notificationPermission())
            else Toast.makeText(context, "Notifications are not a runtime permission on this Android version.", Toast.LENGTH_SHORT).show()
        }
        StatusRow("Photos and videos", mediaOk) {
            val needed = Permissions.mediaPermissions()
            if (needed.isEmpty()) Permissions.openAppSettings(context)
            else media.launch(needed)
        }
        StatusRow("All files access", filesOk) {
            Permissions.openAllFilesSettings(context)
        }
        OutlinedButton(onClick = { saf.launch(null) }) { Text("Pick a folder (SAF fallback)") }
        StatusRow("Accessibility (remote screen)", accessOk) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        StatusRow("Battery optimization ignored", batteryOk) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:" + context.packageName)
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        Text("If OEM battery savers still kill the app, add OwnerLink to their allowlist. OwnerLink does not bypass those settings.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = onContinue) { Text("Continue") }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (ok) "$label: granted" else "$label: not granted")
        TextButton(onClick = onClick) { Text(if (ok) "Open" else "Grant") }
    }
}

@Composable
private fun PairingScreen(
    conn: ConnectionState,
    msg: String,
    onConnected: () -> Unit,
    onPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val app = OwnerLinkApp.instance
    val clipboard = LocalClipboardManager.current
    var name by remember { mutableStateOf(app.store.friendlyName) }
    val payload = remember(app.store.pairingToken, app.store.serverUrl, name, conn) {
        app.store.friendlyName = name
        PairingJson.encode(app.store)
    }
    val qr = remember(payload) { QrEncoder.encode(payload) }

    LaunchedEffect(Unit) {
        ConnectionService.start(context)
        app.socket.connect()
    }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Pair this phone", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Wait until this screen says “Waiting for pairing”, then paste the QR JSON in the website. Keep this app open.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Device name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Device ID: ${app.store.deviceId}", style = MaterialTheme.typography.bodySmall)
        Text("Server: ${app.store.serverUrl}", style = MaterialTheme.typography.bodySmall)
        Image(bitmap = qr.asImageBitmap(), contentDescription = "Pairing QR", modifier = Modifier.fillMaxWidth())
        Text(statusLabel(conn, msg))
        Button(onClick = {
            ConnectionService.start(context)
            app.socket.connect()
        }) { Text("Start remote access") }
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(payload))
            Toast.makeText(context, "Pairing data copied", Toast.LENGTH_SHORT).show()
        }) { Text("Copy pairing data") }
        TextButton(onClick = onPermissions) { Text("Permissions") }
        if (conn == ConnectionState.Online) {
            Button(onClick = onConnected) { Text("Continue") }
        }
    }
}

@Composable
private fun ConnectedScreen(
    conn: ConnectionState,
    msg: String,
    onPermissions: () -> Unit,
    onUnpaired: () -> Unit,
    onServer: () -> Unit,
) {
    val context = LocalContext.current
    val app = OwnerLinkApp.instance
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(app.store.friendlyName, style = MaterialTheme.typography.headlineMedium)
        Text(statusLabel(conn, msg))
        Text("Server: ${Urls.hostLabel(app.store.serverUrl)}")
        Text("Device ID: ${app.store.deviceId}", style = MaterialTheme.typography.bodySmall)
        Text("Notifications: ${yes(Permissions.hasNotifications(context))}")
        Text("Media: ${yes(Permissions.hasMedia(context))}")
        Text("All files: ${yes(Permissions.hasAllFiles())}")
        Text("Accessibility: ${yes(Permissions.accessibilityEnabled(context))}")
        Text("Remote camera/mic: unavailable (not included in V1)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ConnectionService.start(context) }) { Text("Start") }
            OutlinedButton(onClick = { ConnectionService.stop(context) }) { Text("Stop remote service") }
        }
        TextButton(onClick = onPermissions) { Text("Permissions") }
        TextButton(onClick = onServer) { Text("Change server URL") }
        if (!app.store.paired) {
            TextButton(onClick = onUnpaired) { Text("Show pairing QR") }
        }
    }
}

private fun statusLabel(conn: ConnectionState, msg: String): String {
    val core = when (conn) {
        ConnectionState.Idle -> "Disconnected"
        ConnectionState.Connecting -> "Connecting"
        ConnectionState.WaitingForPair -> "Waiting for pairing"
        ConnectionState.Online -> "Connected"
        ConnectionState.Offline -> "Disconnected — reconnecting"
        ConnectionState.Revoked -> "Not paired"
    }
    return if (msg.isBlank()) core else "$core — $msg"
}

private fun yes(v: Boolean) = if (v) "ready" else "needs setup"
