package com.lume.launcher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Root: backdrop + home + any stacked overlays (each overlay is its own window, so D-pad focus is always correct). */
@Composable
fun LumeApp(vm: LauncherViewModel) {
    val accent = Accents[vm.accentIndex].color
    Backdrop(accent) { HomeScreen(vm) }
    vm.overlays.forEach { o ->
        androidx.compose.runtime.key(o) { OverlayHost(vm, o, accent) }
    }
}

@Composable
fun Backdrop(accent: Color, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050506))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.36f), Color.Transparent),
                        center = Offset(size.width * 0.26f, size.height * 0.42f),
                        radius = size.width * 0.58f,
                    ),
                )
            },
        content = content,
    )
}

@Composable
fun HomeScreen(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    val byPkg = remember(vm.apps) { vm.apps.associateBy { it.pkg } }
    val favorites = remember(vm.apps, vm.favoritePkgs, vm.hiddenPkgs) {
        vm.favoritePkgs.mapNotNull { byPkg[it] }.filter { it.pkg !in vm.hiddenPkgs }
    }
    val recents = remember(vm.apps, vm.favoritePkgs, vm.recentPkgs, vm.hiddenPkgs) {
        vm.recentPkgs.mapNotNull { byPkg[it] }.filter { it.pkg !in vm.favoritePkgs }.take(8)
    }
    var focusedLabel by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    val rowState = rememberLazyListState()

    // Pressing Home (or finishing an overlay action) returns focus to the first tile.
    LaunchedEffect(vm.homeTick, favorites.isNotEmpty()) {
        rowState.scrollToItem(0)
        delay(90)
        runCatching { firstFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(top = 34.dp)) {
        TopBar(vm, Modifier.padding(horizontal = 48.dp))
        Spacer(Modifier.height(46.dp))
        Txt(
            focusedLabel, 30.sp,
            Modifier.padding(start = 52.dp).height(42.dp),
            weight = FontWeight.Light,
        )
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(favorites, key = { _, a -> a.pkg }) { index, app ->
                AppCard(
                    app, 200.dp,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    onFocused = { focusedLabel = app.label },
                    onLongClick = { vm.show(Overlay.AppMenu(app.pkg)) },
                    onClick = { vm.openApp(ctx, app.pkg) },
                )
            }
            item(key = "all-apps") {
                GlyphTile(
                    Glyph.Apps, "All apps", 200.dp,
                    modifier = if (favorites.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                    onFocused = { focusedLabel = "All apps" },
                    onClick = { vm.show(Overlay.Drawer) },
                )
            }
        }
        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Txt(
                "RECENT", 12.sp, Modifier.padding(start = 52.dp),
                color = Color.White.copy(alpha = 0.5f), weight = FontWeight.Medium, spacing = 2.sp,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(recents, key = { _, a -> a.pkg }) { _, app ->
                    AppCard(
                        app, 150.dp,
                        onFocused = { focusedLabel = app.label },
                        onLongClick = { vm.show(Overlay.AppMenu(app.pkg)) },
                        onClick = { vm.openApp(ctx, app.pkg) },
                    )
                }
            }
        }
    }
}

@Composable
fun TopBar(vm: LauncherViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val now by produceState(initialValue = Date()) {
        while (true) {
            value = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }
    val is24 = remember { DateFormat.is24HourFormat(ctx) }
    val timeFmt = remember { SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()) }
    val ampmFmt = remember { SimpleDateFormat("a", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()) }
    val hour = Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour < 5 -> "GOOD NIGHT"
        hour < 12 -> "GOOD MORNING"
        hour < 17 -> "GOOD AFTERNOON"
        else -> "GOOD EVENING"
    }
    val net by rememberNetworkLabel()

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Txt(greeting, 12.sp, color = Color.White.copy(alpha = 0.6f), weight = FontWeight.Medium, spacing = 2.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Txt(timeFmt.format(now), 54.sp, weight = FontWeight.Light)
                if (!is24) {
                    Txt(
                        ampmFmt.format(now).uppercase(), 15.sp,
                        Modifier.padding(start = 6.dp, bottom = 11.dp),
                        color = Color.White.copy(alpha = 0.8f), weight = FontWeight.Medium,
                    )
                }
                Box(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).width(1.dp).height(24.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
                )
                Txt(
                    dateFmt.format(now).uppercase(), 14.sp,
                    Modifier.padding(bottom = 14.dp),
                    color = Color.White.copy(alpha = 0.65f), spacing = 1.5.sp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(Glyph.Search, "Search", { vm.show(Overlay.Search) })
            Pill(Glyph.Input, "Inputs", { vm.show(Overlay.Inputs) })
            Pill(Glyph.Net, net, { openSettings(ctx, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS) })
            Pill(Glyph.Gear, "Settings", { vm.show(Overlay.Quick) })
        }
    }
}

@Composable
fun rememberNetworkLabel(): State<String> {
    val ctx = LocalContext.current
    val label = remember { mutableStateOf("Network") }
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        fun compute(n: Network?) {
            val caps = n?.let { cm?.getNetworkCapabilities(it) }
            label.value = when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                else -> "Online"
            }
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = compute(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = compute(network)
            override fun onLost(network: Network) = compute(null)
        }
        var registered = false
        try {
            compute(cm?.activeNetwork)
            cm?.registerDefaultNetworkCallback(cb)
            registered = true
        } catch (e: Exception) {
            // Leave the generic label if the system refuses.
        }
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return label
}

/** Tries each settings action in turn; works across different TV vendors' Settings apps. */
fun openSettings(ctx: Context, vararg actions: String, uri: Uri? = null): Boolean {
    for (a in actions) {
        try {
            ctx.startActivity(
                Intent(a).apply {
                    if (uri != null) data = uri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            return true
        } catch (e: Exception) {
            // try the next one
        }
    }
    Toast.makeText(ctx, "Not available on this TV", Toast.LENGTH_SHORT).show()
    return false
}
