// Applies the theme saved in this browser before the page is drawn, so the wrong theme never flashes (ADR-162).
// A same-origin classic script, allowed by the Content-Security-Policy (script-src 'self'). Same key and values as
// src/lib/theme.ts; no choice, or blocked storage, leaves the operating-system setting in force.
;(function () {
  try {
    var theme = window.localStorage.getItem('ims.theme')
    if (theme === 'light' || theme === 'dark') {
      document.documentElement.setAttribute('data-theme', theme)
    }
  } catch {
    // Storage blocked: follow the operating system.
  }
})()
