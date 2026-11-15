package com.example.quietdialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.VideoProfile

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val HANGUP = "com.example.quietdialer.HANGUP"
        /** Answer straight from the notification, without opening the call screen. */
        const val ANSWER = "com.example.quietdialer.ANSWER"
        const val MUTE = "com.example.quietdialer.MUTE"
        const val SPEAKER = "com.example.quietdialer.SPEAKER"
        /** Fired if the notification is dismissed anyway; we simply post it again. */
        const val REPOST = "com.example.quietdialer.REPOST"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            HANGUP -> {
                val call = CallManager.primary() ?: return
                if (call.state == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
            }
            ANSWER -> CallManager.primary()?.takeIf { it.state == Call.STATE_RINGING }
                ?.answer(VideoProfile.STATE_AUDIO_ONLY)
            MUTE -> CallManager.toggleMute()
            SPEAKER -> CallManager.toggleSpeaker()
            REPOST -> CallService.instance?.refreshNotification()
        }
    }
}
