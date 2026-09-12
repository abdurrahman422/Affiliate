#!/bin/sh
set -eu

# exec makes Uvicorn PID 1 so Render's SIGTERM reaches it and the FastAPI
# lifespan can close WebSockets with a restart code before shutdown.
exec uvicorn app.main:create_app --factory \
  --host 0.0.0.0 \
  --port "${PORT:-8000}" \
  --workers 1 \
  --ws-ping-interval 20 \
  --ws-ping-timeout 20 \
  --timeout-graceful-shutdown 20
