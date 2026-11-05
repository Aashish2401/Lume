package com.lume.launcher

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val store = LayoutStore(app)
    private val repo = AppRepository(app)
    private var loadJob: Job? = null

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var favoritePkgs by mutableStateOf(store.favorites ?: emptyList())
        private set
    var hiddenPkgs by mutableStateOf(store.hidden)
        private set
    var accentIndex by mutableIntStateOf(store.accent.coerceIn(0, Accents.lastIndex))
        private set
    var recentPkgs by mutableStateOf<List<String>>(emptyList())
        private set
    var homeTick by mutableIntStateOf(0)
        private set

    val overlays = mutableStateListOf<Overlay>()

    init {
        app.getExternalFilesDir(null) // creates the folder used for layout import/export
        reload()
    }

    // ---- overlays -------------------------------------------------------------------------

    fun show(o: Overlay) {
        if (overlays.lastOrNull() != o) overlays.add(o)
    }

    fun dismiss() {
        if (overlays.isNotEmpty()) overlays.removeAt(overlays.lastIndex)
    }

    fun goHome() {
        overlays.clear()
        homeTick++
    }

    // ---- loading --------------------------------------------------------------------------

    /** Debounced: a single app update fires several package broadcasts. */
    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            delay(300)
            val loaded = repo.load()
            if (loaded.isNotEmpty()) applyDefaults(loaded)
            apps = loaded
            refreshRecents()
        }
    }

    private fun applyDefaults(loaded: List<AppEntry>) {
        val installed = loaded.map { it.pkg }.toSet()
        val userApps = loaded.filter { it.isUserApp }.map { it.pkg }
        val current = store.favorites
        val base = current ?: DEFAULT_PINS.filter { it in installed }
        val hidden = store.hidden
        val known = store.known
        val fresh = userApps.filter { it !in known && it !in base && it !in hidden }
        val merged = base + fresh
        if (current == null || fresh.isNotEmpty()) store.favorites = merged
        store.known = known + userApps
        favoritePkgs = merged
        hiddenPkgs = hidden
    }

    fun visibleApps(): List<AppEntry> = apps.filter { it.pkg !in hiddenPkgs }

    // ---- recents --------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun usageRecents(): List<String> = try {
        val usm = getApplication<Application>().getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 3L * 24 * 3600 * 1000, end)
        val last = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last[e.packageName] = e.timeStamp
        }
        last.entries.sortedByDescending { it.value }.map { it.key }
    } catch (e: Exception) {
        emptyList()
    }

    fun refreshRecents() {
        val installed = apps.map { it.pkg }.toSet()
        recentPkgs = (usageRecents() + store.recents)
            .distinct()
            .filter { it in installed && it !in hiddenPkgs }
            .take(12)
    }

    // ---- actions --------------------------------------------------------------------------

    fun openApp(ctx: Context, pkg: String) {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(ctx, "Can't open this app", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            store.recordLaunch(pkg)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Can't open this app", Toast.LENGTH_SHORT).show()
        }
    }

    fun isPinned(pkg: String) = pkg in favoritePkgs

    fun togglePin(pkg: String) {
        setFavorites(if (isPinned(pkg)) favoritePkgs - pkg else favoritePkgs + pkg)
    }

    fun moveFavorite(pkg: String, delta: Int) {
        val installed = apps.map { it.pkg }.toSet()
        val list = favoritePkgs.toMutableList()
        val i = list.indexOf(pkg)
        if (i < 0) return
        var j = i + delta
        while (j in list.indices && list[j] !in installed) j += delta
        if (j !in list.indices) return
        list.removeAt(i)
        list.add(j, pkg)
        setFavorites(list)
    }

    fun hide(pkg: String) {
        store.hidden = store.hidden + pkg
        hiddenPkgs = store.hidden
        setFavorites(favoritePkgs - pkg)
        refreshRecents()
    }

    fun unhideAll() {
        store.hidden = emptySet()
        hiddenPkgs = emptySet()
        refreshRecents()
    }

    private fun setFavorites(list: List<String>) {
        store.favorites = list
        favoritePkgs = list
    }

    fun setAccent(i: Int) {
        store.accent = i
        accentIndex = i
    }

    // ---- layout sync between TVs ------------------------------------------------------------

    private fun layoutFile(): File? =
        getApplication<Application>().getExternalFilesDir(null)?.let { File(it, "lume-layout.json") }

    fun exportLayout(): String {
        val f = layoutFile() ?: return "Storage not available"
        return try {
            f.writeText(store.exportJson())
            "Saved: Android/data/com.lume.launcher/files/lume-layout.json"
        } catch (e: Exception) {
            "Export failed"
        }
    }

    fun importLayout(): String {
        val f = layoutFile()
        if (f == null || !f.exists()) return "No lume-layout.json found (adb push it first)"
        val ok = store.importJson(f.readText())
        if (!ok) return "Layout file is invalid"
        favoritePkgs = store.favorites ?: emptyList()
        hiddenPkgs = store.hidden
        accentIndex = store.accent.coerceIn(0, Accents.lastIndex)
        refreshRecents()
        return "Layout imported"
    }

    fun resetLayout() {
        store.reset()
        favoritePkgs = emptyList()
        hiddenPkgs = emptySet()
        accentIndex = 0
        reload()
    }

    fun toast(msg: String) {
        Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
    }
}
