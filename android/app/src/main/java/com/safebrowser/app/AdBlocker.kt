package com.safebrowser.app

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight host-based ad/tracker blocker.
 *
 * - Loads a bundled blocklist from assets/blocklist.txt (one host per line).
 * - On first run also tries to fetch a richer hosts file in the background.
 * - Per-host allow-list (e.g. "youtube.com") disables blocking for matching pages.
 *
 * NOTE: A host-based blocker is much weaker than EasyList rules. It's a
 * reasonable trade-off for a simple, fast in-WebView blocker without native deps.
 */
class AdBlocker(private val ctx: Context) {

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var allowList: MutableSet<String> = mutableSetOf(
        "youtube.com", "youtu.be", "google.com", "gmail.com",
        "drive.google.com", "docs.google.com", "maps.google.com",
    )

    private val cacheFile = File(ctx.filesDir, "blocklist-cache.txt")
    private val allowFile = File(ctx.filesDir, "ad-allowlist.json")

    private val EMPTY_RESPONSE: WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
    )

    suspend fun load() = withContext(Dispatchers.IO) {
        loadAllowList()
        // Prefer cached/fetched copy; fall back to bundled.
        val text = if (cacheFile.exists()) cacheFile.readText()
                   else ctx.assets.open("blocklist.txt").bufferedReader().use { it.readText() }
        blocked = parse(text)
        // Best-effort background refresh (don't block startup)
        runCatching { refreshFromNetwork() }
    }

    private suspend fun refreshFromNetwork() = withContext(Dispatchers.IO) {
        // StevenBlack hosts (unified) — small, well-maintained.
        val urls = listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        )
        for (u in urls) {
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 12000
                }
                conn.inputStream.bufferedReader().use { r ->
                    val txt = r.readText()
                    if (txt.length > 5000) {
                        cacheFile.writeText(txt)
                        blocked = parse(txt)
                        return@withContext
                    }
                }
            } catch (_: Exception) { /* offline or blocked, fine */ }
        }
    }

    private fun parse(text: String): Set<String> {
        val out = HashSet<String>(64_000)
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            // formats supported: "host", "0.0.0.0 host", "127.0.0.1 host"
            val parts = line.split(Regex("\\s+"))
            val host = if (parts.size >= 2) parts[1] else parts[0]
            if (host.isBlank() || host == "localhost" || host == "0.0.0.0") return@forEach
            out.add(host.lowercase())
        }
        return out
    }

    fun shouldBlock(pageUrl: String?, request: WebResourceRequest?, adblockEnabled: Boolean): Boolean {
        if (!adblockEnabled) return false
        if (request == null) return false
        if (request.method != "GET" && request.method != null) {
            // POSTs are usually first-party form submits — don't block them.
            if (request.method.equals("POST", true)) return false
        }
        val pageHost = hostOf(pageUrl) ?: return false
        if (isAllowed(pageHost)) return false

        val reqHost = hostOf(request.url.toString()) ?: return false
        // Don't block first-party (same registrable domain).
        if (sameRegistrableDomain(pageHost, reqHost)) return false
        return matches(reqHost)
    }

    fun emptyResponse(): WebResourceResponse = EMPTY_RESPONSE

    private fun matches(host: String): Boolean {
        // exact, or any parent label match: "ads.example.com" matches "example.com"
        if (blocked.contains(host)) return true
        var i = host.indexOf('.')
        var h = host
        while (i != -1) {
            h = h.substring(i + 1)
            if (blocked.contains(h)) return true
            i = h.indexOf('.')
        }
        return false
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
    }

    private fun sameRegistrableDomain(a: String, b: String): Boolean {
        val pa = a.split('.')
        val pb = b.split('.')
        if (pa.size < 2 || pb.size < 2) return a == b
        return pa.takeLast(2).joinToString(".") == pb.takeLast(2).joinToString(".")
    }

    // ----- Per-host allow-list -----
    fun isAllowed(host: String): Boolean {
        val h = host.lowercase()
        return allowList.any { h == it || h.endsWith(".$it") }
    }

    suspend fun allow(host: String) = withContext(Dispatchers.IO) {
        allowList.add(host.lowercase())
        saveAllowList()
    }

    suspend fun disallow(host: String) = withContext(Dispatchers.IO) {
        allowList.remove(host.lowercase())
        saveAllowList()
    }

    fun allowList(): List<String> = allowList.toList().sorted()

    private fun loadAllowList() {
        if (!allowFile.exists()) return
        runCatching {
            val arr = JSONArray(allowFile.readText())
            val s = mutableSetOf<String>()
            for (i in 0 until arr.length()) s.add(arr.getString(i).lowercase())
            allowList = s
        }
    }

    private fun saveAllowList() {
        val arr = JSONArray()
        allowList.forEach { arr.put(it) }
        allowFile.writeText(arr.toString())
    }
}
