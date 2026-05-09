/* SafeBrowser overlay zapper v4.
 *
 * Persistent in-page overlay/modal/popup suppressor. Targets:
 *   - Floating cookie/consent/GDPR banners.
 *   - Newsletter / subscribe / signup modals.
 *   - Paywall / metering overlays.
 *   - "Open in app" / smart-app banners.
 *   - Login / sign-in prompts (Google one-tap, Facebook, etc.).
 *   - Exit-intent and scroll-trigger popups.
 *   - Scam/gambling/prize overlays ("Congratulations", "bonus", etc.).
 *   - Push-notification bell/chat widgets (small corner FABs).
 *   - role="dialog", <dialog open>, custom modal-* elements.
 *   - Generic position:fixed/sticky elements in the popup-size range.
 *
 * v4 improvements over v3:
 *   - Added scam/gambling keyword selectors and text-content sniffing for
 *     "congratulations", "bonus", "prize", "winner", "claim" etc.
 *   - Small floating corner widgets (bell icons, chat bubbles) detected by
 *     position + size + corner location — no keyword needed.
 *   - Late-appearing elements (injected >3s after load) get a much lower
 *     z-index bar AND lower area minimum (2%) since ads inject late.
 *   - Backdrop/scrim detection: semi-transparent full-screen overlays that
 *     dim the page behind a popup.
 *
 * Idempotent.
 */
