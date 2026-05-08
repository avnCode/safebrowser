package com.safebrowser.app

import android.content.Context

enum class Mode { Strict, Lenient }

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("safebrowser", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", "Strict") ?: "Strict") }.getOrDefault(Mode.Strict)
        set(value) { sp.edit().putString("mode", value.name).apply() }

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
}
