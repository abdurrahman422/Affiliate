FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY app /app/app
COPY start.sh /app/start.sh

RUN mkdir -p /data && chmod 755 /app/start.sh

ENV PYTHONUNBUFFERED=1 \
    STORAGE_BACKEND=redis \
    ENV=production

EXPOSE 8000

CMD ["/app/start.sh"]