(function () {
  if (window.__sbOverlayZapper) return;
  window.__sbOverlayZapper = true;

  var SWEEP_THROTTLE_MS = 250;
  var MIN_AREA_RATIO      = 0.05;   // 5% of viewport (initial load)
  var MIN_AREA_RATIO_LATE = 0.02;   // 2% for late-injected elements
  var MAX_AREA_RATIO      = 0.85;   // skip full-page content panels
  var MIN_Z_INDEX         = 100;
  var MIN_Z_INDEX_LATE    = 10;     // very low bar for late elements
  var pageLoaded          = false;
  var killedSignatures    = new Set();

  // Text patterns that strongly indicate scam/nag popups.
  var SCAM_PATTERNS = /congratulat|bonus.{0,10}approved|you.{0,5}won|claim.{0,10}(prize|reward|bonus)|spin.{0,5}wheel|lucky.{0,5}visitor|winner|free.{0,5}gift/i;

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
      if (el.querySelector('video, audio')) return true;
      var links = el.querySelectorAll('a');
      if (links.length > 5) return true;
      var items = el.querySelectorAll('li');
      if (items.length > 5) return true;
      var inputs = el.querySelectorAll('input, textarea, select');
      if (inputs.length > 2) return true;
    } catch (e) {}
    return false;
  }

  /** Check if a fixed/sticky element has scam/nag text content. */
  function hasScamText(el) {
    try {
      var text = (el.innerText || '').substring(0, 300);
      return SCAM_PATTERNS.test(text);
    } catch (e) { return false; }
  }

  /**
   * Detect small floating corner widgets: notification bells, chat bubbles,
   * "back to top" nags, etc.
   */
  function isCornerWidget(el) {
    if (!el || !(el instanceof HTMLElement)) return false;
    if (el.__sbHidden) return false;
    var cs;
    try { cs = getComputedStyle(el); } catch (e) { return false; }
    if (!cs || cs.display === 'none') return false;
    if (cs.position !== 'fixed') return false;
    var z = parseInt(cs.zIndex, 10);
    if (isNaN(z)) z = 0;
    if (z < 1) return false;
    var r;
    try { r = el.getBoundingClientRect(); } catch (e) { return false; }
    var v = viewport();
    if (v.w === 0 || v.h === 0) return false;
    var area = (r.width * r.height) / (v.w * v.h);
    if (r.width > 120 || r.height > 120 || area > 0.02) return false;
    if (r.width < 20 || r.height < 20) return false;
    var nearBottom = (v.h - r.bottom) < 30 || r.bottom > v.h - 30;
    var nearSide   = r.left < 30 || (v.w - r.right) < 30;
    return nearBottom && nearSide;
  }

  /**
   * Detect semi-transparent full-screen backdrop/scrim overlays that dim the
   * page behind a popup.
   */
  function isBackdropScrim(el) {
    if (!el || !(el instanceof HTMLElement)) return false;
    if (el.__sbHidden) return false;
    var cs;
    try { cs = getComputedStyle(el); } catch (e) { return false; }
    if (!cs) return false;
    if (cs.position !== 'fixed') return false;
    var r;
    try { r = el.getBoundingClientRect(); } catch (e) { return false; }
    var v = viewport();
    if (v.w === 0 || v.h === 0) return false;
    var area = (r.width * r.height) / (v.w * v.h);
    if (area < 0.9) return false;
    var opacity = parseFloat(cs.opacity);
    if (opacity > 0 && opacity < 0.95) return true;
    var bg = cs.backgroundColor || '';
    if (/rgba\(\s*\d+.*,\s*(0\.\d+)\s*\)/.test(bg)) {
      var alpha = parseFloat(RegExp.$1);
      if (alpha > 0 && alpha < 0.95) return true;
    }
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

    var minArea = useLateThreshold ? MIN_AREA_RATIO_LATE : MIN_AREA_RATIO;
    if (area >= minArea) return true;
    // Wide-and-short banner stuck to top/bottom (cookie strip).
    if (r.width >= v.w * 0.9 && r.height >= 40 && r.height <= v.h * 0.35 &&
        (Math.abs(r.top) < 8 || Math.abs(r.bottom - v.h) < 8)) {
      return true;
    }
    return false;
  }

  // Keyword selectors — gated on fixed/sticky + area check.
  var MATCH_SELECTORS = [
    // Privacy / consent
    '[id*="cookie" i]', '[class*="cookie" i]',
    '[id*="consent" i]', '[class*="consent" i]',
    '[id*="gdpr" i]', '[class*="gdpr" i]',
    // Paywall
    '[id*="paywall" i]', '[class*="paywall" i]',
    // Newsletter / subscribe
    '[id*="newsletter" i]', '[class*="newsletter" i]',
    '[id*="subscribe-modal" i]', '[class*="subscribe-modal" i]',
    // Auth nags
    '[id*="signup-modal" i]', '[class*="signup-modal" i]',
    '[id*="signin-modal" i]', '[class*="signin-modal" i]',
    '[id*="login-modal" i]', '[class*="login-modal" i]',
    // App install banners
    '[id*="app-banner" i]', '[class*="app-banner" i]',
    '[id*="smart-banner" i]', '[class*="smart-banner" i]',
    '[id*="open-in-app" i]', '[class*="open-in-app" i]',
    // Generic popups/modals/overlays
    '[id*="exit-intent" i]', '[class*="exit-intent" i]',
    '[id*="popup" i]', '[class*="popup" i]',
    '[id*="modal" i]', '[class*="modal" i]',
    '[id*="overlay" i]', '[class*="overlay" i]',
    '[id*="lightbox" i]', '[class*="lightbox" i]',
    // Semantic roles
    '[role="dialog"]', '[role="alertdialog"]',
    'dialog[open]',
    // Google one-tap
    '#credential_picker_container',
    '#credential_picker_iframe',
    'div[aria-label*="Sign in" i]',
    'div[aria-label*="Subscribe" i]',
    '.fc-consent-root', '.qc-cmp2-container',
    // Scam / gambling / prize nags
    '[id*="bonus" i]', '[class*="bonus" i]',
    '[id*="prize" i]', '[class*="prize" i]',
    '[id*="reward" i]', '[class*="reward" i]',
    '[id*="winner" i]', '[class*="winner" i]',
    '[id*="spin" i]', '[class*="spin-wheel" i]',
    '[id*="lucky" i]', '[class*="lucky" i]',
    // Notification / chat nags
    '[id*="push-notification" i]', '[class*="push-notification" i]',
    '[id*="web-push" i]', '[class*="web-push" i]',
    '[id*="notification-bell" i]', '[class*="notification-bell" i]',
    '[id*="chat-widget" i]', '[class*="chat-widget" i]',
    '[id*="livechat" i]', '[class*="livechat" i]',
    '[id*="intercom" i]', '[class*="intercom" i]',
    '[id*="crisp" i]', '[class*="crisp-client" i]',
    '[id*="tidio" i]', '[class*="tidio" i]',
    '[id*="tawk" i]', '[class*="tawk" i]',
    '[id*="zendesk" i]', '[class*="zendesk" i]'
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

    // Keyword matches — gated on fixed/sticky + area check.
    try {
      document.querySelectorAll(MATCH_SELECTORS).forEach(function (el) {
        if (el.__sbHidden) return;
        if (isOverlayCandidate(el, useLate)) hide(el);
      });
    } catch (e) {}

    // Generic scan for fixed/sticky overlays + special detectors.
    try {
      document.querySelectorAll('body *').forEach(function (el) {
        if (el.__sbHidden) return;
        if (isOverlayCandidate(el, useLate)) { hide(el); return; }
        // Scam text sniffing: fixed/sticky element with scam text, any size.
        if (useLate) {
          try {
            var cs = getComputedStyle(el);
            if (cs && (cs.position === 'fixed' || cs.position === 'sticky')) {
              if (hasScamText(el)) { hide(el); return; }
            }
          } catch (e) {}
        }
        // Corner widget detection (bell icons, chat buttons).
        if (isCornerWidget(el)) { hide(el); return; }
        // Backdrop scrim detection.
        if (isBackdropScrim(el)) hide(el);
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
