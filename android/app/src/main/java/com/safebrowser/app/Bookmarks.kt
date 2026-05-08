package com.safebrowser.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Bookmark(val id: String, val url: String, val title: String, val addedAt: Long)

class BookmarksStore(ctx: Context) {
    private val file: File = File(ctx.filesDir, "bookmarks.json")

    suspend fun list(): List<Bookmark> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Bookmark(
                    id      = o.optString("id"),
                    url     = o.optString("url"),
                    title   = o.optString("title"),
                    addedAt = o.optLong("addedAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun add(url: String, title: String): List<Bookmark> = withContext(Dispatchers.IO) {
        val current = list().toMutableList()
        if (current.any { it.url == url }) return@withContext current
        val b = Bookmark(
            id      = System.currentTimeMillis().toString() + "-" + (Math.random() * 1e6).toLong(),
            url     = url,
            title   = title.ifBlank { url },
            addedAt = System.currentTimeMillis(),
        )
        current.add(0, b)
        save(current)
        current
    }

    suspend fun remove(id: String): List<Bookmark> = withContext(Dispatchers.IO) {
        val next = list().filterNot { it.id == id }
        save(next)
        next
    }

    suspend fun has(url: String): Boolean = list().any { it.url == url }

    private fun save(items: List<Bookmark>) {
        val arr = JSONArray()
        for (b in items) {
            arr.put(JSONObject().apply {
                put("id", b.id); put("url", b.url); put("title", b.title); put("addedAt", b.addedAt)
            })
        }
        file.writeText(arr.toString())
    }
}
