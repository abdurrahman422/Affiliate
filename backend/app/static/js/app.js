window.OwnerLink = {
  csrf() {
    const el = document.querySelector('meta[name="csrf-token"]');
    return el ? el.getAttribute("content") : "";
  },
  async api(url, options = {}) {
    const headers = Object.assign({ "X-CSRF-Token": this.csrf() }, options.headers || {});
    if (options.body && typeof options.body === "object" && !(options.body instanceof FormData)) {
      headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(options.body);
    }
    const res = await fetch(url, Object.assign({}, options, { headers, credentials: "same-origin" }));
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = { raw: text }; }
    if (res.status === 401) {
      window.location.href = "/login";
      throw new Error("Authentication required");
    }
    if (!res.ok) {
      const err = new Error((data && (data.error || data.detail)) || res.statusText);
      err.code = data && data.code;
      err.status = res.status;
      throw err;
    }
    return data;
  },
  formatSize(n) {
    if (n == null || n === "") return "";
    const units = ["B", "KB", "MB", "GB"];
    let v = Number(n);
    let i = 0;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return `${v.toFixed(i ? 1 : 0)} ${units[i]}`;
  }
};
