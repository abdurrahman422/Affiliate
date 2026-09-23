(function () {
  const id = window.OWNERLINK_DEVICE_ID;
  const img = document.getElementById("workspace-frame");
  const stage = document.getElementById("workspace-stage");
  const status = document.getElementById("workspace-status");
  const apps = document.getElementById("workspace-apps");
  const openButton = document.getElementById("btn-open-app");
  let ws = null;
  let objectUrl = null;

  function setStatus(text, kind) {
    status.textContent = text;
    status.className = "banner " + (kind || "");
  }
  function send(type, payload) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type, payload: payload || {} }));
  }
  function populate(items) {
    apps.textContent = "";
    (items || []).forEach((item) => {
      const option = document.createElement("option");
      option.value = item.package;
      option.textContent = `${item.label} — ${item.package}`;
      apps.appendChild(option);
    });
    apps.disabled = !apps.options.length;
    openButton.disabled = apps.disabled;
  }
  function connect() {
    if (ws && ws.readyState <= WebSocket.OPEN) return;
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/ws/workspace/${id}`);
    ws.binaryType = "arraybuffer";
    setStatus("Starting isolated workspace…");
    ws.onopen = () => setStatus("Connected — creating secondary display…", "ok");
    ws.onclose = () => { setStatus("Workspace disconnected"); populate([]); };
    ws.onerror = () => setStatus("Workspace connection failed", "bad");
    ws.onmessage = (event) => {
      if (typeof event.data === "string") {
        const message = JSON.parse(event.data);
        const payload = message.payload || {};
        if (message.type === "ERROR") setStatus(payload.message || "Workspace error", "bad");
        else if (message.type === "WORKSPACE_READY") {
          populate(payload.apps || []);
          setStatus("Remote workspace running on a separate display", "ok");
        } else if (message.type === "APP_RESULT") {
          setStatus(payload.message || "App opened in remote workspace", payload.ok === false ? "bad" : "ok");
        } else if (message.type === "VIRTUAL_WORKSPACE_STATUS") {
          setStatus(payload.message || payload.code || "Workspace status", payload.code === "STOPPED" ? "" : "ok");
        }
        return;
      }
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      objectUrl = URL.createObjectURL(new Blob([event.data], { type: "image/jpeg" }));
      img.src = objectUrl;
    };
  }
  function stop() {
    send("STOP", {});
    if (ws) ws.close();
    ws = null;
    populate([]);
  }
  function normalized(event) {
    const rect = img.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    const x = (event.clientX - rect.left) / rect.width;
    const y = (event.clientY - rect.top) / rect.height;
    return x >= 0 && y >= 0 && x <= 1 && y <= 1 ? { x, y } : null;
  }
  let down = null;
  let timer = null;
  let longSent = false;
  stage.addEventListener("pointerdown", (event) => {
    const point = normalized(event); if (!point) return;
    stage.setPointerCapture(event.pointerId); down = { ...point }; longSent = false;
    timer = setTimeout(() => { if (down) { longSent = true; send("GESTURE", { gesture: "LONG_PRESS", x: down.x, y: down.y, duration_ms: 700 }); } }, 520);
  });
  stage.addEventListener("pointerup", (event) => {
    clearTimeout(timer); const point = normalized(event) || down;
    if (!down || !point) { down = null; return; }
    const distance = Math.hypot(point.x - down.x, point.y - down.y);
    if (!longSent && distance > .04) send("GESTURE", { gesture: "SWIPE", x1: down.x, y1: down.y, x2: point.x, y2: point.y, duration_ms: 350 });
    else if (!longSent) send("GESTURE", { gesture: "TAP", x: point.x, y: point.y });
    down = null;
  });
  stage.addEventListener("pointercancel", () => { clearTimeout(timer); down = null; });
  document.getElementById("btn-start").addEventListener("click", connect);
  document.getElementById("btn-stop").addEventListener("click", stop);
  document.getElementById("btn-home").addEventListener("click", () => send("HOME", {}));
  openButton.addEventListener("click", () => send("LAUNCH_APP", { package: apps.value }));
})();
