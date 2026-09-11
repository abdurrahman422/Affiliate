# OwnerLink — Final V1 Decisions

This file is authoritative. If another document is ambiguous, follow this one.

## Product goal

OwnerLink is a personal, self-hosted remote-access system for Android phones owned and configured by the same owner.

The owner may keep:
- a Home Phone at home, mainly for important personal files/documents;
- a Business Phone at the office, mainly for checking business apps/status/orders.

The owner wants to travel with a laptop/secondary phone and open one private website to access either Android device.

## V1 must stay simple

Do NOT turn this into TeamViewer, an MDM platform, an enterprise admin suite, cloud drive, AI file indexer, OCR project, or multi-user SaaS.

## Required V1 features

1. Self-hosted website/backend on the owner's own server/VPS.
2. Website login with username + password.
3. No sign-up / no registration page.
4. Dashboard showing all paired phones separately.
5. Add Device using QR code pairing.
6. Each phone has its own independent device identity and pairing token.
7. Pairing token has NO time duration/expiry requirement.
8. Pairing token is single-use once successfully paired.
9. Removing a device invalidates its device session and requires a new QR for future pairing.
10. Phone automatically reconnects after temporary network loss.
11. Gallery browsing.
12. File/folder browsing.
13. Download original images/videos/files to the browser.
14. Do not permanently upload or mirror phone files to the server.
15. Phone online/offline state.
16. Low-FPS remote screen view for app testing/status checking.
17. Remote control: tap, long press, swipe, Back, Home, Recents, scroll.
18. Respect Android secure-screen restrictions; do not bypass them.
19. Payment, banking, OTP, password-manager, authenticator automation is explicitly out of scope.
20. Camera/microphone are optional/experimental modules and MUST NOT block the core build. They must use official Android permissions/foreground behavior only.

## Security philosophy for V1

Keep security practical but not over-engineered:
- HTTPS/WSS only in deployment.
- One private owner login.
- Password stored as a strong hash, never plaintext.
- Secure HttpOnly session cookie.
- Random device ID.
- High-entropy pairing token shown as QR.
- Device session/token stored using Android Keystore-protected storage where practical.
- Server does not permanently store phone file contents.
- Device can be revoked from dashboard.
- No public ADB.
- No unauthenticated file endpoints.

E2EE, certificate pinning, passkeys, 2FA, hardware attestation, rotating keys, trusted-browser pairing, audit hardening etc. are future upgrades, not V1 blockers.

## Preferred simplicity decisions

### Backend/web
Use ONE FastAPI application for:
- login/session;
- HTML dashboard;
- static JS/CSS;
- WebSocket endpoint for Android devices;
- WebSocket/HTTP endpoints for browser actions;
- file streaming relay.

Use SQLite for V1.

Avoid React/Next.js unless Grok has a compelling build reason. Simple server-rendered HTML + vanilla JavaScript is preferred.

### Android
Use Kotlin.
Jetpack Compose for normal app UI.
Use:
- MediaStore for gallery;
- MANAGE_EXTERNAL_STORAGE for broad personal file access when granted, with SAF fallback;
- OkHttp WebSocket;
- WorkManager/foreground service as appropriate for reconnect/reliability;
- AccessibilityService for low-FPS screenshot + gestures;
- Android Keystore for local secrets/credential protection.

### Deployment
Self-host on Linux VPS/server.
Recommended:
- Docker Compose
- FastAPI container
- Caddy reverse proxy for automatic HTTPS

Do not depend on Vercel.

## Non-goals

Do not add:
- AI
- OCR
- file semantic search
- full-text indexing
- facial recognition
- duplicate detection
- public accounts
- subscriptions
- payment processing
- cloud file synchronization
- remote shell
- arbitrary command execution
- ADB over internet
- stealth camera/microphone
- permission bypasses
- secure-window bypasses
