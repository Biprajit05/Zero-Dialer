package com.example.quietdialer

import android.telecom.Call
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Red = Color(0xFFFF3B30)
private val Green = Color(0xFF34C759)
private val Orange = Color(0xFFFFB02E)

/** Soft white glow behind big white text. */
private val Glow = TextStyle(shadow = Shadow(Color.White.copy(alpha = 0.35f), Offset.Zero, 28f))

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
    onSwap: () -> Unit,
    onMerge: () -> Unit,
    onEndCall: (Call) -> Unit
) {
    val elapsed by produceState(0L, c.connectTime) {
        while (true) {
            value = if (c.connectTime > 0) (System.currentTimeMillis() - c.connectTime) / 1000 else 0
            delay(1000)
        }
    }
    val ringing = c.state == Call.STATE_RINGING
    val timer: String? =
        if (c.connectTime > 0 && (c.state == Call.STATE_ACTIVE || c.state == Call.STATE_HOLDING)) {
            if (elapsed >= 3600) "%d:%02d:%02d".format(elapsed / 3600, elapsed % 3600 / 60, elapsed % 60)
            else "%02d:%02d".format(elapsed / 60, elapsed % 60)
        } else null
    var showSms by remember { mutableStateOf(false) }

    GlassHost(Modifier.fillMaxSize()) {
        if (ringing) {
            IncomingContent(c, onAnswer, onDecline, onQuick = { showSms = true })
        } else {
            OngoingContent(
                c, other, timer, muted, speaker, recording,
                onRecord, onMute, onSpeaker, onHold, onAddCall, onVideo, onMessages,
                onEnd, onDtmf, onSwap, onMerge, onEndCall
            )
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

// ---------------------------------------------------------------- incoming call

@Composable
private fun IncomingContent(c: CallSnap, onAnswer: () -> Unit, onDecline: () -> Unit, onQuick: () -> Unit) {
    val head by rememberEntrance(0, 650)
    val body by rememberEntrance(150, 750)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(88.dp))
        Column(
            Modifier.graphicsLayer {
                alpha = head
                translationY = -28.dp.toPx() * (1f - head)
            },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (c.name.isNullOrBlank() && !c.isConference) {
                FitTitle(c.title, 44.sp, FontWeight.Medium)
            } else {
                Text(
                    c.title, style = Glow, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    lineHeight = 54.sp
                )
            }
            Text("Incoming call...", color = Color.White.copy(alpha = 0.8f), fontSize = 24.sp, modifier = Modifier.padding(top = 6.dp))
            Text(
                c.subtitle ?: regionLabel(c.number).ifBlank { "mobile" },
                color = Color.White, fontSize = 26.sp, modifier = Modifier.padding(top = 10.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        SwipeAnswerControl(
            onAnswer, onDecline,
            Modifier.graphicsLayer {
                alpha = body
                translationY = 60.dp.toPx() * (1f - body)
            }
        )
        Spacer(Modifier.weight(1f))
        GlassPill(
            "Quick message", null, true,
            Modifier.graphicsLayer {
                alpha = body
                translationY = 40.dp.toPx() * (1f - body)
            }.widthIn(min = 220.dp),
            onQuick
        )
        Spacer(Modifier.height(28.dp))
    }
}

// ---------------------------------------------------------------- ongoing call

@Composable
private fun OngoingContent(
    c: CallSnap,
    other: CallSnap?,
    timer: String?,
    muted: Boolean,
    speaker: Boolean,
    recording: Boolean,
    onRecord: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHold: () -> Unit,
    onAddCall: () -> Unit,
    onVideo: () -> Unit,
    onMessages: () -> Unit,
    onEnd: () -> Unit,
    onDtmf: (Char, Boolean) -> Unit,
    onSwap: () -> Unit,
    onMerge: () -> Unit,
    onEndCall: (Call) -> Unit
) {
    val enter by rememberEntrance(0, 450)
    var showPad by remember { mutableStateOf(false) }

    val onHoldNow = c.state == Call.STATE_HOLDING
    val fitTitle = c.name.isNullOrBlank() && !c.isConference
    val live = c.state == Call.STATE_ACTIVE || onHoldNow
    val label = when (c.state) {
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling..."
        Call.STATE_ACTIVE -> "Ongoing Call"
        Call.STATE_HOLDING -> "On Hold"
        Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
        else -> ""
    }

    Column(
        Modifier.fillMaxSize().graphicsLayer { alpha = enter }
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            other != null -> {
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CallCard(
                        Modifier.weight(1f), c.title,
                        if (c.state == Call.STATE_ACTIVE) "Active" + (timer?.let { "  •  $it" } ?: "") else label,
                        held = onHoldNow, onEnd = { onEndCall(c.call) }
                    )
                    CallCard(Modifier.weight(1f), other.title, "On Hold", held = true, onEnd = { onEndCall(other.call) })
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GlassPill("Swap", Icons.Default.SwapVert, true, Modifier.weight(1f), onSwap)
                    GlassPill("Merge", Icons.Default.CallMerge, c.canMerge || other.canMerge, Modifier.weight(1f), onMerge)
                }
                if (recording) {
                    Spacer(Modifier.height(10.dp))
                    Text("● Recording", color = Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            else -> {
                Spacer(Modifier.height(72.dp))
                CallHeader(
                    c.title,
                    if (c.isConference && c.participants.isNotEmpty()) "${c.participants.size} Participants" else label,
                    timer, c.hd, recording, big = true, fit = fitTitle
                )
                if (c.isConference && c.participants.isNotEmpty()) {
                    Spacer(Modifier.height(36.dp))
                    ParticipantsCard(c.participants) { onEndCall(it) }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        ControlGrid(
            live, onHoldNow, c.canHold, muted, speaker, recording, showPad,
            onVideo, onAddCall, onMessages, onMute, onHold, onRecord, onSpeaker,
            onTogglePad = { showPad = !showPad }, onEnd = onEnd,
            onKey = onDtmf
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CallHeader(title: String, label: String, timer: String?, hd: Boolean, recording: Boolean, big: Boolean, fit: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (fit) {
            FitTitle(title, if (big) 40.sp else 28.sp, FontWeight.SemiBold)
        } else {
            Text(
                title, style = Glow, color = Color.White, fontSize = if (big) 40.sp else 28.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = if (big) 46.sp else 33.sp
            )
        }
        if (label.isNotEmpty()) {
            Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = if (big) 22.sp else 18.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (timer != null) {
            Row(Modifier.padding(top = if (big) 10.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hd) {
                    Box(
                        Modifier.border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) { Text("HD", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp))
                }
                Text(timer, color = Color.White.copy(alpha = 0.9f), fontSize = if (big) 24.sp else 18.sp)
            }
        }
        if (recording) {
            Text("● Recording", color = Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Avatar with a small red end-call badge in its corner. */
@Composable
private fun AvatarWithEnd(name: String, size: Dp, onEnd: () -> Unit) {
    Box(Modifier.size(size)) {
        GlassAvatar(name, size)
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 2.dp).size(size * 0.4f)
                .bounceClick(shape = CircleShape, pressedScale = 0.85f, onClick = onEnd)
                .background(Red),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.CallEnd, "End", tint = Color.White, modifier = Modifier.size(size * 0.22f)) }
    }
}

/** One call of a two-call screen: the active one, or the one on hold (orange). */
@Composable
private fun CallCard(modifier: Modifier, name: String, status: String, held: Boolean, onEnd: () -> Unit) {
    Column(
        modifier.glass(RoundedCornerShape(28.dp)).padding(horizontal = 10.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AvatarWithEnd(name, 72.dp, onEnd)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (!held) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(9.dp).clip(CircleShape).background(Green))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, color = if (held) Orange else Color.White.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1)
            if (held) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(18.dp).clip(CircleShape).background(Orange), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Pause, null, tint = Color(0xFF3A2A00), modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}

/** The people in a conference call, each with an end badge. Scrolls sideways if there are many. */
@Composable
private fun ParticipantsCard(people: List<Participant>, onEnd: (Call) -> Unit) {
    Box(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(vertical = 18.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            people.forEach { p ->
                Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AvatarWithEnd(p.label, 76.dp) { onEnd(p.call) }
                    Spacer(Modifier.height(10.dp))
                    Text(p.label, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun GlassPill(text: String, icon: ImageVector?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.alpha(if (enabled) 1f else 0.45f).height(52.dp)
            .bounceClick(shape = shape, pressedScale = 0.95f, enabled = enabled, onClick = onClick)
            .glass(shape).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Color.White, fontSize = 18.sp)
    }
}

// ---------------------------------------------------------------- buttons

private val KEY_SIZE = 72.dp
private val KEY_GAP = 14.dp
private val PAD_AREA = KEY_SIZE * 4 + KEY_GAP * 3

/**
 * The button area. The six round buttons and the keypad share one spot and cross-fade with a
 * small slide; the bottom row (speaker, end, keypad) never moves. The keypad button shows as
 * pressed while the keypad is open and tapping it again closes the keypad.
 */
@Composable
private fun ControlGrid(
    live: Boolean,
    onHoldNow: Boolean,
    canHold: Boolean,
    muted: Boolean,
    speaker: Boolean,
    recording: Boolean,
    showPad: Boolean,
    onVideo: () -> Unit,
    onAddCall: () -> Unit,
    onMessages: () -> Unit,
    onMute: () -> Unit,
    onHold: () -> Unit,
    onRecord: () -> Unit,
    onSpeaker: () -> Unit,
    onTogglePad: () -> Unit,
    onEnd: () -> Unit,
    onKey: (Char, Boolean) -> Unit
) {
    val p by animateFloatAsState(
        targetValue = if (showPad) 1f else 0f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "keypad"
    )
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(PAD_AREA), contentAlignment = Alignment.BottomCenter) {
            if (p < 0.99f) {
                Column(
                    Modifier.fillMaxWidth().graphicsLayer {
                        alpha = (1f - p * 1.4f).coerceIn(0f, 1f)
                        translationY = 36.dp.toPx() * p
                    },
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundAction("Video call", Icons.Default.Videocam, onVideo, enabled = live)
                        RoundAction("Add call", Icons.Default.Add, onAddCall, enabled = live)
                        RoundAction("Messages", Icons.Default.ChatBubble, onMessages)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundAction("Mute", if (muted) Icons.Default.MicOff else Icons.Default.Mic, onMute, active = muted)
                        RoundAction("Hold", Icons.Default.Pause, onHold, active = onHoldNow, enabled = live && canHold)
                        RoundAction(if (recording) "Stop" else "Record", Icons.Default.GraphicEq, onRecord, active = recording, enabled = live)
                    }
                }
            }
            if (p > 0.01f) {
                Box(
                    Modifier.fillMaxWidth().graphicsLayer {
                        alpha = ((p - 0.15f) / 0.85f).coerceIn(0f, 1f)
                        translationY = 48.dp.toPx() * (1f - p)
                    }
                ) { InCallKeypad(onKey) }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            RoundAction("Speaker", Icons.Default.VolumeUp, onSpeaker, active = speaker)
            EndButton(onEnd)
            RoundAction("Keypad", Icons.Default.Dialpad, onTogglePad, active = showPad, enabled = live)
        }
    }
}

/** Frosted glass button: no colour of its own, it only blurs the background behind it. */
@Composable
private fun RoundAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true
) {
    Column(Modifier.width(92.dp).alpha(if (enabled) 1f else 0.45f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp)
                .bounceClick(shape = CircleShape, pressedScale = 0.88f, enabled = enabled, onClick = onClick)
                .glass(CircleShape, if (active) 0.38f else 0f),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, modifier = Modifier.size(30.dp), tint = if (active) Color(0xFF1F1F1F) else Color.White) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.95f), fontSize = 14.sp, maxLines = 1)
    }
}

/** The big red end button. */
@Composable
private fun EndButton(onEnd: () -> Unit) {
    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.offset(y = (-2).dp).size(76.dp)
                .bounceClick(shape = CircleShape, pressedScale = 0.9f, onClick = onEnd)
                .background(Brush.verticalGradient(listOf(Color(0xFFF0505A), Color(0xFFC62828)))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.CallEnd, "End call", tint = Color.White, modifier = Modifier.size(34.dp)) }
    }
}

@Composable
private fun InCallKeypad(onKey: (Char, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { ch ->
                    Box(
                        Modifier.size(KEY_SIZE).glass(CircleShape)
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
    }
}

/** One-line title that shrinks until it fits. Used when the title is just a phone number. */
@Composable
internal fun FitTitle(text: String, size: TextUnit, weight: FontWeight, modifier: Modifier = Modifier) {
    var current by remember(text, size) { mutableStateOf(size) }
    Text(
        text, style = Glow, color = Color.White, fontSize = current, fontWeight = weight,
        textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        modifier = modifier,
        onTextLayout = { r -> if (r.didOverflowWidth && current.value > 18f) current = (current.value * 0.92f).sp }
    )
}
