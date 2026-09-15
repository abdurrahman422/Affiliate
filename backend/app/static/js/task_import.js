(function () {
  const form = document.getElementById("task-import-form");
  const input = document.getElementById("task-json-file");
  const button = document.getElementById("task-import-button");
  const output = document.getElementById("task-import-result");
  if (!form || !input || !button || !output) return;

  input.addEventListener("change", () => {
    const file = input.files && input.files[0];
    output.textContent = file ? `${file.name} is ready to import.` : "Choose a JSON file to begin.";
  });

  async function importSelectedFile(event) {
    if (event) event.preventDefault();
    const file = input.files && input.files[0];
    if (!file) { output.textContent = "Choose a JSON file first."; input.click(); return; }
    if (file.size > 256 * 1024) { output.textContent = "JSON file must be under 256 KB."; return; }
    button.disabled = true;
    button.textContent = "Importing…";
    output.textContent = "Validating and creating your tasks. Please keep this page open.";
    try {
      const payload = JSON.parse(await file.text());
      const result = await window.OwnerLink.api("/api/tasks/import", { method: "POST", body: payload });
      const count = Array.isArray(result.tasks) ? result.tasks.length : 1;
      output.textContent = `${count} task(s) imported successfully. Opening Tasks…`;
      window.setTimeout(() => { window.location.href = "/tasks"; }, 600);
    } catch (error) {
      output.textContent = error instanceof SyntaxError ? "This file is not valid JSON." : `Import failed: ${error.message || "Server unavailable"}`;
      button.disabled = false;
      button.textContent = "Import JSON";
    }
  }

  button.addEventListener("click", importSelectedFile);
  form.addEventListener("submit", importSelectedFile);
})();
