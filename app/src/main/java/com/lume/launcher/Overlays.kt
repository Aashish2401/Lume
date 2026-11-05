package com.lume.launcher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

@Composable
fun OverlayHost(vm: LauncherViewModel, overlay: Overlay, accent: Color) {
    Dialog(
        onDismissRequest = { vm.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        OverlaySurface(accent) {
            when (overlay) {
                Overlay.None -> Unit
                Overlay.Drawer -> DrawerContent(vm)
                Overlay.Search -> SearchContent(vm)
                Overlay.Quick -> QuickContent(vm)
                Overlay.Inputs -> InputsContent(vm)
                Overlay.Personalize -> PersonalizeContent(vm)
                is Overlay.AppMenu -> AppMenuContent(vm, overlay.pkg)
                is Overlay.Resume -> ResumeContent(vm, overlay.key)
            }
        }
    }
}

@Composable
fun OverlaySurface(accent: Color, content: @Composable BoxScope.() -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(140), label = "overlayIn")
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = a
                val s = 0.97f + 0.03f * a
                scaleX = s
                scaleY = s
            }
            .background(Color(0xF7060608))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.15f),
                        radius = size.width * 0.5f,
                    ),
                )
            },
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// Shared grid
// ---------------------------------------------------------------------------------------------

@Composable
fun AppGrid(apps: List<AppEntry>, vm: LauncherViewModel, autoFocusFirst: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 20.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        itemsIndexed(apps, key = { _, a -> a.pkg }) { index, app ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppCard(
                    app, Dp.Unspecified,
                    modifier = if (autoFocusFirst && index == 0) Modifier.autoFocus() else Modifier,
                    onLongClick = { vm.show(Overlay.AppMenu(app.pkg)) },
                    onClick = {
                        vm.openApp(ctx, app.pkg)
                        vm.goHome()
                    },
                )
                Txt(app.label, 12.sp, Modifier.padding(top = 8.dp), color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// All apps
// ---------------------------------------------------------------------------------------------

@Composable
fun DrawerContent(vm: LauncherViewModel) {
    val list = remember(vm.apps, vm.hiddenPkgs) {
        vm.visibleApps().sortedWith(compareBy<AppEntry>({ !it.isUserApp }, { it.label.lowercase() }))
    }
    Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
        Txt("All apps", 28.sp, Modifier.padding(start = 56.dp), weight = FontWeight.Light)
        Txt(
            "${list.size} apps  ·  hold OK for options", 13.sp,
            Modifier.padding(start = 56.dp, top = 4.dp), color = Color.White.copy(alpha = 0.5f),
        )
        AppGrid(list, vm, autoFocusFirst = true)
    }
}

// ---------------------------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------------------------

@Composable
fun SearchContent(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val results = remember(query, vm.apps, vm.hiddenPkgs) {
        val q = query.trim()
        val base = vm.visibleApps()
        if (q.isEmpty()) {
            base.sortedWith(compareBy<AppEntry>({ !it.isUserApp }, { it.label.lowercase() }))
        } else {
            base.filter { it.label.contains(q, ignoreCase = true) }
                .sortedWith(compareBy<AppEntry>({ !it.label.startsWith(q, ignoreCase = true) }, { it.label.lowercase() }))
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { fr.requestFocus() }
        keyboard?.show()
    }
    Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
        Box(
            Modifier
                .padding(horizontal = 56.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 26.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    results.firstOrNull()?.let {
                        vm.openApp(ctx, it.pkg)
                        vm.goHome()
                    }
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Txt("Search apps", 26.sp, color = Color.White.copy(alpha = 0.4f))
                        inner()
                    }
                },
            )
        }
        AppGrid(results, vm, autoFocusFirst = false)
    }
}

// ---------------------------------------------------------------------------------------------
// Quick settings
// ---------------------------------------------------------------------------------------------

private class QuickItem(val glyph: Glyph, val label: String, val action: () -> Unit)

