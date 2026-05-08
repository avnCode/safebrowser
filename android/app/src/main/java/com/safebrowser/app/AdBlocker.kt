package com.safebrowser.app

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Lightweight host-based ad blocker.
 *
 *  - Loads `assets/blocklist.txt` (one host per line; "0.0.0.0 host" also OK).
 *  - Adds the user's persisted block-list (Settings.blockedOrigins, host part).
 *  - Matches the requested URL's host AND any parent domain, so a rule for
 *    `doubleclick.net` blocks `ads.g.doubleclick.net`.
 */
class AdBlocker(ctx: Context, private val settings: Settings) {

    private val staticHosts: Set<String> = runCatching {
        ctx.assets.open("blocklist.txt").bufferedReader().useLines { seq ->
            seq.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map {
                    // Accept "0.0.0.0 host", "127.0.0.1 host", or bare host.
                    val parts = it.split(Regex("\\s+"))
                    (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
                }
                .filter { it.isNotBlank() && it != "localhost" && !it.startsWith("#") }
                .toSet()
        }
    }.getOrDefault(emptySet())

    @Volatile var enabled: Boolean = true
    @Volatile var blockedThisSession: Long = 0L
        private set

    fun shouldBlock(url: String?): Boolean {
        if (!enabled || url.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        if (matches(host, staticHosts)) return true
        // Also honor the user's per-origin block-list (host portion).
        val userHosts = settings.blockedOrigins().mapNotNull {
            runCatching { Uri.parse(it).host?.lowercase() }.getOrNull()
        }.toSet()
        return matches(host, userHosts)
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun noteBlocked() { blockedThisSession++ }

    private fun matches(host: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        if (set.contains(host)) return true
        var i = host.indexOf('.')
        while (i in 0 until host.length - 1) {
            val parent = host.substring(i + 1)
            if (set.contains(parent)) return true
            i = host.indexOf('.', i + 1)
        }
        return false
    }
}
