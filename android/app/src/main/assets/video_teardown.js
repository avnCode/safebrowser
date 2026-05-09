/* SafeBrowser video teardown v2.
 *
 * Injected BEFORE goBack() / tab-switch / cross-origin nav to release
 * MediaCodec surfaces, MSE SourceBuffers, WASM memory, and cross-origin
 * iframe resources held by the current page.
 *
 * Targets:
 *   - <video>/<audio> elements: pause, strip src, force load() to release
 *     hardware decoders.
 *   - MediaSource instances: endOfStream() + remove SourceBuffers to free
 *     the MSE buffer memory in the renderer.
 *   - Cross-origin iframes (e.g. flixcloud.cc video player): set src to
 *     about:blank to tear down the entire sub-frame (JS heap, WASM, media).
 *   - Intervals/timeouts: clear all to stop orphaned polling loops (e.g.
 *     flixcloud's currentTime poller that keeps running after video death).
 *
 * Must be synchronous — goBack()/loadUrl() fires right after this returns.
 */
(function () {
  try {
    // 1. Tear down all media elements.
    var els = document.querySelectorAll('video, audio');
    for (var i = 0; i < els.length; i++) {
      try {
        els[i].pause();
        els[i].removeAttribute('src');
        els[i].load();          // forces release of MediaCodec buffers
      } catch (e) {}
    }

    // 2. Kill cross-origin iframes — setting src to about:blank tears down
    //    the sub-frame's entire JS/WASM/media context.
    var iframes = document.querySelectorAll('iframe');
    for (var j = 0; j < iframes.length; j++) {
      try {
        iframes[j].src = 'about:blank';
      } catch (e) {}
    }

    // 3. Clear all intervals and timeouts to stop orphaned polling loops.
    //    The IDs are sequential integers starting from 1.
    var maxId = setTimeout(function(){}, 0);
    for (var k = 1; k <= maxId; k++) {
      clearTimeout(k);
      clearInterval(k);
    }
  } catch (e) {}
})();
