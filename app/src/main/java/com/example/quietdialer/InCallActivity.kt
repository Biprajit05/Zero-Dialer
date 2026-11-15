package com.example.quietdialer

import android.Manifest
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*

class InCallActivity : ComponentActivity() {

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startOrAskAccessibility()
            else Toast.makeText(this, "Microphone permission is needed to record", Toast.LENGTH_SHORT).show()
        }

    private fun startRecording() {
        val who = CallManager.primary()?.let { CallManager.numberOf(it) } ?: "call"
        if (CallRecorder.start(this, who)) CallService.instance?.refreshNotification()
        else Toast.makeText(this, "Couldn't start recording", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRecording() {
        when {
            CallRecorder.active.value -> { CallRecorder.stop(this); CallService.instance?.refreshNotification() }
            CallRecorder.hasPermission(this) -> startOrAskAccessibility()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private var skipAccessibilityPrompt = false

    private fun startOrAskAccessibility() {
        if (CallRecorder.a11yEnabled(this) || skipAccessibilityPrompt) { startRecording(); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("Turn on call recording")
            .setMessage(
                "Android silences the microphone during calls for most apps, so recordings come out empty. " +
                    "To fix that, open Accessibility settings, find Quiet Dialer and switch it on. " +
                    "It is only used to keep the microphone working; it does not read your screen."
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Record anyway") { _, _ -> skipAccessibilityPrompt = true; startRecording() }
            .show()
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var resumed = false
    private var wantProximity = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        handleAnswer(intent)

        setContent {
            MaterialTheme {
                val snaps by CallManager.snaps.collectAsState()
                val audio by CallManager.audio.collectAsState()
                val primary = snaps.firstOrNull { it.state == Call.STATE_RINGING }
                    ?: snaps.firstOrNull {
                        it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING ||
                            it.state == Call.STATE_CONNECTING
                    }
                    ?: snaps.firstOrNull()

                if (primary == null) {
                    LaunchedEffect(Unit) { finish() }
                    return@MaterialTheme
                }
                val other = snaps.firstOrNull { it.call != primary.call && it.state == Call.STATE_HOLDING }

                val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
                val inCallNow = primary.state == Call.STATE_ACTIVE ||
                    primary.state == Call.STATE_DIALING || primary.state == Call.STATE_CONNECTING
                val useProximity = inCallNow && route == CallAudioState.ROUTE_EARPIECE

                LaunchedEffect(useProximity) {
                    wantProximity = useProximity
                    applyProximity()
                }

                val speaker = route == CallAudioState.ROUTE_SPEAKER
                val recording by CallRecorder.active.collectAsState()
                CallScreen(
                    c = primary,
                    other = other,
                    muted = audio?.isMuted == true,
                    speaker = speaker,
                    recording = recording,
                    onRecord = { toggleRecording() },
                    onAnswer = { primary.call.answer(VideoProfile.STATE_AUDIO_ONLY) },
                    onDecline = { primary.call.reject(false, null) },
                    onSmsReply = { primary.call.reject(true, it) },
                    onMute = { CallManager.toggleMute() },
                    onSpeaker = { CallManager.toggleSpeaker() },
                    onHold = {
                        if (primary.state == Call.STATE_HOLDING) primary.call.unhold() else primary.call.hold()
                    },
                    onAddCall = {
                        startActivity(
                            Intent(this@InCallActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    },
                    onVideo = {
                        val vc = primary.call.videoCall
                        if (vc != null) vc.sendSessionModifyRequest(VideoProfile(VideoProfile.STATE_BIDIRECTIONAL))
                        else Toast.makeText(this@InCallActivity, "Video call isn't available right now", Toast.LENGTH_SHORT).show()
                    },
                    onMessages = { sendSms(this@InCallActivity, primary.number) },
                    onEnd = { primary.call.disconnect() },
                    onDtmf = { ch, down -> if (down) primary.call.playDtmfTone(ch) else primary.call.stopDtmfTone() },
                    onSwap = { other?.call?.unhold() }
                )
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
