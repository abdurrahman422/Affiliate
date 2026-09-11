# OwnerLink — Build and Deployment Guide Requirements

Grok must create the actual commands/scripts, not only describe them.

## Development topology

For local testing:
- backend on laptop;
- Android emulator/physical phone on same LAN or via accessible dev URL;
- debug build may allow explicit HTTP development URL if necessary.

For production:
- Linux VPS/server;
- public domain/subdomain;
- Caddy HTTPS;
- Android phones connect outbound over WSS;
- laptop browser connects over HTTPS.

## Recommended production directory

```
ownerlink/
  backend/
  android/
  deploy/
  README.md
  docker-compose.yml
  .env.example
```

## Docker deployment

Prefer:
- `ownerlink-backend`
- `caddy`

SQLite data in persistent volume.

Example conceptual ports:
- backend internal 8000
- Caddy public 80/443

Only Caddy should be public.

## Caddy

Caddy terminates TLS and reverse proxies:
- HTTP -> FastAPI
- WebSocket upgrade -> FastAPI automatically

Grok should generate a valid Caddyfile using `${DOMAIN}` or documented replacement.

## Firewall

Production guide:
- allow 22 only as needed for SSH;
- allow 80/443;
- do NOT expose FastAPI port 8000 publicly if Docker/private network handles it;
- no ADB ports.

## First-run admin account

Grok should implement one simple bootstrap path.

Preferred:
1. Put `OWNERLINK_ADMIN_USERNAME` and `OWNERLINK_ADMIN_PASSWORD` in `.env` before first boot.
2. If no owner exists, hash password and create owner.
3. After initial creation, password hash lives in SQLite.
4. Document how to change password later via CLI or authenticated settings.

Do not ship default `admin/admin`.

## Android first-run

1. Install APK.
2. Enter production server URL.
3. Grant requested file/media permissions.
4. Enable OwnerLink accessibility service if remote screen/control is desired.
5. Start remote access service.
6. QR appears.
7. On laptop login -> Add Device -> scan QR.
8. Device shows connected.

## Travel usage

No local server setup on laptop.

User simply:
1. opens `https://<domain>`;
2. logs in;
3. chooses Home Phone or Business Phone;
4. accesses gallery/files/screen.

## Build verification

Grok must actually run:
- backend tests;
- Python import/startup check;
- Android Gradle build;
- lint/basic compile;
- Docker config validation where tooling exists.

Fix build errors before declaring done.

## Final outputs expected from Grok

- backend source
- web templates/static
- Android source
- debug APK path if build environment supports it
- Docker deployment files
- `.env.example`
- generated setup README
- test report
- known Android OS limitations
