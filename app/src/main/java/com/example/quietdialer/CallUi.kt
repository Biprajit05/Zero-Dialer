package com.example.quietdialer

import android.telecom.Call
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Red = Color(0xFFFF3B30)
private val Green = Color(0xFF34C759)
private val ScreenBg = Color(0xFF2B2B2D)
private val Btn = Color(0xFF4F4F52)

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

    Box(Modifier.fillMaxSize().background(ScreenBg)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(if (showPad) 24.dp else 64.dp))
            if (status.isNotEmpty()) {
                Text(status, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                c.title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                c.subtitle ?: "mobile", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (recording) {
                Spacer(Modifier.height(8.dp))
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
                ringing -> IncomingControls(onAnswer, onDecline, onSmsReply)
                showPad -> InCallKeypad(
                    onKey = { ch, down -> if (down) typed += ch; onDtmf(ch, down) },
                    onHide = { showPad = false },
                    onEnd = onEnd
                )
                else -> Column(
                    Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundAction("Speaker", Icons.Default.VolumeUp, onSpeaker, active = speaker)
                        RoundAction("Hold", Icons.Default.Pause, onHold, active = onHoldNow, enabled = live && c.canHold)
                        RoundAction("Mute", if (muted) Icons.Default.MicOff else Icons.Default.Mic, onMute, active = muted)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundAction("Add call", Icons.Default.Add, onAddCall, enabled = live)
                        RoundAction(
                            if (recording) "Stop" else "Record", Icons.Default.FiberManualRecord, onRecord,
                            active = recording, enabled = live
                        )
                        RoundAction("Keypad", Icons.Default.Dialpad, { showPad = true }, enabled = live)
                    }
                    RoundAction("End", Icons.Default.CallEnd, onEnd, container = Red)
                }
            }
            Spacer(Modifier.height(36.dp))
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
    container: Color? = null,
    size: Int = 72
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
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.9f else 0.4f), fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun IncomingControls(onAnswer: () -> Unit, onDecline: () -> Unit, onSmsReply: (String) -> Unit) {
    var showSms by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column(
                Modifier.clip(CircleShape).clickable { showSms = true }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Btn), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Message, "Message", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("Message", color = Color.White, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundAction("Decline", Icons.Default.CallEnd, onDecline, container = Red, size = 76)
            RoundAction("Accept", Icons.Default.Call, onAnswer, container = Green, size = 76)
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
