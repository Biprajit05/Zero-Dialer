package com.example.quietdialer

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class InCallActivity : ComponentActivity() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var resumed = false
    private var wantProximity = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        handleAnswer(intent)

        setContent {
            MaterialTheme {
                val snaps by CallManager.snaps.collectAsState()
                val audio by CallManager.audio.collectAsState()
                val primary = snaps.firstOrNull { it.state == Call.STATE_RINGING } ?: snaps.firstOrNull()

                if (primary == null) {
                    LaunchedEffect(Unit) { finish() }
                    return@MaterialTheme
                }

                val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
                val inCallNow = primary.state == Call.STATE_ACTIVE ||
                    primary.state == Call.STATE_DIALING || primary.state == Call.STATE_CONNECTING
                val useProximity = inCallNow && route == CallAudioState.ROUTE_EARPIECE

                LaunchedEffect(useProximity) {
                    wantProximity = useProximity
                    applyProximity()
                }

                CallScreen(primary, audio?.isMuted == true, route == CallAudioState.ROUTE_SPEAKER)
            }
        }
    }

    @Composable
    private fun CallScreen(c: CallSnap, muted: Boolean, speaker: Boolean) {
        val elapsed by produceState(0L, c.connectTime) {
            while (true) {
                value = if (c.connectTime > 0) (System.currentTimeMillis() - c.connectTime) / 1000 else 0
                delay(1000)
            }
        }
        val status = when (c.state) {
            Call.STATE_RINGING -> "Incoming call"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"
            Call.STATE_ACTIVE -> "%02d:%02d".format(elapsed / 60, elapsed % 60)
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
            else -> ""
        }

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 80.dp)) {
                Text(c.number, fontSize = 32.sp)
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize = 18.sp)
            }

            if (c.state == Call.STATE_RINGING) {
                Row(Modifier.fillMaxWidth().padding(bottom = 48.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(
                        onClick = { c.call.reject(false, null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) { Text("Decline") }
                    Button(
                        onClick = { c.call.answer(VideoProfile.STATE_AUDIO_ONLY) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text("Answer") }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 48.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FilterChip(
                            selected = muted,
                            onClick = { CallService.instance?.setMuted(!muted) },
                            label = { Text("Mute") }
                        )
                        FilterChip(
                            selected = speaker,
                            onClick = {
                                CallService.instance?.setAudioRoute(
                                    if (speaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
                                )
                            },
                            label = { Text("Speaker") }
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { c.call.disconnect() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) { Text("Hang up") }
                }
            }
        }
    }

    /** Proximity sensor is only held while this screen is visible. */
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
