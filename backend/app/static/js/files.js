(function () {
  const id = window.OWNERLINK_DEVICE_ID;
  const body = document.getElementById("file-body");
  const status = document.getElementById("files-status");
  const pathBar = document.getElementById("path-bar");
  const parentButton = document.getElementById("files-parent");
  const stack = [{ id: null, name: "Storage" }];

  function downloadUrl(fileId) {
    return `/api/devices/${id}/download?file=${encodeURIComponent(fileId)}`;
  }

  function renderPath() {
    pathBar.innerHTML = "";
    stack.forEach((part, idx) => {
      if (idx) pathBar.appendChild(document.createTextNode(" / "));
      const a = document.createElement("a");
      a.href = "#";
      a.textContent = part.name;
      a.addEventListener("click", (ev) => {
        ev.preventDefault();
        stack.splice(idx + 1);
        load();
      });
      pathBar.appendChild(a);
    });
    parentButton.hidden = stack.length <= 1;
  }

  async function load() {
    const folder = stack[stack.length - 1].id;
    status.textContent = "Loading…";
    body.innerHTML = "";
    renderPath();
    try {
      const q = folder ? `?folder=${encodeURIComponent(folder)}` : "";
      const data = await OwnerLink.api(`/api/devices/${id}/files${q}`);
      const items = data.items || [];
      if (!items.length) status.textContent = data.message || "This folder is empty or file access is not granted on the phone.";
      else status.textContent = "";
      for (const item of items) {
        const tr = document.createElement("tr");
        const nameTd = document.createElement("td");
        const typeTd = document.createElement("td");
        const sizeTd = document.createElement("td");
        const modifiedTd = document.createElement("td");
        const actTd = document.createElement("td");
        if (item.directory) {
          const a = document.createElement("a");
          a.href = "#";
          a.textContent = item.name + "/";
          a.addEventListener("click", (ev) => {
            ev.preventDefault();
            stack.push({ id: item.id, name: item.name });
            load();
          });
          nameTd.appendChild(a);
          typeTd.textContent = "Folder";
        } else {
          nameTd.textContent = item.name;
          typeTd.textContent = item.mime || "File";
          const a = document.createElement("a");
          a.href = downloadUrl(item.id);
          a.textContent = "Download";
          a.setAttribute("download", item.name || "download");
          actTd.appendChild(a);
        }
        sizeTd.textContent = item.directory ? "" : OwnerLink.formatSize(item.size);
        modifiedTd.textContent = item.modified ? new Date(item.modified).toLocaleString() : "—";
        tr.append(nameTd, typeTd, sizeTd, modifiedTd, actTd);
        body.appendChild(tr);
      }
    } catch (err) {
      status.textContent = err.message;
    }
  }

  parentButton.addEventListener("click", () => {
    if (stack.length > 1) { stack.pop(); load(); }
  });

  load();
})();
