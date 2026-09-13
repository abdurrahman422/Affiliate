(function () {
  const form = document.getElementById("campaign-form");
  form?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const out = document.getElementById("campaign-result");
    const values = Object.fromEntries(new FormData(form).entries());
    values.eligibility = { type: values.eligibility_type, ids: String(values.eligibility_ids || "").split(",").map((x) => x.trim()).filter(Boolean) };
    delete values.eligibility_type; delete values.eligibility_ids;
    try { await OwnerLink.api("/api/campaigns", { method: "POST", body: values }); window.location.reload(); }
    catch (error) { out.textContent = error.message; }
  });
  document.querySelectorAll("[data-campaign-id] [data-status]").forEach((button) => button.addEventListener("click", async () => {
    const card = button.closest("[data-campaign-id]");
    try { await OwnerLink.api(`/api/campaigns/${card.dataset.campaignId}/status`, { method: "POST", body: { status: button.dataset.status } }); window.location.reload(); }
    catch (error) { alert(error.message); }
  }));
})();
