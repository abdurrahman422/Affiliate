/* Local QR decoder fallback.
 * Pairing prefers the browser BarcodeDetector API. This stub keeps the
 * page working when jsQR is not replaced with the full library.
 */
(function (root) {
  if (typeof root.jsQR === "function") return;
  root.jsQR = function jsQR() { return null; };
})(typeof window !== "undefined" ? window : this);
