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
    val features: Int,
    val location: String? = null
) {
    // CallLog.Calls.FEATURES bit flags
    val isHd get() = features and 0x4 != 0
    val isWifi get() = features and 0x8 != 0
    val isVolte get() = features and 0x40 != 0
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
        CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.FEATURES,
        CallLog.Calls.GEOCODED_LOCATION
    )
    try {
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null, "${CallLog.Calls.DATE} DESC")
            ?.use { c ->
                while (c.moveToNext() && out.size < 300) {
                    out += LogEntry(
                        c.getLong(0), c.getString(1) ?: "", c.getString(2),
                        c.getInt(3), c.getLong(4), c.getLong(5), c.getInt(6), c.getString(7)
                    )
                }
            }
    } catch (e: SecurityException) { }
    return out
}

/** Ids of contacts that are stored in a Google account (they get the small "G" badge). */
fun loadGoogleContactIds(ctx: Context): Set<Long> {
    val out = HashSet<Long>()
    try {
        ctx.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.DELETED} = 0", null, null
        )?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == "com.google") out.add(c.getLong(0))
        }
    } catch (e: Exception) { }
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

/** "Today 2:01 AM", "Yesterday 9:10 PM", "3 Nov 8:00 AM". */
fun formatLogTime(ts: Long): String {
    val now = Calendar.getInstance()
    val t = Calendar.getInstance().apply { timeInMillis = ts }
    fun same(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
    if (same(now, t)) return "Today $time"
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (same(y, t)) return "Yesterday $time"
    val pattern = if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts)) + " " + time
}

/** Right-hand status text of a call-log entry in the details screen. */
fun statusLabel(e: LogEntry): String = when {
    e.type == CallLog.Calls.MISSED_TYPE -> "Missed"
    e.type == CallLog.Calls.REJECTED_TYPE -> "Declined"
    e.duration > 0 -> formatDuration(e.duration)
    else -> "Not connected"
}

fun copyNumber(ctx: Context, number: String) {
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText("number", number))
    Toast.makeText(ctx, "Number copied", Toast.LENGTH_SHORT).show()
}

fun addToExistingIntent(ctx: Context, number: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
            type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
        })
    } catch (e: Exception) {
        Toast.makeText(ctx, "No contacts app found", Toast.LENGTH_SHORT).show()
    }
}

fun editContactIntent(ctx: Context, contactId: Long) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_EDIT, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString()))
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "No contacts app found", Toast.LENGTH_SHORT).show()
    }
}

fun blockNumber(ctx: Context, number: String) {
    try {
        val v = android.content.ContentValues().apply {
            put(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
        }
        ctx.contentResolver.insert(android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI, v)
        Toast.makeText(ctx, "Number blocked", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Set Phone as your default phone app to block numbers", Toast.LENGTH_LONG).show()
    }
}

/** Marks / unmarks a contact as a favourite (needs WRITE_CONTACTS). */
fun setStarred(ctx: Context, contactId: Long, starred: Boolean): Boolean = try {
    val v = android.content.ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
    ctx.contentResolver.update(
        android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId), v, null, null
    ) > 0
} catch (e: Exception) {
    Toast.makeText(ctx, "Contacts permission needed", Toast.LENGTH_SHORT).show(); false
}

fun deleteContacts(ctx: Context, ids: Set<Long>) {
    try {
        ids.forEach {
            ctx.contentResolver.delete(
                android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, it), null, null
            )
        }
    } catch (e: Exception) {
        Toast.makeText(ctx, "Contacts permission needed", Toast.LENGTH_SHORT).show()
    }
}

