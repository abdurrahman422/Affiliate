(function () {
  const form = document.getElementById("task-form");
  const builder = document.getElementById("field-builder");
  const taskType = document.getElementById("task-type");
  const formFields = document.getElementById("form-task-fields");
  const addField = document.getElementById("add-field");
  const urlOptions = [...document.querySelectorAll(".url-task-option")];
  const importForm = document.getElementById("task-import-form");
  importForm?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const out = document.getElementById("task-import-result");
    const file = document.getElementById("task-json-file")?.files?.[0];
    if (!file) return;
    if (file.size > 256 * 1024) { out.textContent = "JSON file must be under 256 KB."; return; }
    try {
      const payload = JSON.parse(await file.text());
      await OwnerLink.api("/api/tasks/import", { method: "POST", body: payload });
      window.location.reload();
    } catch (error) { out.textContent = error instanceof SyntaxError ? "This is not valid JSON." : error.message; }
  });
  const types = ["short_text", "long_text", "url", "number", "dropdown", "multi_select", "checkbox", "datetime", "image", "file", "note"];
  document.getElementById("category-form")?.addEventListener("submit", async (event) => {
    event.preventDefault(); const fd = new FormData(event.currentTarget);
    try { await OwnerLink.api("/api/categories", { method: "POST", body: Object.fromEntries(fd.entries()) }); window.location.reload(); }
    catch (error) { alert(error.message); }
  });
  document.querySelectorAll("[data-category-id] [data-category-action]").forEach((button) => button.addEventListener("click", async () => {
    const card = button.closest("[data-category-id]"); const id = card.dataset.categoryId; const current = (window.AFFILIATE_CATEGORIES || []).find((c) => c.category_id === id); const action = button.dataset.categoryAction;
    try {
      if (action === "edit") { const name = prompt("Category name", current.name); if (!name) return; const description = prompt("Description", current.description || ""); await OwnerLink.api(`/api/categories/${encodeURIComponent(id)}`, { method: "PUT", body: { name, description: description || "", enabled: current.enabled !== false } }); }
      else if (action === "toggle") await OwnerLink.api(`/api/categories/${encodeURIComponent(id)}`, { method: "PUT", body: { enabled: current.enabled === false } });
      else { if (!confirm("Delete this category? Only unreferenced categories can be deleted.")) return; await OwnerLink.api(`/api/categories/${encodeURIComponent(id)}`, { method: "DELETE", body: {} }); }
      window.location.reload();
    } catch (error) { alert(error.message); }
  }));
  function fieldRow(value = {}) {
    const row = document.createElement("div"); row.className = "builder-row";
    const id = document.createElement("input"); id.placeholder = "field_id"; id.value = value.id || ""; id.setAttribute("aria-label", "Field identifier");
    const label = document.createElement("input"); label.placeholder = "User-facing label"; label.value = value.label || ""; label.setAttribute("aria-label", "Field label");
    const type = document.createElement("select"); type.setAttribute("aria-label", "Field type"); types.forEach((name) => { const option = document.createElement("option"); option.value = name; option.textContent = name.replaceAll("_", " "); option.selected = name === (value.type || "short_text"); type.appendChild(option); });
    const requiredLabel = document.createElement("label"); requiredLabel.className = "check-label"; const required = document.createElement("input"); required.type = "checkbox"; required.checked = !!value.required; requiredLabel.append(required, document.createTextNode("Required"));
    const choices = document.createElement("input"); choices.placeholder = "Choices / MIME types"; choices.value = (value.choices || value.allowed_types || []).join(", "); choices.setAttribute("aria-label", "Choices or allowed file types");
    const minimum = document.createElement("input"); minimum.type = "number"; minimum.placeholder = "Min"; minimum.value = value.min_length ?? value.min ?? ""; minimum.setAttribute("aria-label", "Minimum length or value");
    const maximum = document.createElement("input"); maximum.type = "number"; maximum.placeholder = "Max"; maximum.value = value.max_length ?? value.max ?? value.max_size_mb ?? ""; maximum.setAttribute("aria-label", "Maximum length, value, or megabytes");
    const help = document.createElement("input"); help.placeholder = "Help text"; help.value = value.help_text || ""; help.setAttribute("aria-label", "Help text");
    const up = document.createElement("button"); up.type = "button"; up.textContent = "↑"; up.setAttribute("aria-label", "Move field up"); up.addEventListener("click", () => { const previous = row.previousElementSibling; if (previous) builder.insertBefore(row, previous); });
    const down = document.createElement("button"); down.type = "button"; down.textContent = "↓"; down.setAttribute("aria-label", "Move field down"); down.addEventListener("click", () => { const next = row.nextElementSibling; if (next) builder.insertBefore(next, row); });
    const remove = document.createElement("button"); remove.type = "button"; remove.textContent = "Remove"; remove.className = "danger"; remove.addEventListener("click", () => row.remove());
    const order = document.createElement("span"); order.className = "field-order"; order.append(up, down);
    row.append(id, label, type, requiredLabel, choices, minimum, maximum, help, order, remove); builder.appendChild(row);
  }
  addField?.addEventListener("click", () => fieldRow());
  function syncTaskType() {
    const isUrl = taskType?.value === "URL";
    urlOptions.forEach((item) => { item.hidden = !isUrl; });
    if (formFields) formFields.hidden = isUrl;
    if (addField) addField.hidden = isUrl;
    const url = form?.elements.action_url;
    if (url) url.required = isUrl;
  }
  taskType?.addEventListener("change", syncTaskType);
  const templateSelect = document.getElementById("template");
  templateSelect?.addEventListener("change", () => {
    const template = (window.AFFILIATE_TEMPLATES || []).find((item) => item.template_id === templateSelect.value);
    if (!template) return; builder.innerHTML = ""; template.field_schema.forEach(fieldRow);
    form.elements.category_id.value = template.category_id; form.elements.reward.value = template.default_reward;
  });
  fieldRow({ id: "review_note", label: "Review notes", type: "long_text", required: true });
  form?.addEventListener("submit", async (event) => {
    event.preventDefault(); const out = document.getElementById("task-result"); const fd = new FormData(form);
    const fields = [...builder.querySelectorAll(".builder-row")].map((row) => {
      const [id, label, type, required, choices, minimum, maximum, help] = row.querySelectorAll("input,select");
      const value = { id: id.value.trim(), label: label.value.trim(), type: type.value, required: required.checked, help_text: help.value.trim() };
      const options = choices.value.split(",").map((x) => x.trim()).filter(Boolean);
      if (["dropdown", "multi_select"].includes(type.value)) value.choices = options;
      if (["short_text", "long_text"].includes(type.value)) { if (minimum.value) value.min_length = Number(minimum.value); if (maximum.value) value.max_length = Number(maximum.value); }
      if (type.value === "number") { if (minimum.value) value.min = Number(minimum.value); if (maximum.value) value.max = Number(maximum.value); }
      if (["image", "file"].includes(type.value)) { value.max_size_mb = Number(maximum.value || 10); value.allowed_types = options; }
      return value;
    });
    const eligibilityType = fd.get("eligibility_type");
    const body = { title: fd.get("title"), category_id: fd.get("category_id"), task_type: fd.get("task_type"), action_url: fd.get("action_url"), campaign_id: fd.get("campaign_id"), template_id: fd.get("template_id"), description: fd.get("description"), instructions: fd.get("instructions"), reward: fd.get("reward"), repeat_limit: fd.get("repeat_limit"), max_submissions: fd.get("max_submissions"), field_schema: taskType?.value === "URL" ? [] : fields, eligibility: { type: eligibilityType, ids: String(fd.get("eligibility_ids") || "").split(",").map((x) => x.trim()).filter(Boolean), minimum_quality_score: fd.get("minimum_quality_score") || undefined } };
    try { await OwnerLink.api("/api/tasks", { method: "POST", body }); window.location.reload(); }
    catch (error) { out.textContent = error.message; }
  });
  document.querySelectorAll("[data-task-id] [data-status]").forEach((button) => button.addEventListener("click", async () => {
    const card = button.closest("[data-task-id]");
    try { await OwnerLink.api(`/api/tasks/${card.dataset.taskId}/status`, { method: "POST", body: { status: button.dataset.status } }); window.location.reload(); }
    catch (error) { alert(error.message); }
  }));
  document.querySelectorAll("[data-task-id] [data-task-action]").forEach((button) => button.addEventListener("click", async () => {
    const card = button.closest("[data-task-id]");
    const task = (window.AFFILIATE_TASKS || []).find((item) => item.task_id === card.dataset.taskId);
    if (!task) return;
    try {
      if (button.dataset.taskAction === "delete") {
        if (!confirm("Delete this task? Tasks with submissions must be archived instead.")) return;
        await OwnerLink.api(`/api/tasks/${encodeURIComponent(task.task_id)}`, { method: "DELETE", body: {} });
      } else {
        const title = prompt("Task name", task.title); if (!title) return;
        const description = prompt("Description", task.description || ""); if (description === null) return;
        const instructions = prompt("Instructions", task.instructions || ""); if (!instructions) return;
        const reward = prompt("Reward coins", task.reward || "0"); if (reward === null) return;
        const patch = { title, description, instructions, reward };
        if ((task.task_type || "FORM") === "URL") { const actionUrl = prompt("Destination link", task.action_url || ""); if (!actionUrl) return; patch.action_url = actionUrl; patch.task_type = "URL"; }
        await OwnerLink.api(`/api/tasks/${encodeURIComponent(task.task_id)}`, { method: "PUT", body: patch });
      }
      window.location.reload();
    } catch (error) { alert(error.message); }
  }));
  syncTaskType();
})();
