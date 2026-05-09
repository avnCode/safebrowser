/* SafeBrowser overlay zapper v3.
 *
 * Persistent in-page overlay/modal/popup suppressor. Targets:
 *   - Floating cookie/consent/GDPR banners.
 *   - Newsletter / subscribe / signup modals.
 *   - Paywall / metering overlays.
 *   - "Open in app" / smart-app banners.
 *   - Login / sign-in prompts (Google one-tap, Facebook, etc.).
 *   - Exit-intent and scroll-trigger popups.
 *   - role="dialog", <dialog open>, custom modal-* elements.
 *   - Generic position:fixed/sticky elements in the popup-size range.
 *
 * v3 improvements over v2:
 *   - Keyword matches now ALSO require fixed/sticky + area check — no more
 *     blindly hiding anything with "modal" or "popup" in its class.
 *   - MIN_AREA lowered to 5% (catches small notification bars).
 *   - MAX_AREA added at 85% (skips full-page content like episode lists).
 *   - Elements containing <video>, dense <a> grids, or many <li> items are
 *     excluded (likely genuine interactive content).
 *   - Late-appearing elements (added after initial load) are treated with
 *     higher suspicion (lower z-index threshold).
 *
 * Idempotent.
 */
(function () {
  if (window.__sbOverlayZapper) return;
  window.__sbOverlayZapper = true;

  var SWEEP_THROTTLE_MS = 250;
  var MIN_AREA_RATIO    = 0.05;   // 5% of viewport
  var MAX_AREA_RATIO    = 0.85;   // 85% — skip full-page panels
  var MIN_Z_INDEX       = 100;
  var MIN_Z_INDEX_LATE  = 50;     // lower bar for elements added after load
  var pageLoaded        = false;
  var killedSignatures  = new Set();

  function viewport() {
    return { w: window.innerWidth || 0, h: window.innerHeight || 0 };
  }

  function signatureOf(el) {
    try {
      var id = el.id ? '#' + el.id : '';
      var cls = (el.className && typeof el.className === 'string')
        ? '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.')
        : '';
      return el.tagName + id + cls;
    } catch (e) { return ''; }
  }

  function hide(el) {
    if (!el || el.__sbHidden) return;
    el.__sbHidden = true;
    try {
      el.style.setProperty('display', 'none', 'important');
      el.style.setProperty('visibility', 'hidden', 'important');
      el.style.setProperty('opacity', '0', 'important');
      el.style.setProperty('pointer-events', 'none', 'important');
      var sig = signatureOf(el);
      if (sig) killedSignatures.add(sig);
    } catch (e) {}
  }

  /** Returns true if the element looks like genuine interactive content. */
  function isInteractiveContent(el) {
    try {
      // Contains a video player — don't touch.
      if (el.querySelector('video, audio')) return true;
      // Dense link grid (e.g. episode list, nav menu with many items).
      var links = el.querySelectorAll('a');
      if (links.length > 5) return true;
      // Many list items — likely a menu or episode list.
      var items = el.querySelectorAll('li');
      if (items.length > 5) return true;
      // Contains an <input> or <textarea> (login/search form is tricky but
      // a large form is likely the page's real UI, not a nag popup).
      var inputs = el.querySelectorAll('input, textarea, select');
      if (inputs.length > 2) return true;
    } catch (e) {}
    return false;
  }

  function isOverlayCandidate(el, useLateThreshold) {
    if (!el || !(el instanceof HTMLElement)) return false;
    if (el.__sbHidden) return false;
    var cs;
    try { cs = getComputedStyle(el); } catch (e) { return false; }
    if (!cs || cs.display === 'none' || cs.visibility === 'hidden') return false;
    var pos = cs.position;
    if (pos !== 'fixed' && pos !== 'sticky') return false;
    var z = parseInt(cs.zIndex, 10);
    if (isNaN(z)) z = 0;
    var zThreshold = useLateThreshold ? MIN_Z_INDEX_LATE : MIN_Z_INDEX;
    if (z < zThreshold) return false;
    var r;
    try { r = el.getBoundingClientRect(); } catch (e) { return false; }
    var v = viewport();
    if (v.w === 0 || v.h === 0) return false;
    var area = (r.width * r.height) / (v.w * v.h);
    // Skip elements that are too large (likely full-page content panels).
    if (area > MAX_AREA_RATIO) return false;
    // Skip genuine interactive content.
    if (isInteractiveContent(el)) return false;

    if (area >= MIN_AREA_RATIO) return true;
    // Wide-and-short banner stuck to top/bottom (cookie strip).
    if (r.width >= v.w * 0.9 && r.height >= 40 && r.height <= v.h * 0.35 &&
        (Math.abs(r.top) < 8 || Math.abs(r.bottom - v.h) < 8)) {
      return true;
    }
    return false;
  }

  // Keyword selectors — these match common overlay class/id patterns, but
  // v3 now requires them to ALSO pass the fixed/sticky + area check.
  var MATCH_SELECTORS = [
    '[id*="cookie" i]', '[class*="cookie" i]',
    '[id*="consent" i]', '[class*="consent" i]',
    '[id*="gdpr" i]', '[class*="gdpr" i]',
    '[id*="paywall" i]', '[class*="paywall" i]',
    '[id*="newsletter" i]', '[class*="newsletter" i]',
    '[id*="subscribe-modal" i]', '[class*="subscribe-modal" i]',
    '[id*="signup-modal" i]', '[class*="signup-modal" i]',
    '[id*="signin-modal" i]', '[class*="signin-modal" i]',
    '[id*="login-modal" i]', '[class*="login-modal" i]',
    '[id*="app-banner" i]', '[class*="app-banner" i]',
    '[id*="smart-banner" i]', '[class*="smart-banner" i]',
    '[id*="open-in-app" i]', '[class*="open-in-app" i]',
    '[id*="exit-intent" i]', '[class*="exit-intent" i]',
    '[id*="popup" i]', '[class*="popup" i]',
    '[id*="modal" i]', '[class*="modal" i]',
    '[id*="overlay" i]', '[class*="overlay" i]',
    '[id*="lightbox" i]', '[class*="lightbox" i]',
    '[role="dialog"]', '[role="alertdialog"]',
    'dialog[open]',
    '#credential_picker_container',
    '#credential_picker_iframe',
    'div[aria-label*="Sign in" i]',
    'div[aria-label*="Subscribe" i]',
    '.fc-consent-root', '.qc-cmp2-container'
  ].join(',');

  function sweep() {
    var v = viewport();
    if (v.w === 0 || v.h === 0) return;
    var useLate = pageLoaded;

    // Re-kill previously suppressed elements that got re-injected.
    if (killedSignatures.size) {
      try {
        document.querySelectorAll('body *').forEach(function (el) {
          if (el.__sbHidden) return;
          var sig = signatureOf(el);
          if (sig && killedSignatures.has(sig)) hide(el);
        });
      } catch (e) {}
    }

    // Keyword matches — now gated on fixed/sticky + area check.
    try {
      document.querySelectorAll(MATCH_SELECTORS).forEach(function (el) {
        if (el.__sbHidden) return;
        // Must be fixed/sticky AND in the overlay size range.
        if (isOverlayCandidate(el, useLate)) {
          hide(el);
        }
      });
    } catch (e) {}

    // Generic scan for fixed/sticky overlays by area.
    try {
      document.querySelectorAll('body *').forEach(function (el) {
        if (isOverlayCandidate(el, useLate)) hide(el);
      });
    } catch (e) {}

    // Restore scroll if the page locked the body (common with modals).
    try {
      var bs = getComputedStyle(document.body);
      if (bs.overflow === 'hidden' || bs.position === 'fixed') {
        document.documentElement.style.setProperty('overflow', 'auto', 'important');
        document.body.style.setProperty('overflow', 'auto', 'important');
        document.documentElement.style.removeProperty('position');
        document.body.style.removeProperty('position');
      }
    } catch (e) {}
  }

  var pending = false;
  function scheduleSweep() {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; sweep(); }, SWEEP_THROTTLE_MS);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      sweep();
      // Mark page as loaded — late-appearing elements get lower z threshold.
      setTimeout(function () { pageLoaded = true; }, 3000);
    }, { once: true });
  } else {
    sweep();
    setTimeout(function () { pageLoaded = true; }, 3000);
  }

  try {
    var obs = new MutationObserver(scheduleSweep);
    obs.observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}

  ['scroll', 'mousemove', 'touchstart', 'visibilitychange'].forEach(function (ev) {
    try { window.addEventListener(ev, scheduleSweep, { passive: true, capture: true }); }
    catch (e) {}
  });

  setInterval(scheduleSweep, 4000);
})();
