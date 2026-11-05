package com.lume.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists the user's layout and startup behaviour. Stored in app storage, so it survives power cycles. */
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
        // commit() rather than apply(): these writes must hit disk even if the TV is unplugged right after.
        sp.edit().putString(key, JSONArray(v).toString()).commit()
    }

    private fun putString(key: String, v: String?) {
        if (v == null) sp.edit().remove(key).commit() else sp.edit().putString(key, v).commit()
    }

    /** null = never initialised (first run). */
    var favorites: List<String>?
        get() = readList("favorites")
        set(v) {
            if (v == null) sp.edit().remove("favorites").commit() else writeList("favorites", v)
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
        set(v) = sp.edit().putInt("accent", v).commit().let { }

    /** "home" | "resume" | "live" */
    var startup: String
        get() = sp.getString("startup", "home") ?: "home"
        set(v) = putString("startup", v)

    /** "app:<package>" or "input:<inputId>" - the last thing opened through Lume. */
    var lastTarget: String?
        get() = sp.getString("lastTarget", null)
        set(v) = putString("lastTarget", v)

    var lastInputId: String?
        get() = sp.getString("lastInputId", null)
        set(v) = putString("lastInputId", v)

    /** Input pinned to the Live TV tile; null = automatic. */
    var tvInputId: String?
        get() = sp.getString("tvInputId", null)
        set(v) = putString("tvInputId", v)

    /** Identifier of the boot Lume last handled, so each power-on triggers startup behaviour only once. */
    var lastBootId: Long
        get() = sp.getLong("lastBootId", -1L)
        set(v) = sp.edit().putLong("lastBootId", v).commit().let { }

    fun recordLaunch(pkg: String) {
        recents = (listOf(pkg) + recents.filter { it != pkg }).take(16)
    }

    /** Input ids differ between TVs, so only portable settings are exported. */
    fun exportJson(): String = JSONObject()
        .put("version", 2)
        .put("favorites", JSONArray(favorites ?: emptyList<String>()))
        .put("hidden", JSONArray(hidden.toList()))
        .put("accent", accent)
        .put("startup", startup)
        .toString(2)

    fun importJson(text: String): Boolean = try {
        val o = JSONObject(text)
        val fav = o.getJSONArray("favorites")
        favorites = List(fav.length()) { fav.getString(it) }
        val hid = o.optJSONArray("hidden")
        hidden = if (hid == null) emptySet() else List(hid.length()) { hid.getString(it) }.toSet()
        accent = o.optInt("accent", 0)
        val st = o.optString("startup", "home")
        startup = if (st in StartupModes) st else "home"
        true
    } catch (e: Exception) {
        false
    }

    fun reset() {
        val boot = lastBootId
        sp.edit().clear().commit()
        lastBootId = boot
    }
}
