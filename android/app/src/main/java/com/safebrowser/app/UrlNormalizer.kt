package com.safebrowser.app

import android.net.Uri

object UrlNormalizer {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun normalize(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return s
        if (s.startsWith("about:") || s.startsWith("file://") || s.startsWith("javascript:")) return s
        if (SCHEME.containsMatchIn(s)) return s

        // Looks like a domain? (contains a dot, no spaces)
        if (!s.contains(' ') && s.contains('.') && !s.contains('?')) {
            return "https://$s"
        }
        // Otherwise treat as a Google search.
        val q = Uri.encode(s)
        return "https://www.google.com/search?q=$q"
    }

    fun host(url: String): String? = runCatching { Uri.parse(url).host }.getOrNull()

    fun origin(url: String): String? = runCatching {
        val u = Uri.parse(url)
        if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) null
        else "${u.scheme}://${u.host}" + if (u.port > 0) ":${u.port}" else ""
    }.getOrNull()

    fun sameRegistrableDomain(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val pa = a.lowercase().split('.')
        val pb = b.lowercase().split('.')
        if (pa.size < 2 || pb.size < 2) return a.equals(b, true)
        return pa.takeLast(2).joinToString(".") == pb.takeLast(2).joinToString(".")
    }
}
