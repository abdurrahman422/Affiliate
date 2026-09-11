# Production setup

1. Point a DNS A/AAAA record at the VPS.
2. Copy `.env.example` to `.env` and set:
   - `OWNERLINK_DOMAIN`
   - `OWNERLINK_BASE_URL=https://<that-domain>`
   - `OWNERLINK_ADMIN_USERNAME`
   - `OWNERLINK_ADMIN_PASSWORD` (first boot only, ≥ 12 characters)
   - `OWNERLINK_SECRET_KEY` (≥ 32 characters)
3. Firewall: allow 22 (SSH as needed), 80, 443. Do not expose 8000 or ADB.
4. From the `ownerlink` directory:

```bash
docker compose up -d --build
```

5. Confirm `https://<domain>/login` works, then remove `OWNERLINK_ADMIN_PASSWORD` from `.env`.
6. Install the Android APK, enter `https://<domain>`, pair via QR.

Caddyfile is `deploy/Caddyfile`. It reverse-proxies all HTTP and WebSocket traffic to `backend:8000`.
