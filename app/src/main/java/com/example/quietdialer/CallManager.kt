package com.example.quietdialer

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CallSnap(
    val call: Call,
    val state: Int,
    val number: String,
    val name: String?,
    val connectTime: Long,
    val canHold: Boolean
) {
    val title: String
        get() = name?.takeIf { it.isNotBlank() } ?: if (number.isNotBlank()) prettyNumber(number) else "Unknown"
    val subtitle: String?
        get() = if (!name.isNullOrBlank() && number.isNotBlank()) prettyNumber(number) else null
}

/** Holds the live calls so the service, notification and UI all see the same thing. */
object CallManager {
    private val live = mutableListOf<Call>()
    private val names = HashMap<Call, String?>()
    private val _snaps = MutableStateFlow<List<CallSnap>>(emptyList())
    val snaps: StateFlow<List<CallSnap>> = _snaps
    private val _audio = MutableStateFlow<CallAudioState?>(null)
    val audio: StateFlow<CallAudioState?> = _audio

    fun add(c: Call, ctx: Context) {
        if (!live.contains(c)) {
            live.add(c)
            names[c] = lookupName(ctx, rawNumber(c))
        }
        publish()
    }

    fun remove(c: Call) { live.remove(c); names.remove(c); publish() }
    fun setAudio(a: CallAudioState) { _audio.value = a }

    fun isMuted(): Boolean = _audio.value?.isMuted == true
    fun isSpeaker(): Boolean = _audio.value?.route == CallAudioState.ROUTE_SPEAKER

    fun toggleMute() { CallService.instance?.setMuted(!isMuted()) }

    /** Speaker on <-> back to Bluetooth (if connected) or earpiece/wired. */
    fun toggleSpeaker() {
        val svc = CallService.instance ?: return
        val mask = _audio.value?.supportedRouteMask ?: 0
        val back = if ((mask and CallAudioState.ROUTE_BLUETOOTH) != 0)
            CallAudioState.ROUTE_BLUETOOTH else CallAudioState.ROUTE_WIRED_OR_EARPIECE
        svc.setAudioRoute(if (isSpeaker()) back else CallAudioState.ROUTE_SPEAKER)
    }

    fun primary(): Call? =
        live.firstOrNull { it.state == Call.STATE_RINGING }
            ?: live.firstOrNull {
                it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING
            }
            ?: live.firstOrNull { it.state != Call.STATE_DISCONNECTED }
            ?: live.firstOrNull()

    fun publish() {
        _snaps.value = live.map {
            val caps = it.details?.callCapabilities ?: 0
            CallSnap(
                it, it.state, rawNumber(it), nameOf(it),
                it.details?.connectTimeMillis ?: 0L,
                (caps and Call.Details.CAPABILITY_HOLD) != 0
            )
        }
    }

    fun rawNumber(c: Call): String = c.details?.handle?.schemeSpecificPart ?: ""

    fun nameOf(c: Call): String? =
        names[c]?.takeIf { it.isNotBlank() } ?: c.details?.callerDisplayName?.takeIf { it.isNotBlank() }

    /** Name if known, otherwise the number. Used for the notification. */
    fun numberOf(c: Call): String =
        nameOf(c) ?: rawNumber(c).ifBlank { "Unknown" }

    private fun lookupName(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }
}
