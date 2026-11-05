package com.lume.launcher

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

@Immutable
data class AppEntry(
    val pkg: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap,
    val isUserApp: Boolean,
)

sealed interface Overlay {
    data object None : Overlay
    data object Drawer : Overlay
    data object Search : Overlay
    data object Quick : Overlay
    data object Inputs : Overlay
    data object Personalize : Overlay
    data class AppMenu(val pkg: String) : Overlay
}

data class Accent(val name: String, val color: Color)

val Accents = listOf(
    Accent("Ember", Color(0xFFD32F2F)),
    Accent("Ocean", Color(0xFF1E88E5)),
    Accent("Violet", Color(0xFF7E57C2)),
    Accent("Forest", Color(0xFF2E9E5B)),
    Accent("Amber", Color(0xFFEF6C00)),
    Accent("Slate", Color(0xFF607D8B)),
)

/** Pinned on first run if installed. Matched by package name, so it works on any TV brand. */
val DEFAULT_PINS = listOf(
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "com.google.android.youtube.tv",
    "in.startv.hotstar",
    "com.sonyliv",
    "org.jellyfin.androidtv",
    "com.plexapp.android",
    "com.disney.disneyplus",
    "com.spotify.tv.android",
    "com.google.android.youtube.tvmusic",
    "com.android.vending",
    "com.apple.atve.androidtv.appletv",
    "org.xbmc.kodi",
    "org.videolan.vlc",
)
