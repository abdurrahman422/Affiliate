# OwnerLink — V1 Security Rules

These rules are mandatory even though V1 is intentionally simple.

## 1. Web authentication

- One owner account.
- No signup.
- Strong password hash: Argon2id preferred; bcrypt acceptable.
- Never log passwords.
- Generic login errors.
- Auth required for all dashboard/API routes.

## 2. Session

Production cookie:
- Secure
- HttpOnly
- SameSite=Lax or Strict

Generate strong application secret from environment.

Logout destroys owner session.

## 3. Transport

Production:
- HTTPS
- WSS

Never deploy production remote access over plain HTTP/WS.

Caddy is recommended for TLS.

## 4. Device pairing

QR token:
- cryptographically random;
- at least 256 bits of entropy;
- no fixed duration/expiry;
- single-use after successful pairing;
- invalid after device removal.

Never use:
- sequential codes
- MAC address as secret
- IMEI as secret
- Android ID as authentication secret

## 5. Device session

After pairing:
- issue a different durable random session credential;
- pairing token is no longer the reconnect credential;
- server stores a hash when possible;
- phone stores credential using Keystore-protected storage.

Temporary network loss must NOT unpair device.

## 6. Authorization

Every browser request:
- owner session required;
- verify target device belongs to owner/personal instance;
- verify device not revoked.

Every Android request:
- authenticate device session;
- bind WebSocket to exactly one device_id.

## 7. File safety

Never accept arbitrary server file paths from browser.

On Android:
- only access allowed storage roots/content URIs;
- use opaque file identifiers or validate canonical paths.

Server:
- do not permanently save phone files;
- prevent path traversal;
- sanitize download filename headers.

## 8. Remote screen/control

Only paired devices.

AccessibilityService must be manually enabled by device owner.

Never:
- hide permission prompts;
- auto-enable accessibility using exploits;
- bypass secure windows;
- capture banking/OTP credentials;
- add remote shell or arbitrary command execution.

## 9. Logs

Allowed:
- login success/failure metadata
- device online/offline
- pairing/removal
- transfer success/failure
- generic errors

Do NOT log:
- passwords
- pairing token plaintext
- device session plaintext
- file contents
- screenshots
- sensitive document names if avoidable

## 10. Secrets

No hardcoded:
- admin password
- production domain credential
- TLS private key
- device master key

Use environment configuration.

## 11. CORS/CSRF

Because web UI and backend are same origin, keep CORS restrictive.

State-changing browser requests should have CSRF protection or same-site session + explicit POST token mechanism.

At minimum protect:
- pair device
- remove device
- logout
- rename device

## 12. Future V2 security upgrades

Document but do not block V1:
- 2FA
- Passkeys
- end-to-end encryption
- certificate pinning
- device asymmetric keys
- hardware attestation
- trusted browser/device approval
- key rotation
- richer audit logs
- IP/session alerts
