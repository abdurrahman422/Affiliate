# OwnerLink — V1 Product Specification

## 1. Core user story

I own multiple Android phones.

Example:
- Home Phone stays at home.
- Business Phone stays at the office.

Before traveling, both phones are already configured and paired to OwnerLink.

While in Dhaka or another city, I open my OwnerLink website from a laptop or another phone, log in, choose the device I need, then browse its files/gallery or check its current screen.

I should NOT need physical access to the phone for ordinary reconnect after Wi-Fi/mobile-data interruptions.

## 2. Login screen

Route:
`/login`

UI:
- Username
- Password
- Login button
- Small error message for invalid credentials

No:
- Register
- Forgot password flow
- Social login

## 3. Dashboard

Route:
`/`

Example:

OwnerLink
--------------------------------

Home Phone
Online
[ Open ] [ Remove ]

Business Phone
Online
[ Open ] [ Remove ]

[ + Add Device ]

Each device card shows:
- friendly name;
- online/offline;
- last seen;
- optional battery percentage if easy.

## 4. Add Device

The Android app displays a QR.

The website has:
`Add Device`

Preferred flow:
1. Click Add Device.
2. Browser opens QR scanner if camera permission is available.
3. Scan QR displayed on Android phone.
4. Browser sends pairing data to backend.
5. Backend binds the pending Android device to the owner account.
6. Android app receives "paired" state.
7. Device appears on dashboard.

Provide a manual fallback:
- "Paste Pairing Data" or "Enter Pairing Code"
This fallback is allowed but QR remains the main UX.

## 5. Pairing QR contents

QR can encode a compact JSON object or deep-link string.

Required logical fields:
- server identifier/base URL;
- device_id;
- pairing_token.

Example concept only:
`ownerlink://pair?device_id=<id>&token=<high_entropy_token>`

Do not use short predictable tokens.

No expiration timer is required.

After successful pairing:
- mark pairing token consumed;
- do not accept it a second time.

After device removal:
- revoke active credential;
- Android shows "Not paired";
- Android generates a new pairing token/QR.

## 6. Device page

Route example:
`/device/{device_id}`

Show:
- Online/Offline
- Files
- Gallery
- Remote Screen

Optional:
- battery
- network
- last seen

## 7. Gallery

Route example:
`/device/{device_id}/gallery`

Behavior:
- Android reads current media directly with MediaStore.
- Browser receives simple paginated batches.
- Display image/video thumbnails.
- Clicking an image opens preview.
- Download button downloads original file.

Do not build:
- AI search
- OCR
- albums engine
- face grouping
- cloud sync

## 8. Files

Route example:
`/device/{device_id}/files`

Behavior:
- Simple folder-style browser.
- Show name, type, size where easy.
- Clicking folder opens it.
- Clicking file offers Download.
- Optional preview only for simple supported types.

Preferred personal-device access:
- request Android "All files access" where allowed;
- fall back to SAF-selected folders if permission is unavailable.

Respect Android-protected locations.

## 9. File download

Important:
- File remains on phone.
- Browser requests file.
- Server asks target Android device.
- Android streams bytes to server.
- Server relays bytes to authenticated browser.
- Server should not permanently save the file.

Support:
- Content-Disposition filename
- known Content-Type
- basic progress in browser if easy

V1 may start with non-resumable streaming.
Large-file resume is NOT required.

## 10. Online/offline

Android keeps outbound WebSocket to server.

Temporary internet loss:
- mark offline after timeout;
- Android retries automatically with backoff;
- when connection returns, device becomes online again;
- no QR re-pairing required.

## 11. Remote Screen View

Purpose:
- check what an app is doing;
- view long-running processing status;
- see business app order/status screens when capture is allowed;
- basic remote interaction.

V1 is intentionally LOW FPS.

Use AccessibilityService screenshot APIs where supported.

Suggested behavior:
- default refresh around 1 FPS;
- adaptive maximum around 1–2 FPS;
- immediately request a fresh frame after a remote gesture;
- JPEG/WebP compression with configurable quality.

Do not promise 30/60 FPS.

If screenshot API returns rate-limit/error:
- back off;
- show last frame;
- recover automatically.

If protected content cannot be captured:
- display "Protected screen cannot be captured."

## 12. Remote Control

AccessibilityService is manually enabled by the owner on each Android device.

Supported commands:
- Tap
- Long press
- Swipe
- Scroll up/down
- Back
- Home
- Recents

Coordinate approach:
- Browser sends normalized x/y coordinates (0.0–1.0).
- Android maps them to current screenshot/display size.
- Dispatch gesture with official AccessibilityService APIs.

Do not add arbitrary text/password/OTP extraction or credential automation.

## 13. Camera and Microphone

V1 core must build successfully even if camera/mic are disabled.

If Grok implements them:
- treat as experimental;
- official Android permissions only;
- visible foreground service/indicator;
- do not bypass Android background restrictions;
- show "Unavailable due to Android OS restrictions" when needed.

Never implement stealth capture.

## 14. Device removal

From dashboard:
`Remove Device`

On confirmation:
- revoke device credential/session server-side;
- disconnect active WebSocket;
- remove from dashboard;
- phone returns to unpaired state;
- create new pairing QR for a future pairing.

## 15. Expected V1 experience

Initial setup happens while owner has physical access to phone.

After that, ordinary travel usage should be:

Laptop -> OwnerLink URL -> Username/Password -> Dashboard -> Choose Home/Business phone -> Files/Gallery/Remote Screen.

No QR is needed again unless the owner explicitly removes/re-pairs a device.
