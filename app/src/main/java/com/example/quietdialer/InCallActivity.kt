package com.example.quietdialer

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class InCallActivity : ComponentActivity() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var resumed = false
    private var wantProximity = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setupBackground()
        handleAnswer(intent)

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Content()
                }
            }
        }
    }

    /** Blurred wallpaper: system wallpaper + blur + dark tint. */
    private fun setupBackground() {
        var blur = false
        if (Build.VERSION.SDK_INT >= 31) {
            blur = getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
            window.setBackgroundBlurRadius(150)
        }
        window.setBackgroundDrawable(
            ColorDrawable(if (blur) 0x66000000 else 0xB3000000.toInt())
        )
    }

    @Composable
    private fun Content() {
        val snaps by CallManager.snaps.collectAsState()
        val audio by CallManager.audio.collectAsState()
        val primary = snaps.firstOrNull { it.state == Call.STATE_RINGING } ?: snaps.firstOrNull()

        if (primary == null) {
            LaunchedEffect(Unit) { finish() }
            return
        }

        val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
        val inCallNow = primary.state == Call.STATE_ACTIVE ||
            primary.state == Call.STATE_DIALING || primary.state == Call.STATE_CONNECTING
        val useProximity = inCallNow && route == CallAudioState.ROUTE_EARPIECE

        LaunchedEffect(useProximity) {
            wantProximity = useProximity
            applyProximity()
        }

        if (primary.state == Call.STATE_RINGING) {
            IncomingScreen(primary)
        } else {
            CallScreen(primary, audio?.isMuted == true, route == CallAudioState.ROUTE_SPEAKER)
        }
    }

    // ---------------- Incoming call screen ----------------

    @Composable
    private fun IncomingScreen(c: CallSnap) {
        var showReplies by remember { mutableStateOf(false) }
        val replies = listOf(
            "Can't talk now. Call you later?",
            "I'll call you back.",
            "I'm in a meeting.",
            "On my way."
        )

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(96.dp))
            Text(c.number, fontSize = 36.sp, color = Color.White)
            if (c.rawNumber.isNotBlank() && c.rawNumber != c.number) {
                Spacer(Modifier.height(6.dp))
                Text(c.rawNumber, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(20.dp))
            Text("Incoming call", fontSize = 18.sp, color = Color.White)

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SwipeCallButton(Color(0xFFE53935), 135f, "Decline") {
                    c.call.reject(false, null)
                }
                SwipeCallButton(Color(0xFF2E9E4F), 0f, "Answer") {
                    c.call.answer(VideoProfile.STATE_AUDIO_ONLY)
                }
            }

            Spacer(Modifier.height(28.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x33FFFFFF))
                    .clickable { showReplies = true }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) { Text("SMS reply", fontSize = 15.sp, color = Color.White) }
            Spacer(Modifier.height(32.dp))
        }

        if (showReplies) {
            AlertDialog(
                onDismissRequest = { showReplies = false },
                containerColor = Color(0xFF2B2B2B),
                title = { Text("Reply with message", color = Color.White) },
                text = {
                    Column {
                        replies.forEach { r ->
                            Text(
                                r, color = Color.White, fontSize = 17.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showReplies = false
                                        sendSmsReply(c.rawNumber, r)
                                        c.call.reject(false, null)
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                },
                confirmButton = {}
            )
        }
    }

    private fun sendSmsReply(number: String, text: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                    .putExtra("sms_body", text)
            )
        } catch (e: Exception) {
            // no messaging app found
        }
    }

    /** Round button: tap it, or drag it up to trigger. */
    @Composable
    private fun SwipeCallButton(color: Color, iconRotation: Float, label: String, onTrigger: () -> Unit) {
        var dy by remember { mutableFloatStateOf(0f) }
        val maxPx = with(LocalDensity.current) { 140.dp.toPx() }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.KeyboardArrowUp, null, tint = color.copy(alpha = 0.5f))
            Icon(
                Icons.Default.KeyboardArrowUp, null, tint = color,
                modifier = Modifier.offset(y = (-14).dp)
            )
            Box(
                Modifier
                    .offset { IntOffset(0, dy.roundToInt()) }
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onTrigger() }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (dy <= -maxPx * 0.7f) onTrigger() else dy = 0f },
                            onDragCancel = { dy = 0f },
                            onVerticalDrag = { _, d -> dy = (dy + d).coerceIn(-maxPx, 0f) }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Call, label, tint = Color.White,
                    modifier = Modifier.rotate(iconRotation)
                )
            }
        }
    }

    // ---------------- Ongoing call screen (unchanged for now) ----------------

    @Composable
    private fun CallScreen(c: CallSnap, muted: Boolean, speaker: Boolean) {
        val elapsed by produceState(0L, c.connectTime) {
            while (true) {
                value = if (c.connectTime > 0) (System.currentTimeMillis() - c.connectTime) / 1000 else 0
                delay(1000)
            }
        }
        val status = when (c.state) {
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"
            Call.STATE_ACTIVE -> "%02d:%02d".format(elapsed / 60, elapsed % 60)
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
            else -> ""
        }
        val chipColors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedLabelColor = Color.Black,
            selectedContainerColor = Color.White
        )

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 60.dp)) {
                Text(c.number, fontSize = 32.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize = 18.sp, color = Color.White)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 48.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilterChip(
                        selected = muted,
                        onClick = { CallService.instance?.setMuted(!muted) },
                        label = { Text("Mute") },
                        colors = chipColors
                    )
                    FilterChip(
                        selected = speaker,
                        onClick = {
                            CallService.instance?.setAudioRoute(
                                if (speaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
                            )
                        },
                        label = { Text("Speaker") },
                        colors = chipColors
                    )
                }
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { c.call.disconnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) { Text("Hang up", color = Color.White) }
            }
        }
    }

    // ---------------- Proximity sensor: only while this screen is visible ----------------

    private fun applyProximity() {
        if (resumed && wantProximity) {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    wakeLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "QuietDialer:proximity")
                }
            }
            wakeLock?.let { if (!it.isHeld) it.acquire(4 * 60 * 60 * 1000L) }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    override fun onResume() { super.onResume(); resumed = true; applyProximity() }
    override fun onPause() { resumed = false; applyProximity(); super.onPause() }
    override fun onDestroy() { resumed = false; applyProximity(); super.onDestroy() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAnswer(intent)
    }

    private fun handleAnswer(i: Intent?) {
        if (i?.getBooleanExtra(CallService.EXTRA_ANSWER, false) == true) {
            CallManager.primary()?.takeIf { it.state == Call.STATE_RINGING }
                ?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }
    }
}
