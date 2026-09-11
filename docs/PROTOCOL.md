# OwnerLink — Minimal Device Protocol

This is a logical protocol. Grok may improve field names but must keep behavior.

## Common envelope

```json
{
  "type": "MESSAGE_TYPE",
  "request_id": "uuid-or-null",
  "device_id": "uuid",
  "payload": {}
}
```

## Android -> Server

### UNPAIRED_HELLO
```json
{
  "type": "UNPAIRED_HELLO",
  "device_id": "...",
  "payload": {
    "pairing_token": "...",
    "name": "Home Phone",
    "app_version": "1.0"
  }
}
```

### DEVICE_AUTH
```json
{
  "type": "DEVICE_AUTH",
  "device_id": "...",
  "payload": {
    "session_token": "..."
  }
}
```

### HEARTBEAT
```json
{
  "type": "HEARTBEAT",
  "device_id": "...",
  "payload": {
    "battery": 74,
    "charging": true
  }
}
```

### RESPONSE
```json
{
  "type": "RESPONSE",
  "request_id": "...",
  "device_id": "...",
  "payload": {
    "ok": true,
    "data": {}
  }
}
```

### ERROR
```json
{
  "type": "ERROR",
  "request_id": "...",
  "device_id": "...",
  "payload": {
    "code": "PERMISSION_MISSING",
    "message": "..."
  }
}
```

## Server -> Android commands

### LIST_GALLERY
Payload:
- cursor
- limit

### LIST_FILES
Payload:
- folder_id
- cursor/limit optional

### DOWNLOAD_FILE
Payload:
- file_id
- transfer_id

### GET_SCREEN_FRAME
Payload:
- quality
- max_width optional

### START_SCREEN_STREAM
Payload:
- fps target, capped by Android agent

### STOP_SCREEN_STREAM

### REMOTE_GESTURE
Examples:

Tap:
```json
{
  "gesture": "TAP",
  "x": 0.52,
  "y": 0.41
}
```

Long press:
```json
{
  "gesture": "LONG_PRESS",
  "x": 0.52,
  "y": 0.41,
  "duration_ms": 700
}
```

Swipe:
```json
{
  "gesture": "SWIPE",
  "x1": 0.5,
  "y1": 0.8,
  "x2": 0.5,
  "y2": 0.2,
  "duration_ms": 350
}
```

Global:
```json
{"gesture":"BACK"}
{"gesture":"HOME"}
{"gesture":"RECENTS"}
```

## Pair success

Server -> Android:
```json
{
  "type": "PAIR_SUCCESS",
  "device_id": "...",
  "payload": {
    "session_token": "<new durable random token>"
  }
}
```

Android:
- stores session securely;
- clears/archives pairing token locally;
- reconnects/authenticates with session token when needed.

## Revocation

Server -> Android if online:
```json
{
  "type": "REVOKED",
  "device_id": "...",
  "payload": {}
}
```

Android:
- clears session token;
- disconnects;
- generates a new pairing token/QR.

## Binary transport

Avoid base64 for:
- original file chunks
- screenshots if high frequency

Grok may define a compact binary header or pair JSON metadata with binary frames.

Protocol must include:
- transfer_id/request_id
- chunk ordering if chunked
- completion/error
- maximum memory limits

Do not load multi-GB files fully into RAM.
