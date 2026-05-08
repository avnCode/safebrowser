/*
 * SafeBrowser seek throttle.
 *
 * Rapid video scrubbing writes `video.currentTime` dozens of times per
 * second. Each write triggers a MediaSource flush and a MediaCodec re-seek
 * which allocates and frees a GPU output surface. Bursts of those calls
 * exhaust the renderer's GPU surface pool and crash the renderer.
 *
 * We wrap HTMLMediaElement's currentTime setter to coalesce writes inside
 * a ~120 ms window: store the latest value, schedule a single rAF-aligned
 * flush, ignore intermediate writes. Reads always return the real time.
 *
 * Idempotent: a second injection is a no-op.
 */
(function () {
  if (window.__sbSeekThrottle) return;
  window.__sbSeekThrottle = true;

  try {
    var proto = HTMLMediaElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'currentTime');
    if (!desc || !desc.set || !desc.get) return;

    var origSet = desc.set;
    var origGet = desc.get;
    var WINDOW_MS = 120;

    Object.defineProperty(proto, 'currentTime', {
      configurable: true,
      enumerable: desc.enumerable,
      get: function () { return origGet.call(this); },
      set: function (v) {
        var media = this;
        // First write or no pending flush -> apply immediately.
        if (!media.__sbPending) {
          origSet.call(media, v);
          media.__sbPending = true;
          media.__sbLast = v;
          setTimeout(function () {
            try {
              if (media.__sbLast !== undefined &&
                  Math.abs(media.__sbLast - origGet.call(media)) > 0.05) {
                origSet.call(media, media.__sbLast);
              }
            } catch (e) {}
            media.__sbPending = false;
            media.__sbLast = undefined;
          }, WINDOW_MS);
        } else {
          // Coalesce: remember latest target, drop intermediates.
          media.__sbLast = v;
        }
      }
    });
  } catch (e) {}
})();
