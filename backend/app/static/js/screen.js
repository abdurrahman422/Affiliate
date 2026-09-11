(function () {
  const id = window.OWNERLINK_DEVICE_ID;
  const img = document.getElementById("screen-frame");
  const status = document.getElementById("screen-status");
  const stage = document.getElementById("screen-stage");
  let ws = null;
  let objectUrl = null;

  function setStatus(text, kind) {
    status.textContent = text;
    status.className = "banner " + (kind || "");
  }

  function send(obj) {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
  }

  function gesture(payload) {
    send({ type: "GESTURE", payload });
  }

  function connect() {
    if (ws) ws.close();
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/ws/screen/${id}`);
    ws.binaryType = "arraybuffer";
    ws.onopen = () => setStatus("Connected — waiting for frames", "ok");
    ws.onclose = () => setStatus("Disconnected");
    ws.onerror = () => setStatus("Screen connection failed", "bad");
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") {
        const msg = JSON.parse(ev.data);
        if (msg.type === "ERROR" || (msg.payload && msg.payload.code === "PROTECTED_SCREEN")) {
          const code = (msg.payload && msg.payload.code) || msg.type;
          const text = (msg.payload && msg.payload.message) || "Protected screen cannot be captured.";
          setStatus(code === "PROTECTED_SCREEN" ? "Protected screen cannot be captured." : text, "bad");
        } else if (msg.type === "STATUS") {
          setStatus("Streaming", "ok");
        }
        return;
      }
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      const blob = new Blob([ev.data], { type: "image/jpeg" });
      objectUrl = URL.createObjectURL(blob);
      img.src = objectUrl;
      setStatus("Streaming", "ok");
    };
  }

  function stop() {
    if (ws) { ws.close(); ws = null; }
    setStatus("Stopped");
  }

  function norm(ev) {
    const rect = img.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    const x = (ev.clientX - rect.left) / rect.width;
    const y = (ev.clientY - rect.top) / rect.height;
    if (x < 0 || y < 0 || x > 1 || y > 1) return null;
    return { x, y };
  }

  let down = null;
  let longTimer = null;
  let longSent = false;

  stage.addEventListener("pointerdown", (ev) => {
    const p = norm(ev);
    if (!p) return;
    stage.setPointerCapture(ev.pointerId);
    down = { ...p, t: Date.now() };
    longSent = false;
    longTimer = setTimeout(() => {
      if (!down) return;
      longSent = true;
      gesture({ gesture: "LONG_PRESS", x: down.x, y: down.y, duration_ms: 700 });
    }, 520);
  });
  stage.addEventListener("pointerup", (ev) => {
    clearTimeout(longTimer);
    const p = norm(ev) || down;
    if (!down || !p) { down = null; return; }
    const dx = p.x - down.x;
    const dy = p.y - down.y;
    const dist = Math.hypot(dx, dy);
    if (!longSent && dist > 0.04) {
      gesture({ gesture: "SWIPE", x1: down.x, y1: down.y, x2: p.x, y2: p.y, duration_ms: 350 });
    } else if (!longSent) {
      gesture({ gesture: "TAP", x: p.x, y: p.y });
    }
    down = null;
  });
  stage.addEventListener("pointercancel", () => { clearTimeout(longTimer); down = null; });

  document.getElementById("btn-start").addEventListener("click", connect);
  document.getElementById("btn-stop").addEventListener("click", stop);
  document.getElementById("btn-back").addEventListener("click", () => gesture({ gesture: "BACK" }));
  document.getElementById("btn-home").addEventListener("click", () => gesture({ gesture: "HOME" }));
  document.getElementById("btn-recents").addEventListener("click", () => gesture({ gesture: "RECENTS" }));
})();
