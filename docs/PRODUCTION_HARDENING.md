# PrivatChat production deployment notes

The service is intentionally a single WebSocket worker because live device/contact routing is process-local. Durable dashboard sessions, revocation and contact metadata remain in Upstash Redis. Do not increase the Render instance count until distributed Redis pub/sub routing is implemented.

## Render always-on action

The repository cannot authorize billing. In the Render dashboard, open the `ownerlink` web service, choose **Settings → Instance Type**, select the lowest always-on paid instance available, confirm the change, and keep the instance count at one. This preserves the existing public URL, environment, Upstash data, and GitHub auto-deploy.

`/health` is a liveness endpoint and `/ready` confirms startup validation and storage initialization completed. The Docker entrypoint uses one Uvicorn worker and graceful shutdown so clients receive a restart close and reconnect with backoff.
