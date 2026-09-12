(function () {
  const video = document.getElementById("qr-video");
  const statusEl = document.getElementById("scan-status");
  const resultEl = document.getElementById("pair-result");
  const jsonEl = document.getElementById("pair-json");
  let stream = null;
  let raf = 0;
  let scanning = false;

  function setScan(msg) { if (statusEl) statusEl.textContent = msg || ""; }
  function setResult(msg, ok) {
    resultEl.textContent = msg || "";
    resultEl.className = "meta " + (ok ? "ok" : "");
  }

  function parsePayload(text) {
    const raw = (text || "").trim();
    if (!raw) throw new Error("Empty pairing data");
    if (raw.startsWith("ownerlink://")) {
      const u = new URL(raw);
      return {
        v: 1,
        server: u.searchParams.get("server") || "",
        device_id: u.searchParams.get("device_id"),
        pairing_token: u.searchParams.get("token") || u.searchParams.get("pairing_token")
      };
    }
    return JSON.parse(raw);
  }

  async function pair(payload) {
    const name = document.getElementById("device-name").value.trim();
    const body = Object.assign({}, payload, name ? { name } : {});
    let lastErr = null;
    for (let i = 0; i < 20; i++) {
      setResult(i === 0
        ? "Pairing… keep PrivatChat open on the phone until the dashboard connection completes."
        : "Waiting for the phone to come online… (" + (i + 1) + "/20)");
      try {
        const data = await OwnerLink.api("/api/devices/pair", { method: "POST", body });
        setResult("Paired " + ((data.device && data.device.name) || "device") + ".", true);
        setTimeout(() => { window.location.href = "/"; }, 700);
        return;
      } catch (err) {
        lastErr = err;
        if (err.code && err.code !== "DEVICE_OFFLINE") throw err;
        await new Promise((resolve) => setTimeout(resolve, 1000));
      }
    }
    throw lastErr || new Error("Device is not online. On the phone tap Start remote access and wait until it says Waiting for pairing.");
  }

  async function handleText(text) {
    try {
      jsonEl.value = text;
      const payload = parsePayload(text);
      await pair(payload);
      stopScan();
    } catch (err) {
      setResult(err.message);
    }
  }

  async function tick() {
    if (!scanning) return;
    if ("BarcodeDetector" in window) {
      try {
        const detector = tick._detector || (tick._detector = new BarcodeDetector({ formats: ["qr_code"] }));
        const codes = await detector.detect(video);
        if (codes && codes.length) {
          await handleText(codes[0].rawValue);
          return;
        }
      } catch (_e) { /* keep scanning */ }
    } else if (window.jsQR && video.readyState >= 2) {
      const canvas = tick._canvas || (tick._canvas = document.createElement("canvas"));
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      const ctx = canvas.getContext("2d");
      ctx.drawImage(video, 0, 0);
      const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
      const code = jsQR(img.data, img.width, img.height);
      if (code && code.data) {
        await handleText(code.data);
        return;
      }
    }
    raf = requestAnimationFrame(tick);
  }

  async function startScan() {
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: false });
      video.srcObject = stream;
      await video.play();
      scanning = true;
      setScan("Point the camera at the phone QR.");
      raf = requestAnimationFrame(tick);
    } catch (err) {
      setScan("Camera unavailable. Paste pairing data instead. " + err.message);
    }
  }

  function stopScan() {
    scanning = false;
    cancelAnimationFrame(raf);
    if (stream) {
      stream.getTracks().forEach((t) => t.stop());
      stream = null;
    }
    video.srcObject = null;
    setScan("");
  }

  document.getElementById("start-scan").addEventListener("click", startScan);
  document.getElementById("stop-scan").addEventListener("click", stopScan);
  document.getElementById("pair-btn").addEventListener("click", async () => {
    try {
      await pair(parsePayload(jsonEl.value));
    } catch (err) {
      setResult(err.message);
    }
  });
})();
