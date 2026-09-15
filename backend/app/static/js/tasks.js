(function () {
  const form = document.getElementById("task-form"), builder = document.getElementById("field-builder"), type = document.getElementById("task-type"), formFields = document.getElementById("form-task-fields");
  function addQuestion() { const row = document.createElement("div"); row.className = "builder-row simple-question"; row.innerHTML = '<input aria-label="Question" placeholder="Question, e.g. Your name" required><select aria-label="Answer type"><option value="short_text">Short answer</option><option value="long_text">Long answer</option><option value="number">Number</option></select><label class="check-label"><input type="checkbox" checked> Required</label><button type="button" class="danger">Remove</button>'; row.querySelector("button").onclick = () => row.remove(); builder.appendChild(row); }
  function syncType() { const link = type.value === "URL"; document.querySelectorAll(".url-task-option").forEach((el) => el.hidden = !link); formFields.hidden = link; document.getElementById("add-field").hidden = link; if (!link && !builder.children.length) addQuestion(); }
  document.getElementById("add-field")?.addEventListener("click", addQuestion); type?.addEventListener("change", syncType);
  form?.addEventListener("submit", async (event) => {
    event.preventDefault(); const output = document.getElementById("task-result"), submit = document.getElementById("create-task-button"), fd = new FormData(form);
    const eligibility = { type: fd.get("eligibility_type"), gender: fd.get("eligibility_gender"), ids: String(fd.get("eligibility_ids") || "").split(",").map((v) => v.trim()).filter(Boolean) };
    const common = { title: fd.get("title"), category_id: fd.get("category_id"), task_type: fd.get("task_type"), description: fd.get("description"), instructions: fd.get("instructions"), reward: fd.get("reward"), repeat_limit: fd.get("repeat_limit"), eligibility };
    try {
      submit.disabled = true; submit.textContent = "Creating…"; output.textContent = "Validating and publishing task…";
      if (type.value === "URL") {
        const urls = String(fd.get("action_urls") || "").split(/\r?\n/).map((v) => v.trim()).filter(Boolean); if (!urls.length) throw new Error("Add at least one destination link.");
        if (urls.length === 1) { const result = await OwnerLink.api("/api/tasks", { method: "POST", body: { ...common, action_url: urls[0], field_schema: [] } }); await OwnerLink.api(`/api/tasks/${result.task.task_id}/status`, { method: "POST", body: { status: "PUBLISHED" } }); }
        else { const category = (window.AFFILIATE_CATEGORIES || []).find((item) => item.category_id === common.category_id); if (!category) throw new Error("Select a category first."); await OwnerLink.api("/api/tasks/import", { method: "POST", body: { version: 1, category: { name: category.name, description: category.description || "" }, series: { name: common.title }, task_defaults: { ...common, title_prefix: common.title, publish: true }, tasks: urls.map((action_url) => ({ action_url })) } }); }
      } else {
        const fields = [...builder.children].map((row, index) => { const inputs = row.querySelectorAll("input,select"); return { id: `answer_${index + 1}`, label: inputs[0].value.trim(), type: inputs[1].value, required: inputs[2].checked }; });
        if (!fields.length || fields.some((field) => !field.label)) throw new Error("Add a name for every form question.");
        const result = await OwnerLink.api("/api/tasks", { method: "POST", body: { ...common, field_schema: fields } });
        await OwnerLink.api(`/api/tasks/${result.task.task_id}/status`, { method: "POST", body: { status: "PUBLISHED" } });
      }
      location.reload();
    } catch (error) { output.textContent = error.message; submit.disabled = false; submit.textContent = "Create task"; }
  });
  document.querySelectorAll("[data-task-id] [data-status]").forEach((button) => button.onclick = async () => { try { await OwnerLink.api(`/api/tasks/${button.closest("[data-task-id]").dataset.taskId}/status`, { method: "POST", body: { status: button.dataset.status } }); location.reload(); } catch (error) { alert(error.message); } });
  document.querySelectorAll("[data-task-action]").forEach((button) => button.onclick = async () => { const id = button.closest("[data-task-id]").dataset.taskId, task = (window.AFFILIATE_TASKS || []).find((item) => item.task_id === id); try { if (button.dataset.taskAction === "delete") { if (!confirm("Delete this task?")) return; await OwnerLink.api(`/api/tasks/${id}`, { method: "DELETE", body: {} }); } else { const title = prompt("Task name", task.title); if (!title) return; const description = prompt("Description", task.description || ""); if (description === null) return; const instructions = prompt("Instructions", task.instructions || ""); if (!instructions) return; await OwnerLink.api(`/api/tasks/${id}`, { method: "PUT", body: { title, description, instructions } }); } location.reload(); } catch (error) { if (button.dataset.taskAction === "delete" && error.message.includes("submissions") && confirm("This task has history. Archive and hide it instead?")) { await OwnerLink.api(`/api/tasks/${id}/status`, { method: "POST", body: { status: "ARCHIVED" } }); location.reload(); } else alert(error.message); } });
  syncType();
})();
