(() => {
  const list = document.getElementById("call-log-list");
  const status = document.getElementById("call-log-status");
  const more = document.getElementById("call-log-more");
  let cursor = 0, filter = "";
  const duration = seconds => { const value = Number(seconds || 0); return value < 60 ? `${value}s` : `${Math.floor(value / 60)}m ${value % 60}s`; };
  const safe = value => String(value || "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
  async function load(reset = false) {
    if (reset) { cursor = 0; list.replaceChildren(); }
    status.textContent = "Loading recent calls…"; more.classList.add("hidden");
    try {
      const query = new URLSearchParams({cursor, limit: 50}); if (filter) query.set("filter", filter);
      const response = await fetch(`/api/devices/${encodeURIComponent(window.OWNERLINK_DEVICE_ID)}/call-logs?${query}`);
      const body = await response.json(); if (!response.ok) throw new Error(body.error || body.message || "Call logs are unavailable.");
      const items = body.items || [];
      for (const item of items) {
        const card = document.createElement("article"); card.className = "card call-log-row";
        card.innerHTML = `<div><strong>${safe(item.display_name || item.number || "Unknown number")}</strong><p class="meta">${safe(item.display_name ? item.number : "")} ${safe(item.direction)}</p></div><div class="call-meta"><time>${new Date(Number(item.timestamp)).toLocaleString()}</time><span>${duration(item.duration_seconds)}</span></div>`;
        list.appendChild(card);
      }
      status.textContent = list.children.length ? `${list.children.length} recent calls` : "No call logs found.";
      cursor = body.next_cursor == null ? null : Number(body.next_cursor); more.classList.toggle("hidden", cursor == null);
    } catch (error) {
      const message = String(error.message || "");
      status.textContent = message.toLowerCase().includes("offline") ? "Device is offline." : message.toLowerCase().includes("permission") ? "Call Log permission is required on this device." : "Call logs could not be loaded. Try again.";
    }
  }
  document.querySelectorAll("[data-filter]").forEach(button => button.addEventListener("click", () => {
    document.querySelectorAll("[data-filter]").forEach(item => item.classList.remove("active")); button.classList.add("active"); filter = button.dataset.filter; load(true);
  }));
  more.addEventListener("click", () => load(false)); load(true);
})();
