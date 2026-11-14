package com.example.quietdialer

import android.app.Notification
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
        private var activeSince = 0L

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
        CallManager.add(call)
        onCallsChanged()
        // Incoming calls open the UI via the full-screen notification; outgoing open it directly.
        if (call.state != Call.STATE_RINGING) {
            startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(cb)
        CallManager.remove(call)
        onCallsChanged()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        CallManager.setAudio(audioState)
    }

    /** Called when the user swipes the notification away: show it again. */
    fun refreshNotification() = onCallsChanged()

    private fun onCallsChanged() {
        CallManager.publish()
        val primary = CallManager.primary()
        if (primary == null) {
                        activeSince = 0L
            stopRinging()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return
        }
        val ringing = primary.state == Call.STATE_RINGING
        if (ringing) startRinging() else stopRinging()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(primary, ringing),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )
    }

    private fun buildNotification(call: Call, ringing: Boolean): Notification {
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
        val repost = PendingIntent.getBroadcast(
            this, 13,
            Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.REPOST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, if (ringing) CH_INCOMING else CH_ONGOING)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)            // cannot be swiped away
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(repost)     // if it is dismissed anyway, show it again
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
                .setContentTitle(CallManager.numberOf(call))
                .setContentText("Ongoing call")
            if (call.state == Call.STATE_ACTIVE) {
                if (activeSince == 0L) {
                    activeSince = call.details.connectTimeMillis.takeIf { it > 0 }
                        ?: System.currentTimeMillis()
                }
                b.setWhen(activeSince).setUsesChronometer(true).setShowWhen(true)
            }
        }
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        return n
    }

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
