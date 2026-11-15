package com.example.quietdialer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// All the motion of the incoming call screen lives in this file, so it is easy to tweak.

private val ArcGreen = Color(0xFF46F26B)
private val ArcRed = Color(0xFFFF4545)

/** 0 -> 1 once when the screen opens. [delayMs] staggers the pieces (name first, then the buttons). */
@Composable
fun rememberEntrance(delayMs: Int = 0, durationMs: Int = 650): State<Float> {
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    return animateFloatAsState(
        targetValue = if (go) 1f else 0f,
        animationSpec = tween(durationMs, delayMs, FastOutSlowInEasing),
        label = "entrance"
    )
}

/**
 * The centre of the incoming call screen: a glass circle with a green arc above it (swipe up to
 * answer) and a red arc below it (swipe down to decline).
 *
 * Motion:
 *  - the arcs glow and drift a little in the swipe direction (a hint)
 *  - the circle slowly "breathes" and a ripple spreads out of it
 *  - the phone icon gives a small shake every few seconds
 *  - dragging brightens the matching arc, fades the other one and the circle follows the finger
 *  - letting go too early springs back; answering expands and fades the circle
 */
@Composable
fun SwipeAnswerControl(onAnswer: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val answer by rememberUpdatedState(onAnswer)
    val decline by rememberUpdatedState(onDecline)
    val needPx = with(density) { 90.dp.toPx() }
    val maxPx = with(density) { 140.dp.toPx() }

    var dragY by remember { mutableFloatStateOf(0f) }
    var leave by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }

    val progress = (abs(dragY) / needPx).coerceIn(0f, 1f)
    val upP = if (dragY < 0f) progress else 0f
    val downP = if (dragY > 0f) progress else 0f
    val ready = progress >= 1f

    val inf = rememberInfiniteTransition(label = "ringing")
    val arcPulse by inf.animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "arcPulse"
    )
    val breathe by inf.animateFloat(
        1f, 1.05f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val ripple by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearOutSlowInEasing), RepeatMode.Restart),
        label = "ripple"
    )
    val wiggle by inf.animateFloat(
        0f, 0f,
        infiniteRepeatable(
            keyframes<Float> {
                durationMillis = 3400
                0f at 0
                0f at 2600
                -16f at 2680
                16f at 2770
                -12f at 2860
                12f at 2950
                -6f at 3040
                0f at 3130
                0f at 3400
            },
            RepeatMode.Restart
        ),
        label = "wiggle"
    )

    fun springBack() {
        scope.launch {
            animate(dragY, 0f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) { v, _ -> dragY = v }
        }
    }

    fun finish() {
        when {
            busy -> {}
            dragY <= -needPx -> {
                busy = true
                scope.launch {
                    animate(0f, 1f, animationSpec = tween(240)) { v, _ -> leave = v }
                    answer()
                }
            }
            dragY >= needPx -> { busy = true; decline() }
            else -> springBack()
        }
    }

    Box(
        modifier.fillMaxWidth().height(360.dp).pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { },
                onVerticalDrag = { change, dy ->
                    if (!busy) {
                        change.consume()
                        dragY = (dragY + dy).coerceIn(-maxPx, maxPx)
                    }
                },
                onDragEnd = { finish() },
                onDragCancel = { if (!busy) springBack() }
            )
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val circleR = 75.dp.toPx()
            val arcR = 158.dp.toPx()
            val drift = ((arcPulse - 0.55f) / 0.45f) * 8.dp.toPx()
            val idle = 1f - progress

            fun arc(startDeg: Float, color: Color, alpha: Float, dy: Float, width: Float) {
                val tl = Offset(c.x - arcR, c.y - arcR + dy)
                val sz = Size(arcR * 2f, arcR * 2f)
                // soft glow under the arc, then the arc itself
                drawArc(
                    color = color.copy(alpha = alpha * 0.28f), startAngle = startDeg, sweepAngle = 68f,
                    useCenter = false, topLeft = tl, size = sz,
                    style = Stroke(width * 2.4f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = color.copy(alpha = alpha), startAngle = startDeg, sweepAngle = 68f,
                    useCenter = false, topLeft = tl, size = sz,
                    style = Stroke(width, cap = StrokeCap.Round)
                )
            }

            val fade = 1f - leave
            val greenA = (arcPulse + (1f - arcPulse) * upP) * (1f - downP) * fade
            val redA = (arcPulse + (1f - arcPulse) * downP) * (1f - upP) * fade
            arc(-124f, ArcGreen, greenA, -drift * idle, (9f + 4f * upP).dp.toPx())
            arc(56f, ArcRed, redA, drift * idle, (9f + 4f * downP).dp.toPx())

            // two ripples spreading out of the circle, half a cycle apart
            if (!busy) {
                val centre = Offset(c.x, c.y + dragY)
                for (phase in listOf(0f, 0.5f)) {
                    val p = (ripple + phase) % 1f
                    drawCircle(
                        color = Color.White.copy(alpha = 0.32f * (1f - p) * idle),
                        radius = circleR * (1.02f + 0.7f * p),
                        center = centre,
                        style = Stroke(2.dp.toPx())
                    )
                }
            }
        }

        Box(
            Modifier.align(Alignment.Center)
                .offset { IntOffset(0, dragY.roundToInt()) }
                .graphicsLayer {
                    val s = breathe * (1f - 0.1f * progress) * (1f + 1.6f * leave)
                    scaleX = s
                    scaleY = s
                    alpha = 1f - leave
                }
                .size(150.dp)
                .glass(CircleShape, 0.04f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (downP > 0.4f) Icons.Default.CallEnd else Icons.Default.Call,
                contentDescription = if (downP > 0.4f) "Decline" else "Answer",
                tint = Color.White,
                modifier = Modifier.size(60.dp).graphicsLayer { rotationZ = if (progress < 0.05f) wiggle else 0f }
            )
        }

        if (ready && !busy) {
            Text(
                if (dragY < 0f) "Release to answer" else "Release to decline",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(if (dragY < 0f) Alignment.TopCenter else Alignment.BottomCenter).padding(vertical = 6.dp)
            )
        }
    }
}
