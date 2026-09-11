# OwnerLink Android Agent — Implementation Specification

## Stack

- Kotlin
- Jetpack Compose
- AndroidX
- OkHttp WebSocket
- kotlinx.serialization
- Coroutines
- Android Keystore
- MediaStore
- StorageManager / MANAGE_EXTERNAL_STORAGE where allowed
- Storage Access Framework fallback
- AccessibilityService
- Foreground Service for reliable active remote connection where required

Use Gradle Kotlin DSL if convenient.

Choose versions that are mutually compatible and available in the build environment.
Prefer a stable minSdk around API 30 unless a dependency requires otherwise.
Compile/target the highest stable SDK available in the environment without breaking build compatibility.

## Screens

### 1. Setup screen
Fields:
- Server URL
- Save/Connect

Validate:
- URL must be HTTPS in production.
- Allow local HTTP only behind an explicit `debug` build flag for LAN development.

### 2. Permissions screen
Show clear status:
- Notifications
- Photos/Videos
- All Files Access OR Selected Folders
- Accessibility Service
- optional Camera/Microphone

Buttons open official Android settings/intents.

### 3. Pairing screen
Show:
- Device friendly name editable
- Device ID
- QR code
- Pairing status
- Copy pairing data fallback

### 4. Connected screen
Show:
- Connected/Disconnected
- server hostname
- device name
- permission status
- regenerate/re-pair only when unpaired
- Stop Remote Service (optional)

## Device identity

On first run:
- generate UUID device_id;
- generate >= 256-bit random pairing token;
- store private values securely.

QR payload example:
```json
{
  "v": 1,
  "server": "https://example.com",
  "device_id": "...",
  "pairing_token": "..."
}
```

No timer expiration.

After successful pairing:
- pairing token considered consumed on server;
- Android receives durable device_session token;
- store device_session using Keystore-protected encrypted storage.

After server revocation:
- clear session;
- generate new pairing token;
- show new QR.

## Foreground connection service

Goal:
keep reliable outbound server connection for a dedicated personal phone.

Requirements:
- visible foreground notification where Android requires it;
- auto-start/reconnect according to platform rules;
- handle Doze/network changes;
- no hidden service tricks;
- no boot-loop behavior.

Notification example:
`OwnerLink remote access active`

Action:
`Disconnect`

If Android requires user to disable battery optimization manually, show setup instructions in the app rather than bypassing OS behavior.

## WebSocket protocol

Connect:
`wss://host/ws/device`

Use JSON text frames for control messages.
Use binary frames for file/screen data when practical.

Create a single manager responsible for:
- connect;
- authenticate;
- heartbeat;
- reconnect;
- dispatch request;
- send response;
- cancellation.

## Gallery

Use MediaStore.

Support:
- photos;
- videos;
- pagination/batch;
- thumbnail bytes or resized preview;
- original download through content URI.

Do not copy all media to app storage.

## Files

Primary personal-device mode:
- if `Environment.isExternalStorageManager()` then browse shared storage roots directly where allowed.

Fallback:
- SAF directory tree selected by owner;
- persist URI permission.

Never assume access to protected private directories of other apps.

File listing DTO:
- stable id/path token
- display name
- directory boolean
- size
- mime type if known
- modified time if easy

Security:
Do not let browser send arbitrary raw filesystem paths and trust them blindly.
Resolve requested files against allowed roots or server-issued opaque file IDs.

## Remote screenshot

AccessibilityService configuration should request only capabilities needed.

Implement screenshot capture using supported Android AccessibilityService APIs when available.

Use low-FPS mode:
- around 1 FPS default;
- max around 2 FPS unless testing proves a safe higher rate;
- compress frame before network transfer;
- request immediate frame after remote gesture;
- backoff on rate-limit errors.

If API not supported on a given Android version/device:
return capability=false.

## Remote gestures

AccessibilityService:
- `dispatchGesture` for tap/long press/swipe.
- `performGlobalAction` for Back/Home/Recents where available.

Browser sends normalized coordinates:
x = 0..1
y = 0..1

Android converts using current display dimensions and rotation.

Supported messages:
- TAP
- LONG_PRESS
- SWIPE
- BACK
- HOME
- RECENTS
- SCROLL_UP
- SCROLL_DOWN

Do not implement automated credential typing, OTP capture, or banking workflows.

## Secure/protected screens

If screenshot returns blank/protected or OS blocks it:
send capability/error message.
Never attempt to bypass FLAG_SECURE or other platform protections.

## Optional camera/microphone module

This must be isolated behind feature flags.

Only implement using official:
- CameraX / camera APIs
- microphone APIs
- foreground service types/permissions

If Android background restrictions prevent remote start:
show unavailable.
Do not bypass permission indicators or OS restrictions.

Core app must compile/run even if this module is disabled.

## Suggested packages

```
com.ownerlink.agent
  data/
  network/
  pairing/
  files/
  gallery/
  remote/
  accessibility/
  service/
  security/
  ui/
  util/
```

## Android deliverable

Grok must produce:
- complete Android Studio project;
- Gradle wrapper;
- buildable debug APK;
- README with install/setup;
- no placeholder TODOs for core V1 features.
