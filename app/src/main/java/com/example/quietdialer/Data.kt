package com.example.quietdialer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class LogEntry(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val duration: Long,
    val features: Int
) {
    // CallLog.Calls.FEATURES bit flags
    val isHd get() = features and 0x4 != 0
    val isWifi get() = features and 0x8 != 0
}

data class Contact(val id: Long, val name: String, val number: String, val starred: Boolean)

fun hasPerm(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

fun digitsOf(s: String) = s.filter { it.isDigit() }
fun numberKey(s: String) = digitsOf(s).takeLast(10)
fun isDialable(s: String) = digitsOf(s).isNotEmpty()

fun prettyNumber(n: String): String =
    if (!isDialable(n)) n
    else try { PhoneNumberUtils.formatNumber(n, Locale.getDefault().country) ?: n } catch (e: Exception) { n }

fun initialOf(name: String): Char =
    name.trim().firstOrNull()?.takeIf { it.isLetter() }?.uppercaseChar() ?: '#'

fun loadLog(ctx: Context): List<LogEntry> {
    val out = mutableListOf<LogEntry>()
    val cols = arrayOf(
        CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
        CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.FEATURES
    )
    try {
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null, "${CallLog.Calls.DATE} DESC")
            ?.use { c ->
                while (c.moveToNext() && out.size < 300) {
                    out += LogEntry(
                        c.getLong(0), c.getString(1) ?: "", c.getString(2),
                        c.getInt(3), c.getLong(4), c.getLong(5), c.getInt(6)
                    )
                }
            }
    } catch (e: SecurityException) { }
    return out
}

fun loadContacts(ctx: Context): List<Contact> {
    val out = mutableListOf<Contact>()
    val seen = HashSet<String>()
    val cols = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, ContactsContract.Contacts.STARRED)
    try {
        ctx.contentResolver.query(Phone.CONTENT_URI, cols, null, null, "${Phone.DISPLAY_NAME} COLLATE NOCASE ASC")
            ?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val num = c.getString(2) ?: continue
                    if (!seen.add("$id:${numberKey(num)}")) continue
                    out += Contact(id, name, num, c.getInt(3) == 1)
                }
            }
    } catch (e: SecurityException) { }
    return out
}

fun deleteLogEntries(ctx: Context, ids: Set<Long>) {
    if (ids.isEmpty()) return
    try {
        ctx.contentResolver.delete(
            CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${ids.joinToString(",")})", null
        )
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "Call log permission needed", Toast.LENGTH_SHORT).show()
    }
}

fun clearCallLog(ctx: Context) {
    try {
        ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "Call log permission needed", Toast.LENGTH_SHORT).show()
    }
}

fun placeCall(ctx: Context, number: String) {
    if (number.isBlank() || !isDialable(number)) return
    try {
        ctx.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts("tel", number, null), android.os.Bundle())
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "Phone permission needed", Toast.LENGTH_SHORT).show()
    }
}

fun sendSms(ctx: Context, number: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No messaging app found", Toast.LENGTH_SHORT).show()
    }
}

fun addContactIntent(ctx: Context, number: String) {
    try {
        val i = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
        }
        ctx.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(ctx, "No contacts app found", Toast.LENGTH_SHORT).show()
    }
}

fun typeLabel(type: Int) = when (type) {
    CallLog.Calls.INCOMING_TYPE -> "Incoming"
    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
    CallLog.Calls.MISSED_TYPE -> "Missed"
    CallLog.Calls.REJECTED_TYPE -> "Declined"
    6 -> "Blocked"
    else -> "Call"
}

fun formatDuration(s: Long): String =
    if (s <= 0) "0s" else if (s < 60) "${s}s" else "%d:%02d".format(s / 60, s % 60)

/** Today -> time, yesterday -> "Yesterday", this year -> "14 Nov", older -> "14 Nov 2025". */
fun formatWhen(ts: Long): String {
    val now = Calendar.getInstance()
    val t = Calendar.getInstance().apply { timeInMillis = ts }
    fun same(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(now, t)) return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (same(y, t)) return "Yesterday"
    val pattern = if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
}

fun formatFull(ts: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))

/** Name to show for a log entry: log's cached name, else address-book match, else "". */
fun nameFor(e: LogEntry, map: Map<String, String>): String =
    e.name?.takeIf { it.isNotBlank() } ?: map[numberKey(e.number)] ?: ""

private fun t9(s: String): String = buildString {
    for (ch in s.lowercase()) {
        when (ch) {
            in 'a'..'c' -> append('2')
            in 'd'..'f' -> append('3')
            in 'g'..'i' -> append('4')
            in 'j'..'l' -> append('5')
            in 'm'..'o' -> append('6')
            in 'p'..'s' -> append('7')
            in 't'..'v' -> append('8')
            in 'w'..'z' -> append('9')
            else -> {}
        }
    }
}

/** Smart dial: match by number digits, or by name using the letters on the keypad. */
fun matchesDial(c: Contact, typed: String): Boolean {
    if (typed.any { it == '*' || it == '#' }) return false
    val plain = typed.filter { it.isDigit() || it == '+' }
    if (plain.isEmpty()) return false
    if (c.number.filter { it.isDigit() || it == '+' }.contains(plain)) return true
    if (typed.all { it.isDigit() }) {
        if (t9(c.name).startsWith(typed)) return true
        return c.name.split(' ').any { t9(it).startsWith(typed) }
    }
    return false
}
