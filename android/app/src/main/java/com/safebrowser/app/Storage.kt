package com.safebrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tiny JSON-backed store for bookmarks and history. Both are kept in
 * `filesDir/bookmarks.json` and `filesDir/history.json` as JSON arrays of
 * `{ "url": ..., "title": ..., "ts": ... }`.
 */
class Bookmarks(private val ctx: Context) {
    private val file = File(ctx.filesDir, "bookmarks.json")

    data class Item(val url: String, val title: String, val ts: Long)

    fun list(): List<Item> = read()

    fun isBookmarked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return read().any { it.url == url }
    }

    fun add(url: String, title: String) {
        if (url.isBlank()) return
        val items = read().toMutableList()
        if (items.any { it.url == url }) return
        items.add(0, Item(url, title.ifBlank { url }, System.currentTimeMillis()))
        write(items)
    }

    fun remove(url: String) {
        val items = read().filterNot { it.url == url }
        write(items)
    }

    fun clear() { write(emptyList()) }

    private fun read(): List<Item> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.optString("url"), o.optString("title"), o.optLong("ts"))
        }
    }.getOrDefault(emptyList())

    private fun write(items: List<Item>) {
        runCatching {
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject().apply {
                    put("url", it.url); put("title", it.title); put("ts", it.ts)
                })
            }
            file.writeText(arr.toString())
        }
    }
}

class History(private val ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")
    private val maxEntries = 1000

    data class Entry(val url: String, val title: String, val ts: Long)

    fun list(): List<Entry> = read()

    fun add(url: String, title: String) {
        if (url.isBlank() || url.startsWith("file://") || url.startsWith("about:")) return
        val items = read().toMutableList()
        // De-dupe consecutive same-URL visits.
        if (items.firstOrNull()?.url == url) return
        items.add(0, Entry(url, title.ifBlank { url }, System.currentTimeMillis()))
        if (items.size > maxEntries) items.subList(maxEntries, items.size).clear()
        write(items)
    }

    fun clear() { write(emptyList()) }

    private fun read(): List<Entry> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.optString("url"), o.optString("title"), o.optLong("ts"))
        }
    }.getOrDefault(emptyList())

    private fun write(items: List<Entry>) {
        runCatching {
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject().apply {
                    put("url", it.url); put("title", it.title); put("ts", it.ts)
                })
            }
            file.writeText(arr.toString())
        }
    }
}
