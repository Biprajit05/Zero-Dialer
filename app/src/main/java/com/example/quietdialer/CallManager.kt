package com.example.quietdialer

import android.telecom.Call
import android.telecom.CallAudioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CallSnap(val call: Call, val state: Int, val number: String, val connectTime: Long)

/** Holds the live calls so the service, notification and UI all see the same thing. */
object CallManager {
    private val live = mutableListOf<Call>()
    private val _snaps = MutableStateFlow<List<CallSnap>>(emptyList())
    val snaps: StateFlow<List<CallSnap>> = _snaps
    private val _audio = MutableStateFlow<CallAudioState?>(null)
    val audio: StateFlow<CallAudioState?> = _audio

    fun add(c: Call) { if (!live.contains(c)) live.add(c); publish() }
    fun remove(c: Call) { live.remove(c); publish() }
    fun setAudio(a: CallAudioState) { _audio.value = a }

    fun primary(): Call? =
        live.firstOrNull { it.state == Call.STATE_RINGING }
            ?: live.firstOrNull { it.state != Call.STATE_DISCONNECTED }
            ?: live.firstOrNull()

    fun publish() {
        _snaps.value = live.map {
            CallSnap(it, it.state, numberOf(it), it.details.connectTimeMillis)
        }
    }

    fun numberOf(c: Call): String {
        val name = c.details.callerDisplayName
        if (!name.isNullOrBlank()) return name
        return c.details.handle?.schemeSpecificPart ?: "Unknown"
    }
}
