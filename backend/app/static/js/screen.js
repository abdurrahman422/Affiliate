(function () {
  const id = window.OWNERLINK_DEVICE_ID;
  const img = document.getElementById("screen-frame");
  const status = document.getElementById("screen-status");
  let ws = null;
  let objectUrl = null;

  function setStatus(text, kind) {
    status.textContent = text;
    status.className = "banner " + (kind || "");
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
          setStatus("Connected — waiting for the first frame", "ok");
        }
        return;
      }
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      const blob = new Blob([ev.data], { type: "image/jpeg" });
      objectUrl = URL.createObjectURL(blob);
      img.onload = () => setStatus("Streaming", "ok");
      img.onerror = () => setStatus("A screen frame arrived but could not be decoded", "bad");
      img.src = objectUrl;
    };
  }

  function stop() {
    if (ws) { ws.close(); ws = null; }
    setStatus("Stopped");
  }

  document.getElementById("btn-start").addEventListener("click", connect);
  document.getElementById("btn-stop").addEventListener("click", stop);
})();
