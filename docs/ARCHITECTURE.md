# OwnerLink — Minimal Architecture

## Components

### 1. Android Agent
Runs on every owned Android phone.

Responsibilities:
- first-run setup;
- server URL;
- device ID;
- pairing QR;
- outbound authenticated WebSocket;
- gallery listing;
- file listing;
- file streaming;
- online heartbeat;
- remote screenshot;
- remote gestures.

### 2. FastAPI Server
One service for API + web UI.

Responsibilities:
- owner login/session;
- device registry;
- pending pairing;
- active device WebSocket registry;
- browser commands;
- file relay;
- screen-frame relay;
- device removal.

### 3. Browser
Used on laptop/secondary phone.

Responsibilities:
- login;
- dashboard;
- QR scan for pairing;
- files/gallery UI;
- downloads;
- remote-screen UI;
- send gestures.

## Network topology

```
Home Android  ----\
                   \
                    >---- HTTPS/WSS ---- OwnerLink Server ---- HTTPS/WSS ---- Browser
                   /
Business Android -/
```

All Android connections are OUTBOUND.

No inbound port is opened on the Android devices.

## Server state

Persistent SQLite:
- owner credential record;
- devices;
- pairing state;
- revoked status;
- friendly name;
- last seen.

In-memory:
- active Android WebSocket connections;
- pending request futures;
- active file-stream relays;
- active screen subscribers.

## Suggested SQLite tables

### owner
- id
- username
- password_hash

### devices
- id
- device_id UNIQUE
- friendly_name
- pairing_token_hash
- pairing_consumed BOOLEAN
- device_session_hash
- revoked BOOLEAN
- created_at
- last_seen

Do not store raw pairing token/session token when a hash is sufficient.

## Android connection handshake

1. Android opens WSS `/ws/device`.
2. If unpaired:
   - send `HELLO_UNPAIRED` with device_id and pairing token proof/value.
   - server records/updates pending device connection.
3. After dashboard pairing:
   - server sends `PAIRED` with durable random device session credential.
   - Android stores it securely.
4. Future reconnect:
   - Android sends device_id + session credential.
   - server authenticates and marks online.

For V1, a high-entropy bearer-style device credential over TLS is acceptable.

Do not build asymmetric challenge-response unless needed later.

## Browser authentication

Server creates session after username/password login.

Use server-side session storage or signed session cookie.

Cookie:
- Secure in production
- HttpOnly
- SameSite=Lax or Strict
- reasonable expiry

No public API endpoint should operate without owner authentication, except the Android device WebSocket handshake.

## Command model

Browser request -> backend -> correct Android WebSocket -> response -> browser.

Every command includes:
- request_id
- device_id
- command type
- payload

Examples:
- LIST_GALLERY
- LIST_FILES
- DOWNLOAD_FILE
- GET_SCREEN_FRAME
- REMOTE_GESTURE
- GET_STATUS

## File transfer

Do NOT base64-encode large files.

Use binary WebSocket frames or a dedicated temporary HTTP streaming channel tied to a request_id.

Simplest acceptable approach:
1. browser requests download;
2. server creates one-time internal transfer ID;
3. server asks Android to begin transfer;
4. Android sends metadata then binary chunks;
5. server streams chunks into the authenticated browser response;
6. transfer state deleted after completion/failure.

No permanent disk write unless unavoidable temporary buffering is needed.
If temporary file buffering is used:
- store in OS temp directory;
- delete immediately after transfer;
- never retain as a library/cloud copy.

## Screen frames

Screen frames are transient.
Do not persist them in DB/disk.

Suggested frame message:
- device_id
- width
- height
- rotation
- timestamp
- JPEG/WebP bytes

## Reconnect

Android:
- immediate retry after short failure;
- exponential backoff to a sensible max;
- network callback can trigger immediate retry when connectivity returns.

Server:
- heartbeat/ping;
- last_seen update;
- mark offline after timeout.

## Scope control

Do not introduce:
- Kafka
- Redis
- PostgreSQL
- microservices
- Kubernetes
- TURN server
- WebRTC
unless a concrete V1 blocker requires them.

FastAPI + SQLite + WebSocket is the intended V1 architecture.
