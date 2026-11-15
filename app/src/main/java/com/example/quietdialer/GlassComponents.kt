package com.example.quietdialer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Frosted glass for the call screens.
 *
 * [GlassHost] draws the background the user picked for calls and, once, makes a small blurred copy of it.
 * Every [glass] surface (buttons, cards, pills) then shows the part of that blurred copy that sits
 * right behind it. So the buttons have no colour of their own: they just blur the chosen background.
 * It is done by hand (no RenderEffect), so it looks the same on every Android version.
 */
@Stable
class GlassBackdrop {
    var blurred by mutableStateOf<ImageBitmap?>(null)
    var origin by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
}

val LocalGlass = compositionLocalOf<GlassBackdrop?> { null }

@Composable
fun GlassHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    remember { CallBg.init(ctx) }
    val choice by CallBg.choice.collectAsState()
    val photoVersion by CallBg.photoVersion.collectAsState()
    val glass = remember { GlassBackdrop() }

    LaunchedEffect(choice, photoVersion, glass.size) {
        val s = glass.size
        if (s.width > 0 && s.height > 0) {
            glass.blurred = withContext(Dispatchers.Default) { renderBlurredBackdrop(ctx, choice, s) }
        }
    }

    Box(
        modifier.onGloballyPositioned {
            glass.origin = it.positionInWindow()
            glass.size = it.size
        }
    ) {
        BackdropFor(choice, Modifier.matchParentSize())
        CompositionLocalProvider(LocalGlass provides glass) { content() }
    }
}

/**
 * Makes this element a piece of frosted glass of the given [shape]. [boost] makes it a little
 * brighter (used for "switched on" buttons such as Mute) without giving it any colour.
 */
fun Modifier.glass(shape: Shape, boost: Float = 0f): Modifier = composed {
    val glass = LocalGlass.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .clip(shape)
        .onGloballyPositioned { origin = it.positionInWindow() }
        .drawBehind {
            val g = glass
            val bmp = g?.blurred
            if (g != null && bmp != null && g.size.width > 0) {
                drawImage(
                    bmp,
                    dstOffset = IntOffset(
                        (g.origin.x - origin.x).roundToInt(),
                        (g.origin.y - origin.y).roundToInt()
                    ),
                    dstSize = g.size,
                    filterQuality = FilterQuality.High
                )
            } else {
                drawRect(Color.White.copy(alpha = 0.10f))
            }
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.20f + boost), Color.White.copy(alpha = 0.07f + boost))
                )
            )
        }
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.30f))
            ),
            shape
        )
}

/** Round avatar made of glass: the first letter of the name, or a person icon when there is no name. */
@Composable
fun GlassAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).glass(CircleShape, 0.08f), contentAlignment = Alignment.Center) {
        if (name.isBlank()) {
            Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        } else {
            Text(initialOf(name).toString(), color = Color.White, fontSize = (size.value * 0.42f).sp)
        }
    }
}

// ---------------------------------------------------------------- blurred copy of the backdrop

private const val DOWNSCALE = 14

/** Draws the chosen background at 1/14 size and blurs it. Small on purpose: cheap and very soft. */
private fun renderBlurredBackdrop(ctx: Context, id: String, size: IntSize): ImageBitmap {
    val w = maxOf(16, size.width / DOWNSCALE)
    val h = maxOf(16, size.height / DOWNSCALE)
    val img = ImageBitmap(w, h)
    val photo: Bitmap? = if (id == "photo") loadSampled(CallBg.photoFile(ctx).path, w * 2, h * 2) else null

    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
        if (id == "photo") {
            drawRect(Color(0xFF111111))
            if (photo != null) {
                // Same centre-crop the real background uses.
                val s = maxOf(w / photo.width.toFloat(), h / photo.height.toFloat())
                val sw = minOf(photo.width, (w / s).toInt().coerceAtLeast(1))
                val sh = minOf(photo.height, (h / s).toInt().coerceAtLeast(1))
                drawImage(
                    photo.asImageBitmap(),
                    srcOffset = IntOffset((photo.width - sw) / 2, (photo.height - sh) / 2),
                    srcSize = IntSize(sw, sh),
                    dstSize = IntSize(w, h),
                    filterQuality = FilterQuality.Medium
                )
            }
            drawRect(Color.Black.copy(alpha = 0.35f)) // same dark layer as the real photo background
        } else {
            val preset = BG_PRESETS.firstOrNull { it.id == id } ?: BG_PRESETS[0]
            preset.draw(this)
        }
    }
    boxBlur(img.asAndroidBitmap(), radius = 2, passes = 3)
    return img
}

private fun loadSampled(path: String, wantW: Int, wantH: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= wantW && bounds.outHeight / (sample * 2) >= wantH) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (e: Exception) { null }

private fun boxBlur(bmp: Bitmap, radius: Int, passes: Int) {
    val w = bmp.width
    val h = bmp.height
    val a = IntArray(w * h)
    val b = IntArray(w * h)
    bmp.getPixels(a, 0, w, 0, 0, w, h)
    repeat(passes) {
        blurLines(a, b, w, h, radius, true)
        blurLines(b, a, w, h, radius, false)
    }
    bmp.setPixels(a, 0, w, 0, 0, w, h)
}

private fun blurLines(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val n = r * 2 + 1
    for (l in 0 until lines) {
        for (i in 0 until len) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val j = (i + k).coerceIn(0, len - 1)
                val p = if (horizontal) src[l * w + j] else src[j * w + l]
                sa += p ushr 24
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            val out = ((sa / n) shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
            if (horizontal) dst[l * w + i] = out else dst[i * w + l] = out
        }
    }
}
