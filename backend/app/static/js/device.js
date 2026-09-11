(function () {
  const form = document.getElementById("rename-form");
  if (!form) return;
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const name = document.getElementById("rename-input").value.trim();
    const status = document.getElementById("rename-status");
    try {
      await OwnerLink.api(`/api/devices/${window.OWNERLINK_DEVICE_ID}/rename`, {
        method: "POST",
        body: { name }
      });
      status.textContent = "Name saved.";
      document.getElementById("device-title").textContent = name;
    } catch (err) {
      status.textContent = err.message;
    }
  });
})();
