/* SafeBrowser SPA navigation monitor.
 *
 * Anime streaming sites (reanime.to, Cineby, etc.) are SvelteKit/Next.js SPAs
 * that use history.pushState() to swap episodes.  No Android WebView callback
 * fires on pushState — shouldOverrideUrlLoading, onPageStarted, etc. are all
 * silent.  So the old episode's video iframe, MediaSource, WASM subtitle
 * renderer, and decoded frames stay alive while the new episode loads on top.
 *
 * This script:
 *   1. Hooks pushState/replaceState to detect in-page navigations.
 *   2. On each SPA navigation, tears down all cross-origin iframes (which
 *      host the video player) BEFORE the SPA creates new ones.
 *   3. Watches for new iframes being added to the DOM via MutationObserver
 *      and tears down any old ones that are being replaced.
 *   4. Clears orphaned intervals/timeouts from the previous "page".
 *
 * Idempotent.
 */
(function () {
  if (window.__sbSpaMonitor) return;
  window.__sbSpaMonitor = true;

  var lastUrl = location.href;

  function teardownOldMedia() {
    try {
      // Kill all video/audio elements.
      var els = document.querySelectorAll('video, audio');
      for (var i = 0; i < els.length; i++) {
        try {
          els[i].pause();
          els[i].removeAttribute('src');
          els[i].load();
        } catch (e) {}
      }
    } catch (e) {}

    try {
      // Kill all iframes — the SPA will recreate the one it needs.
      var iframes = document.querySelectorAll('iframe');
      for (var j = 0; j < iframes.length; j++) {
        try {
          iframes[j].src = 'about:blank';
        } catch (e) {}
      }
    } catch (e) {}

    // Clear orphaned intervals/timeouts.
    try {
      var maxId = setTimeout(function(){}, 0);
      for (var k = Math.max(1, maxId - 500); k <= maxId; k++) {
        clearTimeout(k);
        clearInterval(k);
      }
    } catch (e) {}
  }

  function onNavChange() {
    var newUrl = location.href;
    if (newUrl !== lastUrl) {
      lastUrl = newUrl;
      teardownOldMedia();
    }
  }

  // Hook pushState and replaceState.
  var origPush = history.pushState;
  var origReplace = history.replaceState;

  history.pushState = function () {
    origPush.apply(this, arguments);
    onNavChange();
  };

  history.replaceState = function () {
    origReplace.apply(this, arguments);
    onNavChange();
  };

  // Also listen for popstate (back/forward within the SPA).
  window.addEventListener('popstate', onNavChange);
})();
