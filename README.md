# Affiliate (branch product) / PrivatChat foundation

The `Affiliate` branch adds a distributed task, review, and coin-ledger product while preserving the existing authenticated device-access foundation. Stable production remains on `main`; use `docs/AFFILIATE_MIGRATION.md` and the separate `render-affiliate.yaml` preview blueprint before deployment.

Affiliate reuses the hardened PrivatChat/OwnerLink Android, backend, dashboard, and remote-access foundation. The Affiliate specification package beside this repository is authoritative on this branch.

Self-hosted remote access for Android phones you own. One website, multiple phones (for example Home Phone and Business Phone). Browse gallery and files, download originals through a live relay, and use a low-FPS remote screen with tap / swipe / Back / Home / Recents.

Task definitions, submissions, reviews, and the coin ledger sync through the private backend. There is no public sign-up, remote shell, bulk phone upload, or automatic upload of `user_data.txt`.

## Repository layout

```text
ownerlink/
  android/          Kotlin agent (Android Studio)
  backend/          FastAPI + Jinja2 + SQLite
  deploy/           Caddy reverse proxy
  docs/             product and protocol specs
  docker-compose.yml
  .env.example
```

## Local development (backend)

Python 3.12+ recommended (3.13 works).

```powershell
cd ownerlink/backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

Linux/macOS:

```bash
cd ownerlink/backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Create `ownerlink/.env` from `.env.example` (or set the same variables in the shell):

```text
ENV=development
OWNERLINK_BASE_URL=http://127.0.0.1:8000
OWNERLINK_ADMIN_USERNAME=owner
OWNERLINK_ADMIN_PASSWORD=choose-a-long-password
OWNERLINK_SECRET_KEY=dev-secret-key-at-least-32-chars
DATABASE_URL=sqlite:///./ownerlink.db
```

Start the server:

```powershell
cd ownerlink/backend
.\.venv\Scripts\Activate.ps1
uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000
```

Open `http://127.0.0.1:8000/login`.

Debug Android builds may use `http://<lan-ip>:8000` as the server URL. Production builds require HTTPS.

### Tests

```powershell
cd ownerlink/backend
.\.venv\Scripts\Activate.ps1
pytest -q
```

### Change owner password later

```powershell
cd ownerlink/backend
python -m app.cli set-password
```

Or use **Settings** after login.

## Android agent

Open `ownerlink/android` in Android Studio. `minSdk` 30, `compileSdk`/`targetSdk` 34.

Build a debug APK:

```powershell
cd ownerlink/android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

APK path:

`android/app/build/outputs/apk/debug/app-debug.apk`

### Phone setup (physical access, once)

1. Install the APK.
2. The production server is built into the app; no server URL entry is required.
3. Grant notifications, photos/videos, and All files access (or pick folders).
4. Enable the PrivatChat accessibility service if you want remote screen/control.
5. Optionally disable battery optimization for PrivatChat.
6. Start remote access. A QR code appears.
7. On a laptop, log in to PrivatChat → **Add Device**. In the app, use **Settings → Connect with Dashboard** to scan or paste the separate dashboard code.

After that, travel usage is: open the site → log in → pick the phone. Temporary internet loss does not unpair the device.

## Production (Docker + Caddy HTTPS)

On a Linux VPS with Docker Compose, a public DNS name, and ports 80/443 open:

```bash
git clone <this-repo>
cd ownerlink
cp .env.example .env
# edit .env: DOMAIN, BASE_URL, username, long password, 32+ char secret
docker compose up -d --build
```

Exact compose command:

```bash
docker compose up -d --build
```

Caddy terminates TLS and reverse-proxies HTTP and WebSocket to the FastAPI container. Do not publish port 8000. Allow 22/80/443 only as needed. Do not expose ADB.

After the first successful boot, remove `OWNERLINK_ADMIN_PASSWORD` from `.env` and recreate the backend container (the password hash is already in SQLite).

SQLite lives in the `ownerlink-data` volume at `/data/ownerlink.db`.

## What V1 does not do

- Camera / microphone remote capture (UI reports unavailable; not required for V1)
- Cloud file mirroring
- 30/60 FPS remote desktop
- Secure-screen bypass
- Payment, banking, OTP, or credential automation
- Remote shell / ADB over the internet

See `docs/KNOWN_LIMITATIONS.md` for Android OS constraints.

## Render Free + Upstash Redis

The repository includes `render.yaml` for a Render Web Service. This is the supported
no-card production topology: Render runs the FastAPI dashboard and WebSocket endpoint;
Upstash Redis persists owner, device, pairing, revocation, and hashed durable-session
metadata. Phone gallery/files/screen bytes are only relayed while requested and are not
stored in Redis.

1. Create a free Upstash Redis database and copy its REST URL and REST token.
2. Push this existing repository to GitHub, then choose **New → Blueprint** in Render
   and select the repository. Render reads `render.yaml`.
3. Set `OWNERLINK_BASE_URL` to the resulting permanent HTTPS Render URL, e.g.
   `https://ownerlink-xxxx.onrender.com`, and paste the Upstash credentials.
4. For a first boot set a 3+ character `OWNERLINK_ADMIN_USERNAME` and a 12+ character
   `OWNERLINK_ADMIN_PASSWORD`; remove the plaintext password from Render after the
   owner has been created.
5. Enter that exact HTTPS Render URL (without `/login` or `/ws/device`) in the Android
   app's built-in production server setting.

Render Free may sleep. That disconnects live sockets but does not unpair a phone: the
Android foreground connection service retries with exponential backoff and reuses its
securely stored device session when Render wakes. Force-stopping the Android app is an
Android OS action and requires manually reopening the app before it can reconnect.
