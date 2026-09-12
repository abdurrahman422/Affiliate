# PrivatChat TURN deployment

PrivatChat prefers direct host/STUN candidates. Coturn is only selected by ICE when those paths cannot connect.

## One-time infrastructure setup

1. Provision a small public VM with a stable public IP and DNS name such as `turn.example.com`.
2. Install Coturn and copy `deploy/coturn/turnserver.conf.example` to the server outside the repository.
3. Generate a strong random shared secret on the server and place it in the private Coturn configuration. Never put it in Git or Android resources.
4. Issue a TLS certificate for the TURN hostname.
5. Open inbound/outbound UDP and TCP 3478, TCP 5349, and UDP 49160–49200.
6. Start Coturn and verify it with the official Trickle ICE test using temporary credentials.

## Render environment

Set these values in the Render service environment:

- `TURN_URLS=turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp,turns:turn.example.com:5349?transport=tcp`
- `TURN_SHARED_SECRET` to the same private Coturn REST shared secret.
- `TURN_REALM` to the configured realm.
- `TURN_CREDENTIAL_TTL=3600`

The backend derives an expiry-prefixed username and HMAC credential. Only this short-lived credential reaches authenticated devices. Restart/redeploy Render after setting the values. Do not use permanent TURN users in the APK.

## Verification

Confirm direct calls still produce host/server-reflexive candidates. Then test two devices on restrictive, different networks and confirm a relay candidate is selected. A live relay test cannot be claimed until a real Coturn endpoint is configured.
