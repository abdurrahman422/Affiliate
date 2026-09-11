(function () {
  async function refresh() {
    try {
      const data = await OwnerLink.api("/api/devices");
      const grid = document.getElementById("device-grid");
      if (!grid || !data.devices) return;
      if (!data.devices.length) return;
      for (const d of data.devices) {
        const card = grid.querySelector(`[data-device-id="${d.device_id}"]`);
        if (!card) continue;
        const pill = card.querySelector(".pill");
        if (pill) {
          pill.textContent = d.online ? "Online" : "Offline";
          pill.className = "pill " + (d.online ? "ok" : "off");
        }
      }
    } catch (_e) { /* ignore polling errors */ }
  }
  document.querySelectorAll("[data-remove]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const id = btn.getAttribute("data-remove");
      const name = btn.getAttribute("data-name") || "this device";
      if (!confirm(`Remove ${name}? The phone will be unpaired and will need a new QR.`)) return;
      try {
        await OwnerLink.api(`/api/devices/${id}/remove`, { method: "POST", body: {} });
        window.location.reload();
      } catch (err) {
        alert(err.message);
      }
    });
  });
  setInterval(refresh, 5000);
})();
