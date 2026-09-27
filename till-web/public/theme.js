// Applied before the first paint, so a light-themed store does not flash dark on load, or the
// reverse. A file rather than an inline script so the edge's Content-Security-Policy can forbid inline
// scripts outright. The theme switch writes this key; everything else about theming is in CSS.
try {
  var stored = localStorage.getItem('till.theme')
  if (stored === 'light' || stored === 'dark') {
    document.documentElement.setAttribute('data-theme', stored)
  }
} catch (ignored) {
  // Storage denied in a private window. The operating system's setting still applies.
}
