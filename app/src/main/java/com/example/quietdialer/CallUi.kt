package com.example.quietdialer

import android.telecom.Call
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Red = Color(0xFFFF3B30)
private val Green = Color(0xFF34C759)
// Translucent so the chosen call background shows through the round buttons.
private val Btn = Color(0x73081A66)

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
    recording: Boolean,
    onRecord: () -> Unit,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onSmsReply: (String) -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHold: () -> Unit,
    onAddCall: () -> Unit,
    onVideo: () -> Unit,
    onMessages: () -> Unit,
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
        Call.STATE_RINGING -> ""
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling..."
        Call.STATE_ACTIVE -> "%02d:%02d".format(elapsed / 60, elapsed % 60)
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
        else -> ""
    }
    var showPad by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var showSms by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        CallBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(if (showPad) 24.dp else 72.dp))
            Text(
                c.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                c.subtitle ?: regionLabel(c.number).ifBlank { "mobile" },
                color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (status.isNotEmpty()) {
                Text(status, color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
            }
            if (ringing) {
                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                        .clickable { showSms = true }.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ChatBubble, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reply with message", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
            }
            if (recording) {
                Spacer(Modifier.height(10.dp))
                Text("● Recording", color = Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (other != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.clip(CircleShape).background(Btn).clickable(onClick = onSwap)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("On hold: ${other.title}  ·  Swap", color = Color.White, fontSize = 14.sp) }
            }
            if (showPad) {
                Spacer(Modifier.height(8.dp))
                Text(typed, color = Color.White, fontSize = 28.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))

            when {
                ringing -> SwipeControls(onAnswer, onDecline)
                showPad -> InCallKeypad(
                    onKey = { ch, down -> if (down) typed += ch; onDtmf(ch, down) },
                    onHide = { showPad = false },
                    onEnd = onEnd
                )
                else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundAction("Video call", Icons.Default.Videocam, onVideo, enabled = live)
                        RoundAction("Add call", Icons.Default.Add, onAddCall, enabled = live)
                        RoundAction("Messages", Icons.Default.ChatBubble, onMessages)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundAction("Mute", if (muted) Icons.Default.MicOff else Icons.Default.Mic, onMute, active = muted)
                        RoundAction("Hold", Icons.Default.Pause, onHold, active = onHoldNow, enabled = live && c.canHold)
                        RoundAction(
                            if (recording) "Stop" else "Record", Icons.Default.GraphicEq, onRecord,
                            active = recording, enabled = live
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
                    ) {
                        RoundAction("Speaker", Icons.Default.VolumeUp, onSpeaker, active = speaker, size = 64, showLabel = false)
                        RoundAction("End call", Icons.Default.CallEnd, onEnd, container = Red, size = 76, showLabel = false)
                        RoundAction("Keypad", Icons.Default.Dialpad, { showPad = true }, enabled = live, size = 64, showLabel = false)
                    }
                }
            }
            Spacer(Modifier.height(if (ringing) 24.dp else 32.dp))
        }
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

@Composable
private fun RoundAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    container: Color? = null,
    size: Int = 72,
    showLabel: Boolean = true
) {
    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size.dp).clip(CircleShape)
                .background(container ?: if (active) Color.White else Btn)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, label, modifier = Modifier.size(30.dp),
                tint = when {
                    container != null -> Color.White
                    active -> Color.Black
                    else -> Color.White.copy(alpha = if (enabled) 1f else 0.4f)
                }
            )
        }
        if (showLabel) {
            Spacer(Modifier.height(8.dp))
            Text(label, color = Color.White.copy(alpha = if (enabled) 0.95f else 0.4f), fontSize = 13.sp, maxLines = 1)
        }
    }
}

/**
 * Incoming call buttons: decline on the left, accept on the right. Drag one up and let go to use it;
 * if you let go too early it springs back.
 */
@Composable
private fun SwipeControls(onAnswer: () -> Unit, onDecline: () -> Unit) {
    var active by remember { mutableIntStateOf(0) } // 0 = none, 1 = decline, 2 = accept
    Row(Modifier.fillMaxWidth()) {
        SwipeSlot(Red, Icons.Default.CallEnd, "Decline", hidden = active == 2, onActive = { active = if (it) 1 else 0 }, onTrigger = onDecline, modifier = Modifier.weight(1f))
        SwipeSlot(Green, Icons.Default.Call, "Accept", hidden = active == 1, onActive = { active = if (it) 2 else 0 }, onTrigger = onAnswer, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SwipeSlot(
    container: Color,
    icon: ImageVector,
    desc: String,
    hidden: Boolean,
    onActive: (Boolean) -> Unit,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val maxPx = with(density) { 240.dp.toPx() }
    val needPx = with(density) { 120.dp.toPx() }
    val scope = rememberCoroutineScope()
    val trigger by rememberUpdatedState(onTrigger)
    var offY by remember { mutableFloatStateOf(0f) }
    val progress = (-offY / needPx).coerceIn(0f, 1f)
    val ready = offY <= -needPx

    val pulse = rememberInfiniteTransition(label = "hint")
    val hint by pulse.animateFloat(0.25f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "hint")

    fun springBack() {
        scope.launch { animate(offY, 0f, animationSpec = spring()) { v, _ -> offY = v } }
    }

    Box(
        modifier.height(170.dp).alpha(if (hidden) 0f else 1f)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { onActive(true) },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        offY = (offY + dy).coerceIn(-maxPx, 0f)
                    },
                    onDragEnd = {
                        val fire = offY <= -needPx
                        onActive(false)
                        if (fire) { offY = 0f; trigger() } else springBack()
                    },
                    onDragCancel = { onActive(false); springBack() }
                )
            }
    ) {
        Icon(
            Icons.Default.KeyboardArrowUp, null, tint = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(26.dp).alpha(hint * (1f - progress))
        )
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                .offset { IntOffset(0, offY.roundToInt()) }
                .scale(1f - 0.25f * progress).alpha(1f - 0.25f * progress)
                .size(76.dp).clip(CircleShape).background(container),
            contentAlignment = Alignment.Center
        ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(32.dp)) }
        if (ready) {
            Text("Release", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun InCallKeypad(onKey: (Char, Boolean) -> Unit, onHide: () -> Unit, onEnd: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { ch ->
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(Btn)
                            .pointerInput(ch) {
                                detectTapGestures(onPress = {
                                    onKey(ch, true)
                                    tryAwaitRelease()
                                    onKey(ch, false)
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) { Text(ch.toString(), color = Color.White, fontSize = 28.sp) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(92.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = onHide) { Text("Hide", color = Color.White, fontSize = 16.sp) }
            }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Red).clickable(onClick = onEnd),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.CallEnd, "End", tint = Color.White, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.width(92.dp))
        }
    }
}
