/* SafeBrowser overlay zapper v2.
 *
 * Persistent in-page overlay/modal/popup suppressor. Runs as long as the page
 * is open. Targets:
 *   - Floating cookie/consent/GDPR banners (top, bottom, sticky).
 *   - Newsletter / subscribe / signup modals.
 *   - Paywall / metering overlays.
 *   - "Open in app" / smart-app banners.
 *   - Login / sign-in prompts (Google one-tap, Facebook, etc.).
 *   - Exit-intent and scroll-trigger popups.
 *   - role="dialog", <dialog open>, custom modal-* elements.
 *   - Generic position:fixed/sticky elements covering >=25% viewport.
 *
 * Strategy:
 *   1. Initial sweep on install.
 *   2. Throttled MutationObserver: re-sweep on DOM changes (no time limit).
 *   3. Remember each suppressed element's signature so re-injected nodes are
 *      killed instantly on next sweep.
 *   4. Re-sweep on scroll / touch / visibility change for trigger popups.
 *   5. Restore body overflow so locked pages stay scrollable.
 *
 * Idempotent.
 */
(function () {
  if (window.__sbOverlayZapper) return;
  window.__sbOverlayZapper = true;

  var SWEEP_THROTTLE_MS = 250;
  var MIN_AREA_RATIO    = 0.25;
  var MIN_Z_INDEX       = 100;
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

  function isOverlayCandidate(el) {
    if (!el || !(el instanceof HTMLElement)) return false;
    if (el.__sbHidden) return false;
    var cs;
    try { cs = getComputedStyle(el); } catch (e) { return false; }
    if (!cs || cs.display === 'none' || cs.visibility === 'hidden') return false;
    var pos = cs.position;
    if (pos !== 'fixed' && pos !== 'sticky') return false;
    var z = parseInt(cs.zIndex, 10);
    if (isNaN(z)) z = 0;
    if (z < MIN_Z_INDEX) return false;
    var r;
    try { r = el.getBoundingClientRect(); } catch (e) { return false; }
    var v = viewport();
    if (v.w === 0 || v.h === 0) return false;
    var area = (r.width * r.height) / (v.w * v.h);
    if (area >= MIN_AREA_RATIO) return true;
    // Wide-and-short banner stuck to top/bottom (cookie strip).
    if (r.width >= v.w * 0.9 && r.height >= 40 && r.height <= v.h * 0.35 &&
        (Math.abs(r.top) < 8 || Math.abs(r.bottom - v.h) < 8)) {
      return true;
    }
    return false;
  }

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

    if (killedSignatures.size) {
      try {
        document.querySelectorAll('body *').forEach(function (el) {
          if (el.__sbHidden) return;
          var sig = signatureOf(el);
          if (sig && killedSignatures.has(sig)) hide(el);
        });
      } catch (e) {}
    }

    try {
      document.querySelectorAll(MATCH_SELECTORS).forEach(function (el) {
        if (el.__sbHidden) return;
        var cs;
        try { cs = getComputedStyle(el); } catch (e) { return; }
        if (!cs) return;
        var pos = cs.position;
        if (pos === 'fixed' || pos === 'sticky' ||
            el.tagName === 'DIALOG' ||
            el.getAttribute('role') === 'dialog' ||
            el.getAttribute('role') === 'alertdialog') {
          hide(el);
        }
      });
    } catch (e) {}

    try {
      document.querySelectorAll('body *').forEach(function (el) {
        if (isOverlayCandidate(el)) hide(el);
      });
    } catch (e) {}

    try {
      document.documentElement.style.setProperty('overflow', 'auto', 'important');
      document.body.style.setProperty('overflow', 'auto', 'important');
      document.documentElement.style.removeProperty('position');
      document.body.style.removeProperty('position');
    } catch (e) {}
  }

  var pending = false;
  function scheduleSweep() {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; sweep(); }, SWEEP_THROTTLE_MS);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', sweep, { once: true });
  } else {
    sweep();
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
