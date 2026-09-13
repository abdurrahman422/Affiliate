(function () {
  const filter = document.getElementById("submission-filter"), search = document.getElementById("submission-search");
  function apply() { document.querySelectorAll(".submission-card").forEach((card) => { card.hidden = !!((filter.value && card.dataset.state !== filter.value) || (search.value && !card.dataset.search.toLowerCase().includes(search.value.toLowerCase()))); }); }
  filter?.addEventListener("change", apply); search?.addEventListener("input", apply);
  document.querySelectorAll("[data-decision]").forEach((button) => button.addEventListener("click", async () => {
    const card = button.closest("[data-submission-id]"), reason = card.querySelector(".review-reason").value;
    try { await OwnerLink.api(`/api/submissions/${card.dataset.submissionId}/review`, { method: "POST", body: { decision: button.dataset.decision, reason } }); window.location.reload(); }
    catch (error) { alert(error.message); }
  }));
})();
