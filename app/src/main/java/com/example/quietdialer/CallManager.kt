package com.example.quietdialer

import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CallSnap(
    val call: Call,
    val state: Int,
    val number: String,      // contact name if known, else the number
    val connectTime: Long,
    val rawNumber: String
)

/** Holds the live calls so the service, notification and UI all see the same thing. */
object CallManager {
    private val live = mutableListOf<Call>()
    private val _snaps = MutableStateFlow<List<CallSnap>>(emptyList())
    val snaps: StateFlow<List<CallSnap>> = _snaps
    private val _audio = MutableStateFlow<CallAudioState?>(null)
    val audio: StateFlow<CallAudioState?> = _audio
    private val nameCache = HashMap<String, String?>()

    fun add(c: Call) { if (!live.contains(c)) live.add(c); publish() }
    fun remove(c: Call) { live.remove(c); publish() }
    fun setAudio(a: CallAudioState) { _audio.value = a }

    fun primary(): Call? =
        live.firstOrNull { it.state == Call.STATE_RINGING }
            ?: live.firstOrNull { it.state != Call.STATE_DISCONNECTED }
            ?: live.firstOrNull()

    fun publish() {
        _snaps.value = live.map {
            CallSnap(it, it.state, numberOf(it), it.details.connectTimeMillis, rawNumberOf(it))
        }
    }

    fun rawNumberOf(c: Call): String = c.details.handle?.schemeSpecificPart ?: ""

    fun numberOf(c: Call): String {
        val name = c.details.callerDisplayName
        if (!name.isNullOrBlank()) return name
        val raw = rawNumberOf(c)
        if (raw.isBlank()) return "Unknown"
        return contactName(raw) ?: raw
    }

    private fun contactName(number: String): String? {
        if (nameCache.containsKey(number)) return nameCache[number]
        val ctx = CallService.instance ?: return null
        var name: String? = null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) name = it.getString(0) }
            nameCache[number] = name
        } catch (e: Exception) {
            // permission not granted yet: don't cache, try again next time
        }
        return name
    }
}
