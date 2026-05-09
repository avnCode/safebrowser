/*
 * SafeBrowser MSE buffer cap.
 *
 * MediaSource Extensions players (YouTube, Twitch, Vimeo, most modern
 * adaptive-bitrate sites) call sourceBuffer.appendBuffer() aggressively to
 * pre-buffer 60-120s ahead. On desktop Chrome that is fine; on Android
 * WebView the SourceBuffer + decoded frame queue live in the same shared
 * renderer process. Once the forward buffer exceeds ~30s the compositor's
 * tile budget overflows and the page goes blank (renderer does not crash,
 * so onRenderProcessGone never fires - it just loses its frame).
 *
 * We wrap SourceBuffer.appendBuffer to defer appends when the buffer is
 * already MAX_AHEAD_S ahead of currentTime. Deferred appends retry on the
 * media element's `timeupdate`, so playback continues smoothly and the
 * buffer is held to a stable ceiling.
 *
 * Idempotent: re-injection is a no-op.
 */
(function () {
  if (window.__sbBufferCap) return;
  window.__sbBufferCap = true;

  try {
    if (typeof SourceBuffer === 'undefined' || typeof MediaSource === 'undefined') return;

    var MAX_AHEAD_S = 25;     // hard cap on forward buffer
    var RETRY_MS    = 250;    // poll interval for deferred appends

    // Track which <video>/<audio> element a given MediaSource is attached to,
    // so we can read currentTime when deciding whether to defer.
    var msToMedia = new WeakMap();

    var origAttach = URL.createObjectURL;
    URL.createObjectURL = function (obj) {
      var url = origAttach.apply(this, arguments);
      if (obj instanceof MediaSource) {
        // Find the media element that attaches this URL.  We hook
        // HTMLMediaElement.src setter below to record the link.
        try { obj.__sbUrl = url; } catch (e) {}
      }
      return url;
    };

    var mediaProto = HTMLMediaElement.prototype;
    var srcDesc = Object.getOwnPropertyDescriptor(mediaProto, 'src');
    if (srcDesc && srcDesc.set) {
      var origSrcSet = srcDesc.set;
      Object.defineProperty(mediaProto, 'src', {
        configurable: true,
        enumerable: srcDesc.enumerable,
        get: srcDesc.get,
        set: function (v) {
          // Look up MediaSource by blob URL we just stamped.
          try {
            // Best effort: scan recent MediaSources.  We instead rely on
            // setting __sbMedia on the SourceBuffer when addSourceBuffer is
            // called; see below.
          } catch (e) {}
          origSrcSet.call(this, v);
        }
      });
    }

    // When a SourceBuffer is created, remember its parent MediaSource and
    // (lazily) the media element it feeds.
    var origAddSB = MediaSource.prototype.addSourceBuffer;
    MediaSource.prototype.addSourceBuffer = function (mime) {
      var sb = origAddSB.call(this, mime);
      try { sb.__sbParent = this; } catch (e) {}
      // Track for the periodic backward-eviction sweep.
      try { liveSourceBuffers.push(sb); } catch (e) {}
      return sb;
    };

    // ---- Backward buffer cap ----
    // The player itself usually evicts past data, but some custom players
    // do not.  Keep at most KEEP_BEHIND_S of already-played buffer to bound
    // renderer-resident video memory regardless of watch duration.
    var KEEP_BEHIND_S = 10;
    var SWEEP_MS      = 10000;
    var liveSourceBuffers = [];

    function sweepBackward() {
      try {
        for (var i = liveSourceBuffers.length - 1; i >= 0; i--) {
          var sb = liveSourceBuffers[i];
          // Drop dead refs.
          if (!sb || !sb.__sbParent) { liveSourceBuffers.splice(i, 1); continue; }
          if (sb.updating) continue;
          var media = findMediaForMS(sb.__sbParent);
          if (!media) continue;
          var t = media.currentTime;
          if (!isFinite(t) || t <= KEEP_BEHIND_S) continue;
          var ranges = sb.buffered;
          if (!ranges || ranges.length === 0) continue;
          var firstStart = ranges.start(0);
          var cutoff = t - KEEP_BEHIND_S;
          if (cutoff > firstStart + 1) {
            try { sb.remove(firstStart, cutoff); } catch (e) {}
          }
        }
      } catch (e) {}
    }
    setInterval(sweepBackward, SWEEP_MS);

    function findMediaForMS(ms) {
      // Resolve the media element this MediaSource is attached to by
      // scanning all <video>/<audio> elements once and matching srcObject /
      // src.  Cached after first hit.
      if (msToMedia.has(ms)) return msToMedia.get(ms);
      var els = document.querySelectorAll('video, audio');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        try {
          if (el.srcObject === ms) { msToMedia.set(ms, el); return el; }
          if (ms.__sbUrl && el.src === ms.__sbUrl) { msToMedia.set(ms, el); return el; }
        } catch (e) {}
      }
      return null;
    }

    function bufferedAhead(sb, media) {
      if (!media) return 0;
      try {
        var ranges = sb.buffered;
        var t = media.currentTime;
        for (var i = 0; i < ranges.length; i++) {
          if (ranges.start(i) <= t && t <= ranges.end(i)) return ranges.end(i) - t;
        }
        // No range covers currentTime: use the last range end if it is ahead.
        if (ranges.length > 0) {
          var last = ranges.end(ranges.length - 1);
          return Math.max(0, last - t);
        }
      } catch (e) {}
      return 0;
    }

    var origAppend = SourceBuffer.prototype.appendBuffer;
    SourceBuffer.prototype.appendBuffer = function (data) {
      var sb = this;
      var ms = sb.__sbParent;
      var media = ms ? findMediaForMS(ms) : null;
      var ahead = bufferedAhead(sb, media);

      if (ahead < MAX_AHEAD_S || sb.updating) {
        // Plenty of room (or browser will queue it for us): pass through.
        return origAppend.call(sb, data);
      }

      // Defer.  Poll until we have headroom OR the user seeks past the
      // buffered range, then perform the original append.  We cap retries
      // so a stalled player can't leak.
      var attempts = 0;
      var maxAttempts = 480; // ~2 min
      var retry = function () {
        attempts++;
        if (attempts > maxAttempts) return; // give up silently
        if (sb.updating) { setTimeout(retry, RETRY_MS); return; }
        try {
          if (bufferedAhead(sb, media) < MAX_AHEAD_S) {
            origAppend.call(sb, data);
            return;
          }
        } catch (e) { return; }
        setTimeout(retry, RETRY_MS);
      };
      setTimeout(retry, RETRY_MS);
    };
  } catch (e) {}
})();
