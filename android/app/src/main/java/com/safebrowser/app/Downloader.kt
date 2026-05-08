package com.safebrowser.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast

/**
 * Hands a URL to the system DownloadManager.  Resumable, shows up in the
 * notification shade, lands in /sdcard/Download via MediaStore.
 *
 * Works for any direct file URL (mp4, mp3, pdf, jpg, ...) and for any URL
 * served with a Content-Disposition header.  Does *not* work for streaming
 * video sites that use HLS/DASH segments (YouTube, Instagram, etc.) — those
 * require a separate extractor and have no single file to download.
 */
object Downloader {

    fun enqueue(
        ctx: Context,
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        referer: String? = null,
    ): Boolean {
        if (url.isBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(ctx, "Cannot download $url", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("SafeBrowser download")
                .setMimeType(mimeType ?: URLUtil.guessUrl(url))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            userAgent?.let { req.addRequestHeader("User-Agent", it) }
            referer?.let { req.addRequestHeader("Referer", it) }
            // Forward cookies so authenticated downloads work.
            runCatching {
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { req.addRequestHeader("Cookie", it) }
            }
            dm.enqueue(req)
            Toast.makeText(ctx, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }
}
