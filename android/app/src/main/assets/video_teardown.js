/* SafeBrowser video teardown.
 *
 * Injected BEFORE goBack() to release MediaCodec surfaces and GPU memory
 * held by <video>/<audio> elements on the current page.  Without this,
 * bfcache keeps the decoded frames alive and the renderer briefly holds
 * two full pages' media allocations in one process.
 *
 * Must be synchronous — goBack() fires right after this returns.
 */
(function () {
  try {
    var els = document.querySelectorAll('video, audio');
    for (var i = 0; i < els.length; i++) {
      try {
        els[i].pause();
        els[i].removeAttribute('src');
        els[i].load();          // forces release of MediaCodec buffers
      } catch (e) {}
    }
  } catch (e) {}
})();
