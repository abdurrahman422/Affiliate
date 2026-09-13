# Affiliate branch migration notes

Affiliate is implemented in the existing repository and retains the existing Android application ID, device identity, dashboard pairing, WebSocket owner, Gallery, Files, and Remote Screen components.

SQLite development installs add Affiliate tables with SQLAlchemy `create_all`; existing tables are not deleted or rewritten. Android uses a separate `affiliate-room.db`, so the established chat database remains untouched. Production Redis data is namespaced below `ownerlink:v1:affiliate:*`; no media, proof bytes, or local text record is mirrored into Redis.

The `Affiliate` branch must be deployed as a separate preview service. Do not merge it into `main` or point the stable service at this branch without a separate release decision. `render-affiliate.yaml` is a preview blueprint with automatic deployment disabled.

Release builds reuse the existing external signing configuration. Back up the signing key; losing it prevents future in-place updates.