@Composable
fun QuickContent(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    val items = listOf(
        QuickItem(Glyph.Input, "Inputs") { vm.show(Overlay.Inputs) },
        QuickItem(Glyph.Net, "Network") {
            openSettings(ctx, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
            vm.goHome()
        },
        QuickItem(Glyph.Bluetooth, "Bluetooth") {
            openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS)
            vm.goHome()
        },
        QuickItem(Glyph.Sound, "Sound") {
            openSettings(ctx, Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_SETTINGS)
            vm.goHome()
        },
        QuickItem(Glyph.Display, "Display") {
            openSettings(ctx, Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)
            vm.goHome()
        },
        QuickItem(Glyph.Apps, "Manage apps") {
            openSettings(ctx, Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, Settings.ACTION_SETTINGS)
            vm.goHome()
        },
        QuickItem(Glyph.Palette, "Personalize") { vm.show(Overlay.Personalize) },
        QuickItem(Glyph.Gear, "All settings") {
            openSettings(ctx, Settings.ACTION_SETTINGS)
            vm.goHome()
        },
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Txt("Quick settings", 28.sp, weight = FontWeight.Light)
        Spacer(Modifier.height(24.dp))
        items.chunked(4).forEachIndexed { r, rowItems ->
            Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rowItems.forEachIndexed { c, item ->
                    GlyphTile(
                        item.glyph, item.label, 200.dp,
                        modifier = if (r == 0 && c == 0) Modifier.autoFocus() else Modifier,
                        onClick = item.action,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Inputs (HDMI etc.)
// ---------------------------------------------------------------------------------------------

@Composable
fun InputsContent(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshInputs() }
    val inputs = vm.inputs
    Column(
        Modifier.fillMaxHeight().width(632.dp).padding(horizontal = 56.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Txt("Inputs", 28.sp, weight = FontWeight.Light)
        Spacer(Modifier.height(20.dp))
        if (inputs.isEmpty()) {
            Txt("No inputs reported by this TV.", 15.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 2)
            Spacer(Modifier.height(16.dp))
            WideButton(
                "Open system settings",
                {
                    openSettings(ctx, Settings.ACTION_SETTINGS)
                    vm.goHome()
                },
                Modifier.autoFocus(),
            )
        } else {
            inputs.forEachIndexed { i, input ->
                WideButton(
                    input.label,
                    {
                        vm.openInput(ctx, input.id)
                        vm.goHome()
                    },
                    Modifier.padding(bottom = 10.dp).then(if (i == 0) Modifier.autoFocus() else Modifier),
                    hint = if (input.connected) "Connected" else null,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Personalize: startup, Live TV button, accent colour and layout sync
// ---------------------------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Txt(text, 12.sp, color = Color.White.copy(alpha = 0.5f), weight = FontWeight.Medium, spacing = 2.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
fun PersonalizeContent(vm: LauncherViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Txt("Personalize", 28.sp, weight = FontWeight.Light)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.width(400.dp)) {
                SectionLabel("STARTUP")
                WideButton(
                    "When TV powers on", { vm.cycleStartup() },
                    Modifier.autoFocus().padding(bottom = 10.dp), hint = vm.startupLabel(),
                )
                WideButton(
                    "Live TV button", { vm.cycleTvInput() },
                    Modifier.padding(bottom = 8.dp), hint = vm.tvInputLabel(),
                )
                Txt(
                    "After a power-on, Lume counts down 3 seconds, then opens your choice. Press Back to cancel.",
                    12.sp, color = Color.White.copy(alpha = 0.45f), maxLines = 3,
                )
                Spacer(Modifier.height(22.dp))
                SectionLabel("ACCENT")
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Accents.forEachIndexed { i, a ->
                        Swatch(a.color, selected = i == vm.accentIndex, onClick = { vm.setAccent(i) })
                    }
                }
            }
            Column(Modifier.width(400.dp)) {
                SectionLabel("LAYOUT")
                WideButton("Export layout", { vm.toast(vm.exportLayout()) }, Modifier.padding(bottom = 10.dp))
                WideButton("Import layout", { vm.toast(vm.importLayout()) }, Modifier.padding(bottom = 10.dp))
                WideButton(
                    "Unhide all apps", { vm.unhideAll(); vm.toast("All apps visible") },
                    Modifier.padding(bottom = 10.dp), hint = "${vm.hiddenPkgs.size} hidden",
                )
                WideButton("Reset to defaults", { vm.resetLayout(); vm.goHome() })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Power-on countdown
// ---------------------------------------------------------------------------------------------

@Composable
fun ResumeContent(vm: LauncherViewModel, key: String) {
    val ctx = LocalContext.current
    val label = vm.targetLabel(key) ?: ""
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.animateTo(1f, tween(3000, easing = LinearEasing))
        vm.launchTarget(ctx, key)
        vm.goHome()
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Txt("STARTING", 13.sp, color = Color.White.copy(alpha = 0.5f), weight = FontWeight.Medium, spacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        Txt(label, 44.sp, weight = FontWeight.Light)
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.width(360.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.15f)),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(Color.White))
        }
        Spacer(Modifier.height(36.dp))
        WideButton("Stay on home screen", { vm.dismiss() }, Modifier.width(360.dp).autoFocus())
        Spacer(Modifier.height(10.dp))
        Txt("or press Back", 12.sp, color = Color.White.copy(alpha = 0.4f))
    }
}

// ---------------------------------------------------------------------------------------------
// Per-app menu (hold OK on any tile)
// ---------------------------------------------------------------------------------------------

@Composable
fun AppMenuContent(vm: LauncherViewModel, pkg: String) {
    val ctx = LocalContext.current
    val app = vm.apps.firstOrNull { it.pkg == pkg }
    if (app == null) {
        LaunchedEffect(Unit) { vm.dismiss() }
        return
    }
    val pinned = vm.isPinned(pkg)
    Column(
        Modifier.fillMaxHeight().width(592.dp).padding(horizontal = 56.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(16.dp))
            Txt(app.label, 26.sp, weight = FontWeight.Light)
        }
        Spacer(Modifier.height(22.dp))
        WideButton(
            "Open",
            {
                vm.openApp(ctx, pkg)
                vm.goHome()
            },
            Modifier.autoFocus().padding(bottom = 10.dp),
        )
        WideButton(
            if (pinned) "Remove from home row" else "Pin to home row",
            { vm.togglePin(pkg); vm.dismiss() },
            Modifier.padding(bottom = 10.dp),
        )
        if (pinned) {
            WideButton("Move left", { vm.moveFavorite(pkg, -1); vm.dismiss() }, Modifier.padding(bottom = 10.dp))
            WideButton("Move right", { vm.moveFavorite(pkg, 1); vm.dismiss() }, Modifier.padding(bottom = 10.dp))
        }
        WideButton("Hide from Lume", { vm.hide(pkg); vm.dismiss() }, Modifier.padding(bottom = 10.dp))
        WideButton(
            "App info",
            {
                openSettings(ctx, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri = Uri.parse("package:$pkg"))
                vm.goHome()
            },
            Modifier.padding(bottom = 10.dp),
        )
        if (app.isUserApp) {
            WideButton(
                "Uninstall",
                {
                    openSettings(ctx, Intent.ACTION_DELETE, uri = Uri.parse("package:$pkg"))
                    vm.goHome()
                },
            )
        }
    }
}
