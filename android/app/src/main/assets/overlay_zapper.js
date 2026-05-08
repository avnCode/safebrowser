/* SafeBrowser overlay zapper: hide common modal/cookie/sticky overlays. */
(function () {
  if (window.__safeBrowserOverlayZapped) return;
  window.__safeBrowserOverlayZapped = true;

  function viewport() {
    return { w: window.innerWidth || 0, h: window.innerHeight || 0 };
  }

  function isOverlayCandidate(el) {
    if (!el || !(el instanceof HTMLElement)) return false;
    const cs = getComputedStyle(el);
    if (cs.display === "none" || cs.visibility === "hidden") return false;
    const pos = cs.position;
    if (pos !== "fixed" && pos !== "sticky") return false;
    const z = parseInt(cs.zIndex, 10);
    if (isNaN(z) || z < 100) return false; // skip nav bars / chrome
    const r = el.getBoundingClientRect();
    const v = viewport();
    if (v.w === 0 || v.h === 0) return false;
    const coverArea = (r.width * r.height) / (v.w * v.h);
    // Big floating element OR a translucent backdrop.
    if (coverArea >= 0.5) return true;
    if (
      r.width >= v.w * 0.9 &&
      r.height >= v.h * 0.4 &&
      cs.backgroundColor &&
      cs.backgroundColor !== "rgba(0, 0, 0, 0)"
    )
      return true;
    return false;
  }

  function nuke() {
    let removed = 0;
    document.querySelectorAll("body *").forEach(function (el) {
      if (isOverlayCandidate(el)) {
        el.style.setProperty("display", "none", "important");
        removed++;
      }
    });
    // Common cookie / consent / signup / paywall selectors.
    const sel = [
      '[id*="cookie" i]',
      '[class*="cookie" i]',
      '[id*="consent" i]',
      '[class*="consent" i]',
      '[id*="paywall" i]',
      '[class*="paywall" i]',
      '[id*="newsletter" i]',
      '[class*="newsletter" i]',
      '[id*="signup-modal" i]',
      '[class*="signup-modal" i]',
      '[role="dialog"]',
      "dialog[open]",
    ].join(",");
    try {
      document.querySelectorAll(sel).forEach(function (el) {
        const cs = getComputedStyle(el);
        if (cs.position === "fixed" || cs.position === "sticky" || el.tagName === "DIALOG") {
          el.style.setProperty("display", "none", "important");
          removed++;
        }
      });
    } catch (e) {}
    // Restore body scroll if the page had locked it.
    document.documentElement.style.removeProperty("overflow");
    document.body.style.removeProperty("overflow");
    document.documentElement.style.setProperty("overflow", "auto", "important");
    document.body.style.setProperty("overflow", "auto", "important");
    return removed;
  }

  // Run now and on later DOM mutations (sites that re-show modals).
  nuke();
  try {
    let scheduled = false;
    const obs = new MutationObserver(function () {
      if (scheduled) return;
      scheduled = true;
      setTimeout(function () {
        scheduled = false;
        nuke();
      }, 400);
    });
    obs.observe(document.documentElement, { childList: true, subtree: true });
    setTimeout(function () { obs.disconnect(); }, 15000); // stop after 15s
  } catch (e) {}
})();
