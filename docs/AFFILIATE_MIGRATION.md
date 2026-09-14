# Affiliate branch migration notes

Affiliate is implemented in the existing repository and retains the existing Android application ID, device identity, dashboard pairing, WebSocket owner, Gallery, and Files components. Remote Screen is not registered or exposed by the Affiliate Android app and is replaced in the paired-device dashboard by on-demand Call Logs. Legacy source remains isolated only to avoid destabilizing the inherited codebase.

Affiliate accounts are separate from dashboard device identity. Existing installs without an authenticated account are routed to **Complete Your Account**; no phone, payout, address, age, or photo data is fabricated. Passwords use Argon2, bearer sessions are stored only in encrypted Android preferences, and registration/profile responses never expose password hashes. Task submissions and the coin ledger use the authenticated Affiliate `user_id` when a session is present.

SQLite development installs add Affiliate tables with SQLAlchemy `create_all`; existing tables are not deleted or rewritten. Android uses a separate `affiliate-room.db`, so the established chat database remains untouched. Production Redis data is namespaced below `ownerlink:v1:affiliate:*`; no media, proof bytes, or local text record is mirrored into Redis.

The standalone `abdurrahman422/Affiliate` repository must be deployed as a separate service from its `main` branch. Do not point the stable OwnerLink service at this repository. `render-affiliate.yaml` keeps automatic deployment disabled until the separate production environment is configured.

Release builds reuse the existing external signing configuration. Back up the signing key; losing it prevents future in-place updates.
