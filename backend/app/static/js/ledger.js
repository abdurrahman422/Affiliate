(function () {
  const form = document.getElementById("adjust-form");
  form?.addEventListener("submit", async (event) => { event.preventDefault(); const out = document.getElementById("adjust-result"), fd = new FormData(form);
    try { await OwnerLink.api("/api/coin-ledger/adjust", { method: "POST", body: Object.fromEntries(fd.entries()) }); window.location.reload(); }
    catch (error) { out.textContent = error.message; }
  });
})();
