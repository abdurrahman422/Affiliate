(function () {
  document.getElementById("category-form")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    try { await OwnerLink.api("/api/categories", { method: "POST", body: Object.fromEntries(new FormData(event.currentTarget).entries()) }); location.reload(); }
    catch (error) { alert(error.message); }
  });
  document.querySelectorAll("[data-category-action]").forEach((button) => button.addEventListener("click", async () => {
    const card = button.closest("[data-category-id]"), id = card.dataset.categoryId;
    const current = (window.AFFILIATE_CATEGORIES || []).find((item) => item.category_id === id);
    try {
      if (button.dataset.categoryAction === "edit") {
        const name = prompt("Category name", current.name); if (!name) return;
        const description = prompt("Description", current.description || ""); if (description === null) return;
        await OwnerLink.api(`/api/categories/${encodeURIComponent(id)}`, { method: "PUT", body: { name, description, enabled: current.enabled !== false } });
      } else if (button.dataset.categoryAction === "toggle") await OwnerLink.api(`/api/categories/${encodeURIComponent(id)}`, { method: "PUT", body: { enabled: current.enabled === false } });
      else { if (!confirm("Delete this category?")) return; await OwnerLink.api(`/api/categories/${encodeURIComponent(id)}`, { method: "DELETE", body: {} }); }
      location.reload();
    } catch (error) { alert(error.message); }
  }));
})();
