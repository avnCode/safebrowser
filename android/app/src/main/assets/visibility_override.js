/* SafeBrowser visibility override.
 *
 * When the user switches away from SafeBrowser with background playback
 * enabled, Android fires the Page Visibility API event
 * (document.visibilityState → "hidden").  Most video players listen for
 * this and voluntarily pause themselves.
 *
 * This script overrides the Visibility API so the page always thinks it's
 * visible.  Without this, the foreground service keeps the process alive
 * but the player pauses itself anyway.
 *
 * Injected at onPageStarted (before the player sets up its listeners).
 * Only injected when backgroundPlaybackEnabled is true.
 * Idempotent.
 */
(function () {
  if (window.__sbVisOverride) return;
  window.__sbVisOverride = true;

  // Lock visibilityState to "visible".
  Object.defineProperty(document, 'visibilityState', {
    get: function () { return 'visible'; },
    configurable: true
  });
  Object.defineProperty(document, 'hidden', {
    get: function () { return false; },
    configurable: true
  });

  // Swallow the visibilitychange event so listeners never fire.
  document.addEventListener('visibilitychange', function (e) {
    e.stopImmediatePropagation();
  }, true);
})();
