package com.lume.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists the user's layout. Everything here is exportable so all your TVs can look identical. */
class LayoutStore(context: Context) {
    private val sp = context.getSharedPreferences("lume_layout", Context.MODE_PRIVATE)

    private fun readList(key: String): List<String>? {
        val raw = sp.getString(key, null) ?: return null
        return try {
            val a = JSONArray(raw)
            List(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeList(key: String, v: Collection<String>) {
        sp.edit().putString(key, JSONArray(v).toString()).apply()
    }

    /** null = never initialised (first run). */
    var favorites: List<String>?
        get() = readList("favorites")
        set(v) {
            if (v == null) sp.edit().remove("favorites").apply() else writeList("favorites", v)
        }

    var hidden: Set<String>
        get() = readList("hidden")?.toSet() ?: emptySet()
        set(v) = writeList("hidden", v)

    /** User-installed packages we have already seen, so newly installed apps get auto-pinned once. */
    var known: Set<String>
        get() = readList("known")?.toSet() ?: emptySet()
        set(v) = writeList("known", v)

    var recents: List<String>
        get() = readList("recents") ?: emptyList()
        set(v) = writeList("recents", v)

    var accent: Int
        get() = sp.getInt("accent", 0)
        set(v) = sp.edit().putInt("accent", v).apply()

    fun recordLaunch(pkg: String) {
        recents = (listOf(pkg) + recents.filter { it != pkg }).take(16)
    }

    fun exportJson(): String = JSONObject()
        .put("version", 1)
        .put("favorites", JSONArray(favorites ?: emptyList<String>()))
        .put("hidden", JSONArray(hidden.toList()))
        .put("accent", accent)
        .toString(2)

    fun importJson(text: String): Boolean = try {
        val o = JSONObject(text)
        val fav = o.getJSONArray("favorites")
        favorites = List(fav.length()) { fav.getString(it) }
        val hid = o.optJSONArray("hidden")
        hidden = if (hid == null) emptySet() else List(hid.length()) { hid.getString(it) }.toSet()
        accent = o.optInt("accent", 0)
        true
    } catch (e: Exception) {
        false
    }

    fun reset() {
        sp.edit().clear().apply()
    }
}
