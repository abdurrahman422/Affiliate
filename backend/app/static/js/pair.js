(function () {
  const result = document.getElementById("pair-result"), code = document.getElementById("pair-json");
  function show(message, ok) { result.textContent = message || ""; result.className = "meta " + (ok ? "ok" : ""); }
  document.getElementById("pair-btn")?.addEventListener("click", async () => {
    const redeemCode = code.value.trim(), name = document.getElementById("device-name").value.trim();
    if (!/^[A-Za-z0-9_-]{22}$/.test(redeemCode)) { show("Enter the exact 22-character connection code shown in Privacy Chat."); return; }
    try {
      show("Connecting… keep Privacy Chat online on the phone.");
      const data = await OwnerLink.api("/api/devices/pair", { method: "POST", body: { redeem_code: redeemCode, name } });
      show("Connected " + ((data.device && data.device.name) || "device") + ".", true);
      setTimeout(() => { location.href = "/devices"; }, 700);
    } catch (error) { show(error.message); }
  });
})();
