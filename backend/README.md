# OwnerLink backend

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8000
pytest -q
python -m app.cli set-password
```

Configuration is loaded from `../.env` or `./.env`. See the repository root README.

## Render + Upstash production

Use the repository-root `render.yaml` to create a Render Web Service. Render supplies
`PORT`; the Docker start command binds to it automatically. In Render, set the public
service URL as `OWNERLINK_BASE_URL` (for example `https://ownerlink-xxxx.onrender.com`),
set `ENV=production` and `STORAGE_BACKEND=redis`, then paste the two REST credentials
from an Upstash Redis database into `UPSTASH_REDIS_REST_URL` and
`UPSTASH_REDIS_REST_TOKEN`.

Do not set a TTL on OwnerLink keys. The phone's paired session is deliberately durable:
a Render sleep/restart closes the socket, and the Android foreground service reconnects
using its stored session token without a new QR pairing.
