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
            if (ok) startRecording(useShizuku = false)
            else Toast.makeText(this, "Microphone permission is needed to record", Toast.LENGTH_SHORT).show()
        }

    private fun startRecording(useShizuku: Boolean) {
        val who = CallManager.primary()?.let { CallManager.numberOf(it) } ?: "call"
        if (CallRecorder.start(this, who, useShizuku)) CallService.instance?.refreshNotification()
        else Toast.makeText(this, "Couldn't start recording", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRecording() {
        if (CallRecorder.active.value) {
            CallRecorder.stop(this)
            CallService.instance?.refreshNotification()
            return
        }
        when (ShizukuRecorder.status(this)) {
            ShizukuRecorder.Status.READY -> startRecording(useShizuku = true)
            ShizukuRecorder.Status.NO_PERMISSION -> {
                Toast.makeText(this, "Allow Quiet Dialer in the Shizuku popup", Toast.LENGTH_SHORT).show()
                ShizukuRecorder.requestPermission { granted ->
                    if (granted) startRecording(useShizuku = true)
                    else Toast.makeText(this, "Shizuku permission was not given", Toast.LENGTH_SHORT).show()
                }
            }
            else -> askShizukuSetup()
        }
    }

    private fun startMicOnly() {
        if (CallRecorder.hasPermission(this)) startRecording(useShizuku = false)
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun askShizukuSetup() {
        val st = ShizukuRecorder.status(this)
        val installed = st != ShizukuRecorder.Status.NOT_INSTALLED
        val msg = when (st) {
            ShizukuRecorder.Status.NOT_INSTALLED ->
                "To record both sides of a call, Quiet Dialer needs Shizuku. Install the Shizuku app, start it, then come back and tap Record."
            ShizukuRecorder.Status.OLD_VERSION ->
                "Your Shizuku app is too old. Update it, start it, then tap Record again."
            else ->
                "Shizuku is installed but not running. Open it and tap Start, then tap Record again."
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Call recording needs Shizuku")
            .setMessage(msg)
            .setPositiveButton(if (installed) "Open Shizuku" else "Get Shizuku") { _, _ ->
                val launch = if (installed) packageManager.getLaunchIntentForPackage(ShizukuRecorder.SHIZUKU_PKG) else null
                try {
                    startActivity(
                        launch ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))
                    )
                } catch (_: Exception) { }
            }
            .setNegativeButton("Mic only") { _, _ -> startMicOnly() }
            .setNeutralButton("Cancel", null)
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
