package com.example.quietdialer

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat

class CallService : InCallService() {

    companion object {
        @Volatile var instance: CallService? = null
        const val CH_INCOMING = "incoming_calls_v3"
        const val CH_ONGOING = "ongoing_calls_v3"
        const val NOTIF_ID = 1
        const val EXTRA_ANSWER = "answer"
    }

    private var ringtone: Ringtone? = null

    private var autoHandled: Call? = null

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = onCallsChanged()
        override fun onDetailsChanged(call: Call, details: Call.Details) = onCallsChanged()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        // Old channels keep their cached settings, so they are replaced by "_v2" ones that are
        // explicitly visible on the lock screen.
        for (old in listOf("incoming_calls", "ongoing_calls", "incoming_calls_v2", "ongoing_calls_v2"))
            nm.deleteNotificationChannel(old)
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // we play the ringtone ourselves
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            // DEFAULT (not LOW) so phones don't treat it as a "silent" notification and hide it from
            // the lock screen; it still makes no sound or vibration.
            NotificationChannel(CH_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    override fun onDestroy() {
        stopRinging()
        CallRecorder.stop(this)
        instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        if (ShizukuRecorder.status(this) == ShizukuRecorder.Status.READY) ShizukuRecorder.ensureBound(this) // warm up the recorder
        call.registerCallback(cb)
        CallManager.add(call, this)
        onCallsChanged()
        // Phone in use and unlocked: an incoming call stays in the notification (heads-up with
        // Answer / Decline) and never takes over the screen. Otherwise open our call screen right
        // away; for incoming calls the full-screen notification is the backup if the system blocks it.
        val quietIncoming = call.state == Call.STATE_RINGING && deviceUnlocked()
        if (!quietIncoming) try {
            startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(cb)
        CallManager.remove(call)
        onCallsChanged()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        CallManager.setAudio(audioState)
        onCallsChanged() // keep Mute / Speaker labels in the notification in sync
    }

    /** True while the screen is on and the phone is unlocked (i.e. the person is using it). */
    private fun deviceUnlocked(): Boolean = try {
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    } catch (e: Exception) { false }

    /** Re-posts the call notification. Kept public so receivers/other code can call it. */
    fun refreshNotification() = onCallsChanged()

    private fun onCallsChanged() {
        CallManager.publish()
        val primary = CallManager.primary()
        if (primary == null) {
            stopRinging()
            CallRecorder.stop(this)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return
        }
        // "Record all calls" setting: start as soon as the call is answered (once per call).
        if (primary.state == Call.STATE_ACTIVE && primary !== autoHandled) {
            autoHandled = primary
            if (RecordSettings.isAuto(this) && !CallRecorder.active.value &&
                ShizukuRecorder.status(this) == ShizukuRecorder.Status.READY
            ) CallRecorder.start(this, CallManager.numberOf(primary), true)
        }
        val ringing = primary.state == Call.STATE_RINGING
        if (ringing) startRinging() else stopRinging()
        // A notification problem must never take the whole call service down: if this service
        // crashes, Android hands the call to the phone's built-in call screen instead of ours.
        val notif = buildNotification(primary, ringing)
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        // While recording, the service also needs the microphone type or Android silences the mic
        // once the call screen is no longer visible.
        // (Not needed when Shizuku records: that runs in another process.)
        val type = if (CallRecorder.active.value && !CallRecorder.viaShizuku) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else base
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        } catch (e: Exception) {
            try { ServiceCompat.startForeground(this, NOTIF_ID, notif, base) } catch (e2: Exception) { }
        }
    }

    private fun buildNotification(call: Call, ringing: Boolean): android.app.Notification {
        val person = Person.Builder().setName(CallManager.numberOf(call)).setImportant(true).build()
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hangup = PendingIntent.getBroadcast(
            this, 11,
            Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, if (ringing) CH_INCOMING else CH_ONGOING)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)            // cannot be swiped away
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // show full content on the lock screen
            .setOnlyAlertOnce(true)
            .setContentIntent(open)

        if (ringing) {
            val unlocked = deviceUnlocked()
            // Unlocked: Answer just answers and the call carries on in the notification.
            // Locked: Answer opens the call screen over the lock screen.
            val answer = if (unlocked) actionIntent(12, CallActionReceiver.ANSWER) else PendingIntent.getActivity(
                this, 12,
                Intent(this, InCallActivity::class.java).putExtra(EXTRA_ANSWER, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val v = RemoteViews(packageName, R.layout.notification_incoming)
            v.setTextViewText(R.id.inc_name, CallManager.numberOf(call))
            v.setTextViewText(R.id.inc_sub, regionLabel(CallManager.rawNumber(call)).ifBlank { "mobile" })
            v.setOnClickPendingIntent(R.id.inc_decline, hangup)
            v.setOnClickPendingIntent(R.id.inc_accept, answer)
            // Plain custom view on the system surface; no big view, so no expand arrow.
            b.setCustomContentView(v)
                .setCustomHeadsUpContentView(v)
                .setShowWhen(false)
            if (!unlocked) b.setFullScreenIntent(open, true)
        } else {
            // Plain custom view on the system notification surface: name + timer on the left,
            // Mute / Speaker / Hang up on the right. No big view, so no expand arrow.
            val view = buildOngoingView(call, hangup)
            b.setCustomContentView(view).setShowWhen(false)
            val t = call.details.connectTimeMillis
            if (t > 0) b.setWhen(t)
        }
        // If the user manages to dismiss it anyway, put it straight back.
        b.setDeleteIntent(actionIntent(15, CallActionReceiver.REPOST))
        val n = b.build()
        n.flags = n.flags or android.app.Notification.FLAG_NO_CLEAR or android.app.Notification.FLAG_ONGOING_EVENT
        return n
    }

    private fun buildOngoingView(call: Call, hangup: PendingIntent): RemoteViews {
        val v = RemoteViews(packageName, R.layout.notification_call)
        v.setTextViewText(R.id.notif_name, CallManager.numberOf(call))

        val connected = call.details.connectTimeMillis
        if (connected > 0 && call.state == Call.STATE_ACTIVE) {
            val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - connected)
            v.setChronometer(R.id.notif_timer, base, null, true)
            v.setViewVisibility(R.id.notif_timer, View.VISIBLE)
            v.setViewVisibility(R.id.notif_status, View.GONE)
        } else {
            v.setViewVisibility(R.id.notif_timer, View.GONE)
            v.setViewVisibility(R.id.notif_status, View.VISIBLE)
            v.setTextViewText(R.id.notif_status, if (call.state == Call.STATE_HOLDING) "On hold" else "Calling…")
        }

        // Off = soft grey circle + icon in the system text colour (white in dark mode, black in
        // light mode). On = blue circle + white icon.
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val offIcon = if (night) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val muted = CallManager.isMuted()
        val speaker = CallManager.isSpeaker()
        v.setInt(R.id.notif_mute, "setBackgroundResource",
            if (muted) R.drawable.notif_btn_white else R.drawable.notif_btn_gray)
        v.setInt(R.id.notif_mute, "setColorFilter", if (muted) android.graphics.Color.WHITE else offIcon)
        v.setInt(R.id.notif_speaker, "setBackgroundResource",
            if (speaker) R.drawable.notif_btn_white else R.drawable.notif_btn_gray)
        v.setInt(R.id.notif_speaker, "setColorFilter", if (speaker) android.graphics.Color.WHITE else offIcon)

        v.setOnClickPendingIntent(R.id.notif_mute, actionIntent(13, CallActionReceiver.MUTE))
        v.setOnClickPendingIntent(R.id.notif_speaker, actionIntent(14, CallActionReceiver.SPEAKER))
        v.setOnClickPendingIntent(R.id.notif_end, hangup)
        return v
    }

    private fun actionIntent(requestCode: Int, action: String) = PendingIntent.getBroadcast(
        this, requestCode,
        Intent(this, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun startRinging() {
        if (ringtone?.isPlaying == true) return
        ringtone = RingtoneManager.getRingtone(
            this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        )?.also { it.play() }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
    }
}
