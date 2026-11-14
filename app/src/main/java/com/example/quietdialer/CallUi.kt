package com.example.quietdialer

import android.telecom.Call
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Red = Color(0xFFE0483E)
private val Green = Color(0xFF3DBE29)
private val Glass = Color.White.copy(alpha = 0.16f)

private val QUICK_REPLIES = listOf(
    "Can't talk now. I'll call you back.",
    "I'm busy right now. What's up?",
    "I'll call you later.",
    "Call me in a few minutes."
)

@Composable
fun CallScreen(
    c: CallSnap,
    other: CallSnap?,
    muted: Boolean,
    speaker: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onSmsReply: (String) -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHold: () -> Unit,
    onAddCall: () -> Unit,
    onEnd: () -> Unit,
    onDtmf: (Char, Boolean) -> Unit,
    onSwap: () -> Unit
) {
    val elapsed by produceState(0L, c.connectTime) {
        while (true) {
            value = if (c.connectTime > 0) (System.currentTimeMillis() - c.connectTime) / 1000 else 0
            delay(1000)
        }
    }
    val ringing = c.state == Call.STATE_RINGING
    val onHoldNow = c.state == Call.STATE_HOLDING
    val live = c.state == Call.STATE_ACTIVE || onHoldNow
    val status = when (c.state) {
        Call.STATE_RINGING -> "Incoming call"
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling"
        Call.STATE_ACTIVE -> "%02d:%02d".format(elapsed / 60, elapsed % 60)
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
        else -> ""
    }
    var showPad by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Color(0xFF151515))
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF5B2F10), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.22f),
                    radius = size.width
                )
            )
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xFF444444)),
                    startY = size.height * 0.55f, endY = size.height
                )
            )
        }
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(if (showPad) 24.dp else 72.dp))
            Text(
                c.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            c.subtitle?.let {
                Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(if (showPad) 8.dp else 28.dp))
            Text(status, color = Color.White, fontSize = 18.sp)
            if (other != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Glass).clickable(onClick = onSwap)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("On hold: ${other.title}  ·  Swap", color = Color.White, fontSize = 14.sp) }
            }
            if (showPad) {
                Spacer(Modifier.height(8.dp))
                Text(typed, color = Color.White, fontSize = 28.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))

            when {
                ringing -> IncomingControls(onAnswer, onDecline, onSmsReply)
                showPad -> InCallKeypad(
                    onKey = { ch, down -> if (down) typed += ch; onDtmf(ch, down) },
                    onHide = { showPad = false },
                    onEnd = onEnd
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundAction("Mute", if (muted) Icons.Default.MicOff else Icons.Default.Mic, onMute, active = muted)
                        RoundAction("Hold", Icons.Default.Pause, onHold, active = onHoldNow, enabled = live && c.canHold)
                        RoundAction("Add call", Icons.Default.Add, onAddCall, enabled = live)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundAction("Speaker", Icons.Default.VolumeUp, onSpeaker, active = speaker)
                        RoundAction("End", Icons.Default.CallEnd, onEnd, container = Red)
                        RoundAction("Dial pad", Icons.Default.Dialpad, { showPad = true }, enabled = live)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RoundAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    container: Color? = null
) {
    Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(container ?: if (active) Color.White else Glass)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, label, modifier = Modifier.size(28.dp),
                tint = when {
                    container != null -> Color.White
                    active -> Color.Black
                    else -> Color.White.copy(alpha = if (enabled) 1f else 0.4f)
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun IncomingControls(onAnswer: () -> Unit, onDecline: () -> Unit, onSmsReply: (String) -> Unit) {
    var showSms by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SwipeCallButton(Red, Icons.Default.CallEnd, "Decline", onDecline)
            SwipeCallButton(Green, Icons.Default.Call, "Answer", onAnswer)
        }
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f)).clickable { showSms = true }
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) { Text("SMS reply", color = Color.White, fontSize = 14.sp) }
    }
    if (showSms) {
        AlertDialog(
            onDismissRequest = { showSms = false },
            containerColor = Color.White,
            title = { Text("Decline with message") },
            text = {
                Column {
                    QUICK_REPLIES.forEach { r ->
                        Text(
                            r, fontSize = 16.sp, color = Color(0xFF1B1B1B),
                            modifier = Modifier.fillMaxWidth()
                                .clickable { showSms = false; onSmsReply(r) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSms = false }) { Text("Cancel") } }
        )
    }
}

/** Round button with pulsing chevrons above it: tap it, or swipe it up. */
@Composable
private fun SwipeCallButton(color: Color, icon: ImageVector, desc: String, onTrigger: () -> Unit) {
    val thresholdPx = with(LocalDensity.current) { 90.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val trigger by rememberUpdatedState(onTrigger)
    val pulse by rememberInfiniteTransition(label = "chev").animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.alpha(pulse).size(width = 32.dp, height = 40.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, null, tint = color, modifier = Modifier.size(32.dp).align(Alignment.TopCenter))
            Icon(Icons.Default.KeyboardArrowUp, null, tint = color, modifier = Modifier.size(32.dp).align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.offset { IntOffset(0, drag.roundToInt()) }
                .size(72.dp).clip(CircleShape).background(color)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (drag < -thresholdPx) trigger(); drag = 0f },
                        onDragCancel = { drag = 0f }
                    ) { _, dy -> drag = (drag + dy).coerceIn(-thresholdPx * 1.6f, 0f) }
                }
                .clickable { trigger() },
            contentAlignment = Alignment.Center
        ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(32.dp)) }
    }
}

@Composable
private fun InCallKeypad(onKey: (Char, Boolean) -> Unit, onHide: () -> Unit, onEnd: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { ch ->
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Glass)
                            .pointerInput(ch) {
                                detectTapGestures(onPress = {
                                    onKey(ch, true)
                                    tryAwaitRelease()
                                    onKey(ch, false)
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) { Text(ch.toString(), color = Color.White, fontSize = 26.sp) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(88.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = onHide) { Text("Hide", color = Color.White, fontSize = 16.sp) }
            }
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Red).clickable(onClick = onEnd),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.CallEnd, "End", tint = Color.White, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(88.dp))
        }
    }
}
