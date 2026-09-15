(function () {
  const result = document.getElementById("pair-result"), code = document.getElementById("pair-json");
  function show(message, ok) { result.textContent = message || ""; result.className = "meta " + (ok ? "ok" : ""); }
  document.getElementById("pair-btn")?.addEventListener("click", async () => {
    const redeemCode = code.value.trim(), name = document.getElementById("device-name").value.trim();
    if (redeemCode.length < 20 || redeemCode.length > 64) { show("Enter the Redeem Code shown in the Affiliate app."); return; }
    try {
      show("Connecting… keep Affiliate open on the phone.");
      const data = await OwnerLink.api("/api/devices/pair", { method: "POST", body: { redeem_code: redeemCode, name } });
      show("Connected " + ((data.device && data.device.name) || "device") + ".", true);
      setTimeout(() => { location.href = "/devices"; }, 700);
    } catch (error) { show(error.message); }
  });
})();
