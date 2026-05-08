package com.safebrowser.app

import android.content.Context

enum class Mode { Strict, Lenient }

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("safebrowser", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", "Strict") ?: "Strict") }.getOrDefault(Mode.Strict)
        set(value) { sp.edit().putString("mode", value.name).apply() }

    var adBlockEnabled: Boolean
        get() = sp.getBoolean("ad_block", true)
        set(value) { sp.edit().putBoolean("ad_block", value).apply() }

    fun blockedOrigins(): Set<String> =
        sp.getStringSet("blocked_origins", emptySet()) ?: emptySet()

    fun isBlocked(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return false
        return blockedOrigins().contains(origin)
    }

    fun blockOrigin(origin: String?) {
        if (origin.isNullOrBlank()) return
        val set = blockedOrigins().toMutableSet()
        if (set.add(origin)) {
            sp.edit().putStringSet("blocked_origins", set).apply()
        }
    }

    fun unblockOrigin(origin: String) {
        val set = blockedOrigins().toMutableSet()
        if (set.remove(origin)) {
            sp.edit().putStringSet("blocked_origins", set).apply()
        }
    }

    fun allowedOrigins(): Set<String> =
        sp.getStringSet("allowed_origins", emptySet()) ?: emptySet()

    fun isAllowed(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return false
        return allowedOrigins().contains(origin)
    }

    fun allowOrigin(origin: String?) {
        if (origin.isNullOrBlank()) return
        val set = allowedOrigins().toMutableSet()
        if (set.add(origin)) {
            sp.edit().putStringSet("allowed_origins", set).apply()
        }
    }
}
