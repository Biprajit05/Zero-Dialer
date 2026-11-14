package com.example.quietdialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.Call

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val HANGUP = "com.example.quietdialer.HANGUP"
        const val REPOST = "com.example.quietdialer.REPOST"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == REPOST) {
            // Notification was swiped away: show it again
            CallService.instance?.refreshNotification()
            return
        }
        val call = CallManager.primary() ?: return
        if (intent.action == HANGUP) {
            if (call.state == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
        }
    }
}
