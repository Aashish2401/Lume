package com.lume.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------------------------

@Composable
fun Txt(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    weight: FontWeight = FontWeight.Normal,
    spacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            letterSpacing = spacing,
            fontFamily = FontFamily.SansSerif,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------------------------------------
// Remote-friendly click handling: short press = click, long press (hold OK) = long click.
// ---------------------------------------------------------------------------------------------

fun Modifier.tvClickable(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    var longFired by remember { mutableStateOf(false) }
    this
        .onKeyEvent { ev ->
            val k = ev.key
            if (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter) {
                when (ev.type) {
                    KeyEventType.KeyDown -> {
                        if (ev.nativeKeyEvent.repeatCount == 0) {
                            longFired = false
                        } else if (!longFired && longClick != null) {
                            longFired = true
                            longClick?.invoke()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (!longFired) click()
                        longFired = false
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = { longClick?.invoke() }, onTap = { click() })
        }
        .focusable()
}

/** Puts focus on this element shortly after it appears (used by dialogs). */
@Composable
fun Modifier.autoFocus(): Modifier {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(90)
        runCatching { fr.requestFocus() }
    }
    return this.focusRequester(fr)
}

// ---------------------------------------------------------------------------------------------
// Pill button (top bar)
// ---------------------------------------------------------------------------------------------

@Composable
fun Pill(glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (focused) Color.White else Color.White.copy(alpha = 0.10f), tween(100), label = "pillBg",
    )
    val fg = if (focused) Color.Black else Color.White
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(null, onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, fg, 18.dp)
        Spacer(Modifier.width(8.dp))
        Txt(label, 14.sp, color = fg, weight = FontWeight.Medium)
    }
}

/** Full-width button for menus and dialogs. */
@Composable
fun WideButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (focused) Color.White else Color.White.copy(alpha = 0.08f), tween(100), label = "btnBg",
    )
    val fg = if (focused) Color.Black else Color.White
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(null, onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Txt(label, 17.sp, color = fg, weight = FontWeight.Medium)
        if (hint != null) Txt(hint, 13.sp, color = fg.copy(alpha = 0.6f))
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

/** 16:9 focusable tile with a scale-up and white ring on focus. */
@Composable
fun TileFrame(
    width: Dp,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    background: Color = Color(0xFF17171B),
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.09f else 1f, tween(110), label = "tileScale")
    val shape = RoundedCornerShape(14.dp)
    val sizeMod = if (width == Dp.Unspecified) {
        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    } else {
        Modifier.size(width, width * 9 / 16)
    }
    Box(
        modifier
            .then(sizeMod)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(background)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .tvClickable(onLongClick, onClick),
        content = content,
    )
}

@Composable
fun AppCard(
    app: AppEntry,
    width: Dp,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    TileFrame(width, modifier, onFocused, onLongClick, onClick) {
        if (app.banner != null) {
            Image(app.banner, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Image(
                app.icon, contentDescription = null,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 14.dp).size(44.dp),
            )
            Txt(
                app.label, 12.sp,
                Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
fun GlyphTile(
    glyph: Glyph,
    label: String,
    width: Dp,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    TileFrame(width, modifier, onFocused, null, onClick) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlyphIcon(glyph, Color.White, 30.dp)
            Spacer(Modifier.size(8.dp))
            Txt(label, 14.sp, weight = FontWeight.Medium)
        }
    }
}

@Composable
fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                when {
                    focused -> Modifier.border(3.dp, Color.White, CircleShape)
                    selected -> Modifier.border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                    else -> Modifier
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(null, onClick),
    )
}
