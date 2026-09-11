# OwnerLink Backend + Web UI — Implementation Specification

## Stack

Preferred:
- Python 3.12+
- FastAPI
- Uvicorn
- Jinja2 templates
- vanilla JavaScript
- SQLite
- SQLAlchemy or SQLModel
- passlib/argon2 or bcrypt for password hashing
- secure session middleware/library
- WebSocket
- Docker
- Caddy

Keep dependencies modest.

## Configuration

Use environment variables.

Required examples:
- `OWNERLINK_ADMIN_USERNAME`
- `OWNERLINK_ADMIN_PASSWORD` only for first-run/bootstrap OR use a CLI init command
- `OWNERLINK_SECRET_KEY`
- `OWNERLINK_BASE_URL`
- `DATABASE_URL=sqlite:///...`
- `ENV=production`

Prefer:
- on first startup, if owner table empty, create account using env credentials and store only password hash;
- log a warning to remove plaintext password env after bootstrap if practical.

Never print the password.

## Pages

### `/login`
Username/password.

### `/`
Authenticated dashboard.

### `/device/{device_id}`
Device page.

### `/device/{device_id}/gallery`
Gallery.

### `/device/{device_id}/files`
Files.

### `/device/{device_id}/screen`
Remote screen/control.

### `/devices/add`
QR scanner / pairing page.

## Login behavior

No signup.

On success:
- create secure owner session.
- redirect dashboard.

On failure:
- generic "Invalid username or password".

Add basic login rate limiting if it can be implemented simply.
Do not add CAPTCHA.

## QR scanning page

Use a locally bundled or pinned QR scanning JS dependency where practical.
Avoid sending camera frames to third-party services.

Fallback:
- text area "Paste pairing data".

Validate:
- version
- server match
- device_id format
- token length

Pair only a device that is currently known/pending or can complete authenticated handshake.

## Device dashboard

Show online/offline.

Buttons:
- Open
- Remove

Add Device button.

Friendly name can be changed.

## Android WebSocket registry

Maintain:
`device_id -> websocket connection`

Authenticate reconnect using device session credential.

If a second connection for same device appears:
- close the older connection or choose latest safely.

Heartbeat:
- update last_seen;
- detect offline.

## Browser/device request broker

Create request_id UUID.

Map:
`request_id -> asyncio Future/Queue`

Timeout ordinary commands reasonably, e.g. 15–30 seconds.

For file streaming, use a streaming queue/channel.

Clean up request state on:
- completion;
- timeout;
- browser disconnect;
- Android disconnect.

## Gallery endpoints

Authenticated browser requests only.

Example:
`GET /api/devices/{id}/gallery?cursor=...`

Server:
- verify owner session;
- verify device is paired;
- send command to Android;
- return items.

Thumbnail:
- can return via small temporary binary response;
- no persistent storage.

## File endpoints

List:
`GET /api/devices/{id}/files?folder=<opaque-id>`

Download:
`GET /api/devices/{id}/download?file=<opaque-id>`

Do not expose raw arbitrary server filesystem paths.

Use StreamingResponse for file relay.

Set:
- Content-Disposition
- Content-Type
- Content-Length if known

## Screen

Page:
`/device/{id}/screen`

UI:
- image/canvas element for current frame;
- Connect/Start View button;
- Stop button;
- Back/Home/Recents controls;
- mouse/touch click translates to normalized coordinates;
- swipe gesture support on pointer drag;
- status banner.

Transport:
Use browser WebSocket for interactive screen messages if easiest.

Server relays:
browser <-> selected Android device.

No frame persistence.

## Remove device

Authenticated POST.

Actions:
- mark revoked;
- invalidate session credential;
- close active device socket;
- remove it from normal dashboard listing or mark removed;
- notify phone if connected.

## Error UX

Examples:
- Device offline
- Permission missing
- File not found
- Protected screen
- Accessibility service disabled
- Request timed out

Do not show Python tracebacks to browser.

## Static UI style

Keep clean and functional.
Responsive for desktop/mobile.
No design framework required.

Pages should be usable before visual polish.

## Backend deliverable

Grok must produce:
- complete source;
- requirements/pyproject;
- migrations or automatic SQLite initialization;
- Dockerfile;
- docker-compose.yml;
- Caddyfile/example;
- `.env.example`;
- setup scripts if useful;
- tests for critical auth/pairing/device-registry flows.
