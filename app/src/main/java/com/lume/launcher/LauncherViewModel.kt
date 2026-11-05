package com.lume.launcher

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.os.SystemClock
import android.provider.Settings
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

/** Reads the TV's passthrough inputs (HDMI etc.). Returns an empty list if the TV doesn't expose them. */
fun loadInputs(ctx: Context): List<InputItem> = try {
    val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
    tim.tvInputList
        .filter { it.isPassthroughInput }
        .map {
            InputItem(
                it.id,
                (it.loadCustomLabel(ctx) ?: it.loadLabel(ctx)).toString(),
                tim.getInputState(it.id) == TvInputManager.INPUT_STATE_CONNECTED,
            )
        }
        .sortedBy { it.label }
} catch (e: Exception) {
    emptyList()
}

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val store = LayoutStore(app)
    private val repo = AppRepository(app)
    private var loadJob: Job? = null
    private var startupChecked = false

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
    var inputs by mutableStateOf<List<InputItem>>(emptyList())
        private set
    var startupMode by mutableStateOf(store.startup)
        private set
    var tvInputId by mutableStateOf(store.tvInputId)
        private set

    val overlays = mutableStateListOf<Overlay>()

    init {
        app.getExternalFilesDir(null) // creates the folder used for layout import/export
        refreshInputs()
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
            refreshInputs()
            if (!startupChecked && loaded.isNotEmpty()) {
                startupChecked = true
                // Separate coroutine so a later reload() can't cancel a pending power-on resume.
                viewModelScope.launch { runStartupCheck() }
            }
        }
    }

    private fun applyDefaults(loaded: List<AppEntry>) {
        val installed = loaded.map { it.pkg }.toSet()
        val userApps = loaded.filter { it.isUserApp }.map { it.pkg }
        val current = store.favorites
        val extra = if (current == null) {
            loaded.filter { PIN_PATTERN.containsMatchIn(it.pkg + " " + it.label) }.map { it.pkg }
        } else {
            emptyList()
        }
        val base = current ?: (DEFAULT_PINS.filter { it in installed } + extra).distinct()
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

    fun refreshInputs() {
        inputs = loadInputs(getApplication())
    }

    // ---- recents --------------------------------------------------------------------------

    /** Packages ordered by most recent foreground time. Needs the optional usage-access grant. */
    @Suppress("DEPRECATION")
    private fun usageRecents(): List<String> = try {
        val usm = getApplication<Application>().getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 7L * 24 * 3600 * 1000, end)
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

    // ---- opening things -------------------------------------------------------------------

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
            store.lastTarget = "app:$pkg"
        } catch (e: Exception) {
            Toast.makeText(ctx, "Can't open this app", Toast.LENGTH_SHORT).show()
        }
    }

    fun openInput(ctx: Context, id: String): Boolean {
        val ok = try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) {
            false
        }
        if (ok) {
            store.lastInputId = id
            store.lastTarget = "input:$id"
        } else {
            toast("This TV can't switch input from here")
        }
        return ok
    }

    fun launchTarget(ctx: Context, key: String) {
        when {
            key.startsWith("app:") -> openApp(ctx, key.removePrefix("app:"))
            key.startsWith("input:") -> openInput(ctx, key.removePrefix("input:"))
        }
    }

    /** Human-readable name for a target key, or null if it is no longer available on this TV. */
    fun targetLabel(key: String): String? = when {
        key.startsWith("app:") -> apps.firstOrNull { it.pkg == key.removePrefix("app:") }?.label
        key.startsWith("input:") -> inputs.firstOrNull { it.id == key.removePrefix("input:") }?.label
        else -> null
    }

    // ---- Live TV tile ---------------------------------------------------------------------

    /**
     * Which input the Live TV tile opens: the pinned one, else the last-used connected input,
     * else any connected input, else the last-used or first input.
     */
    fun liveTvTarget(): InputItem? {
        val list = inputs
        if (list.isEmpty()) return null
        tvInputId?.let { id -> list.firstOrNull { it.id == id }?.let { return it } }
        val last = store.lastInputId
        return list.firstOrNull { it.id == last && it.connected }
            ?: list.firstOrNull { it.connected }
            ?: list.firstOrNull { it.id == last }
            ?: list.first()
    }

    fun tvInputLabel(): String =
        if (tvInputId == null) "Auto" else inputs.firstOrNull { it.id == tvInputId }?.label ?: "Not found"

    fun cycleTvInput() {
        val options = listOf<String?>(null) + inputs.map { it.id }
        val next = options[(options.indexOf(tvInputId).coerceAtLeast(0) + 1) % options.size]
        store.tvInputId = next
        tvInputId = next
    }

    // ---- startup behaviour (survives power cycles) ------------------------------------------

    fun startupLabel(): String = when (startupMode) {
        "resume" -> "Last app or input"
        "live" -> "Live TV input"
        else -> "Home screen"
    }

    fun cycleStartup() {
        val next = StartupModes[(StartupModes.indexOf(startupMode).coerceAtLeast(0) + 1) % StartupModes.size]
        store.startup = next
        startupMode = next
    }

    /** Changes on every power-on, stays the same across standby, wake and Lume restarts. */
    private fun bootId(): Long {
        val count = try {
            Settings.Global.getInt(getApplication<Application>().contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Exception) {
            -1
        }
        return if (count >= 0) count.toLong() else (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 120_000L
    }

    @Suppress("DEPRECATION")
    private fun queryPackages(intent: Intent): Set<String> = try {
        getApplication<Application>().packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** Packages that display HDMI inputs (the "Live TV" app of this particular TV). */
    private fun tvViewerPackages(): Set<String> =
        queryPackages(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput("lume")))

    private fun homePackages(): Set<String> =
        queryPackages(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

    /** What the TV was showing before it was switched off. */
    private fun resumeTarget(): String? {
        val installed = apps.map { it.pkg }.toSet()
        val viewers = tvViewerPackages()
        val skip = homePackages() + setOf(getApplication<Application>().packageName, "android", "com.android.systemui")
        val last = usageRecents().firstOrNull { it !in skip && (it in installed || it in viewers) }
        val key = when {
            last == null -> store.lastTarget
            last in viewers -> (store.lastInputId ?: liveTvTarget()?.id)?.let { "input:$it" } ?: store.lastTarget
            else -> "app:$last"
        }
        return key?.takeIf { targetLabel(it) != null }
    }

    private suspend fun runStartupCheck() {
        val id = bootId()
        if (id == store.lastBootId) return // same power-on as last time: user just pressed Home
        store.lastBootId = id
        if (store.startup == "home") return

        // Inputs can take a few seconds to appear right after power-on.
        var tries = 0
        while (inputs.isEmpty() && tries < 4) {
            delay(1500)
            refreshInputs()
            tries++
        }
        val key = when (store.startup) {
            "resume" -> resumeTarget()
            "live" -> liveTvTarget()?.let { "input:${it.id}" }
            else -> null
        } ?: return
        if (targetLabel(key) != null) show(Overlay.Resume(key))
    }

    // ---- pinning, hiding ------------------------------------------------------------------

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
        startupMode = store.startup
        refreshRecents()
        return "Layout imported"
    }

    fun resetLayout() {
        store.reset()
        favoritePkgs = emptyList()
        hiddenPkgs = emptySet()
        accentIndex = 0
        startupMode = "home"
        tvInputId = null
        reload()
    }

    fun toast(msg: String) {
        Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
    }
}