/** Saves a new contact on the phone. Returns false if it couldn't be written. */
fun saveContact(ctx: Context, name: String, phone: String, email: String, company: String, title: String): Boolean {
    val ops = ArrayList<android.content.ContentProviderOperation>()
    ops += android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build()
    fun row(mime: String) = android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
        .withValue(ContactsContract.Data.MIMETYPE, mime)
    if (name.isNotBlank()) ops += row(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.trim()).build()
    if (phone.isNotBlank()) ops += row(Phone.CONTENT_ITEM_TYPE)
        .withValue(Phone.NUMBER, phone.trim()).withValue(Phone.TYPE, Phone.TYPE_MOBILE).build()
    if (email.isNotBlank()) ops += row(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.trim())
        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME).build()
    if (company.isNotBlank() || title.isNotBlank()) ops += row(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
        .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, company.trim())
        .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, title.trim()).build()
    return try {
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops); true
    } catch (e: Exception) { false }
}

fun placeVideoCall(ctx: Context, number: String) {
    if (number.isBlank() || !isDialable(number)) return
    try {
        val extras = android.os.Bundle().apply {
            putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, android.telecom.VideoProfile.STATE_BIDIRECTIONAL)
        }
        ctx.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts("tel", number, null), extras)
    } catch (e: Exception) {
        Toast.makeText(ctx, "Video calling isn't available", Toast.LENGTH_SHORT).show()
    }
}

/** The contact's own ringtone, or null when it follows the system ringtone. */
fun readRingtone(ctx: Context, contactId: Long): Uri? = try {
    ctx.contentResolver.query(
        android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
        arrayOf(ContactsContract.Contacts.CUSTOM_RINGTONE), null, null, null
    )?.use { cur ->
        if (cur.moveToFirst()) cur.getString(0)?.takeIf { s -> s.isNotBlank() }?.let { s -> Uri.parse(s) } else null
    }
} catch (e: Exception) { null }

fun setRingtone(ctx: Context, contactId: Long, uri: Uri?): Boolean = try {
    val v = android.content.ContentValues().apply { put(ContactsContract.Contacts.CUSTOM_RINGTONE, uri?.toString()) }
    ctx.contentResolver.update(
        android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId), v, null, null
    ) > 0
} catch (e: Exception) {
    Toast.makeText(ctx, "Contacts permission needed", Toast.LENGTH_SHORT).show(); false
}

fun ringtoneTitle(ctx: Context, uri: Uri?): String =
    if (uri == null) "Follow system"
    else try { android.media.RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) ?: "Custom" } catch (e: Exception) { "Custom" }

fun durationWords(s: Long): String =
    if (s < 60) "$s sec" else "${s / 60} min ${s % 60} sec"

private val CALLING_CODES = mapOf(
    "1" to "United States / Canada", "7" to "Russia", "20" to "Egypt", "27" to "South Africa", "33" to "France",
    "34" to "Spain", "39" to "Italy", "44" to "United Kingdom", "49" to "Germany", "55" to "Brazil",
    "60" to "Malaysia", "61" to "Australia", "62" to "Indonesia", "63" to "Philippines", "65" to "Singapore",
    "66" to "Thailand", "81" to "Japan", "82" to "South Korea", "84" to "Vietnam", "86" to "China",
    "91" to "India", "92" to "Pakistan", "93" to "Afghanistan", "94" to "Sri Lanka", "95" to "Myanmar",
    "234" to "Nigeria", "880" to "Bangladesh", "966" to "Saudi Arabia", "965" to "Kuwait", "968" to "Oman",
    "971" to "United Arab Emirates", "973" to "Bahrain", "974" to "Qatar", "975" to "Bhutan", "977" to "Nepal"
)

/** Country shown under the caller's name: from the +code if there is one, else the phone's own country. */
fun regionLabel(number: String): String {
    val n = number.trim()
    if (n.startsWith("+") || n.startsWith("00")) {
        val d = digitsOf(n).let { if (n.startsWith("00")) it.drop(2) else it }
        for (len in 3 downTo 1) CALLING_CODES[d.take(len)]?.let { return it }
        return ""
    }
    return if (isDialable(n)) Locale.getDefault().displayCountry else ""
}
