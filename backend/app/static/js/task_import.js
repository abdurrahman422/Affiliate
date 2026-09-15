(function () {
  document.getElementById("task-import-form")?.addEventListener("submit", async (event) => {
    event.preventDefault(); const output = document.getElementById("task-import-result");
    const file = document.getElementById("task-json-file")?.files?.[0]; if (!file) return;
    if (file.size > 256 * 1024) { output.textContent = "JSON file must be under 256 KB."; return; }
    try { const result = await OwnerLink.api("/api/tasks/import", { method: "POST", body: JSON.parse(await file.text()) }); output.textContent = `${result.tasks?.length || 1} task(s) imported successfully.`; }
    catch (error) { output.textContent = error instanceof SyntaxError ? "This is not valid JSON." : error.message; }
  });
})();
