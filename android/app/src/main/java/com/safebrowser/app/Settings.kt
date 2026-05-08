package com.safebrowser.app

import android.content.Context

enum class Mode { Strict, Lenient }

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("safebrowser", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", "Strict") ?: "Strict") }.getOrDefault(Mode.Strict)
        set(value) { sp.edit().putString("mode", value.name).apply() }
}
