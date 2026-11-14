package com.example.quietdialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Ringtone
import android.media.RingtoneManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat

class CallService : InCallService() {

    companion object {
        @Volatile var instance: CallService? = null
        const val CH_INCOMING = "incoming_calls"
        const val CH_ONGOING = "ongoing_calls"
        const val NOTIF_ID = 1
        const val EXTRA_ANSWER = "answer"
    }

    private var ringtone: Ringtone? = null

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = onCallsChanged()
        override fun onDetailsChanged(call: Call, details: Call.Details) = onCallsChanged()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) } // we play the ringtone ourselves
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        stopRinging()
        instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(cb)
        CallManager.add(call, this)
        onCallsChanged()
        // Always try to open our call screen right away. For incoming calls the full-screen
        // notification is the backup if the system blocks this (e.g. phone is locked/idle).
        try {
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

    /** Re-posts the call notification. Kept public so receivers/other code can call it. */
    fun refreshNotification() = onCallsChanged()

    private fun onCallsChanged() {
        CallManager.publish()
        val primary = CallManager.primary()
        if (primary == null) {
            stopRinging()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return
        }
        val ringing = primary.state == Call.STATE_RINGING
        if (ringing) startRinging() else stopRinging()
        // A notification problem must never take the whole call service down: if this service
        // crashes, Android hands the call to the phone's built-in call screen instead of ours.
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(primary, ringing),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } catch (e: Exception) { }
    }

    private fun buildNotification(call: Call, ringing: Boolean): android.app.Notification {
        val person = Person.Builder().setName(CallManager.numberOf(call)).setImportant(true).build()
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, InCallActivity::class.java),
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
            .setOnlyAlertOnce(true)
            .setContentIntent(open)

        if (ringing) {
            val answer = PendingIntent.getActivity(
                this, 12,
                Intent(this, InCallActivity::class.java).putExtra(EXTRA_ANSWER, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.setStyle(NotificationCompat.CallStyle.forIncomingCall(person, hangup, answer))
                .setFullScreenIntent(open, true)
        } else {
            b.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangup))
            // Extra buttons shown next to Hang up (CallStyle allows up to 3 actions in total).
            val muted = CallManager.isMuted()
            val speaker = CallManager.isSpeaker()
            b.addAction(
                if (muted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off,
                if (muted) "Unmute" else "Mute",
                actionIntent(13, CallActionReceiver.MUTE)
            )
            b.addAction(
                android.R.drawable.ic_btn_speak_now,
                if (speaker) "Speaker off" else "Speaker",
                actionIntent(14, CallActionReceiver.SPEAKER)
            )
            val t = call.details.connectTimeMillis
            if (t > 0) b.setWhen(t).setUsesChronometer(true).setShowWhen(true)
        }
        // If the user manages to dismiss it anyway, put it straight back.
        b.setDeleteIntent(actionIntent(15, CallActionReceiver.REPOST))
        val n = b.build()
        n.flags = n.flags or android.app.Notification.FLAG_NO_CLEAR or android.app.Notification.FLAG_ONGOING_EVENT
        return n
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
