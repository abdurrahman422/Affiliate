(function () {
  const id = window.OWNERLINK_DEVICE_ID;
  const grid = document.getElementById("gallery-grid");
  const status = document.getElementById("gallery-status");
  const more = document.getElementById("load-more");
  const dialog = document.getElementById("preview");
  let cursor = null;
  let loading = false;

  function thumbUrl(item) {
    return `/api/devices/${id}/thumb?file=${encodeURIComponent(item.id)}`;
  }
  function downloadUrl(item) {
    return `/api/devices/${id}/download?file=${encodeURIComponent(item.id)}`;
  }

  function addItem(item) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "thumb";
    const img = document.createElement("img");
    img.alt = item.name || "media";
    img.loading = "lazy";
    img.src = thumbUrl(item);
    btn.appendChild(img);
    btn.addEventListener("click", () => {
      const preview = document.getElementById("preview-img");
      preview.src = item.is_video ? thumbUrl(item) : downloadUrl(item);
      const dl = document.getElementById("preview-download");
      dl.href = downloadUrl(item);
      dl.setAttribute("download", item.name || "download");
      dialog.showModal();
    });
    grid.appendChild(btn);
  }

  async function load() {
    if (loading) return;
    loading = true;
    status.textContent = "Loading…";
    try {
      const q = cursor ? `&cursor=${encodeURIComponent(cursor)}` : "";
      const data = await OwnerLink.api(`/api/devices/${id}/gallery?limit=60${q}`);
      const items = data.items || [];
      items.forEach(addItem);
      cursor = data.cursor || null;
      more.classList.toggle("hidden", !data.has_more);
      status.textContent = items.length || grid.children.length ? "" : "No media found, or photos permission is missing on the phone.";
    } catch (err) {
      status.textContent = err.message;
    } finally {
      loading = false;
    }
  }

  more.addEventListener("click", load);
  document.getElementById("preview-close").addEventListener("click", () => dialog.close());
  load();
})();
