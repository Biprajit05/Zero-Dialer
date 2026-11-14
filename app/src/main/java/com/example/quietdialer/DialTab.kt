package com.example.quietdialer

import android.Manifest
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val KEYS = listOf(
    listOf("1" to "", "2" to "ABC", "3" to "DEF"),
    listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
    listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
    listOf("*" to "", "0" to "+", "#" to "")
)

private val BackspaceShape = GenericShape { size, _ ->
    val tip = size.height * 0.5f
    moveTo(tip, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(tip, size.height)
    lineTo(0f, size.height / 2f)
    close()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialTab(
    number: String,
    onNumber: (String) -> Unit,
    contacts: List<Contact>,
    permsVersion: Int,
    onRequestPerms: () -> Unit
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var log by remember { mutableStateOf<List<LogEntry>?>(null) }
    val reload = remember { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    var padVisible by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<LogEntry?>(null) }

    val canRead = remember(permsVersion) { hasPerm(ctx, Manifest.permission.READ_CALL_LOG) }

    // Refresh the list whenever the system call log changes (e.g. right after a call ends).
    DisposableEffect(Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reload.intValue++ }
        }
        try {
            ctx.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, obs)
        } catch (e: SecurityException) { }
        onDispose { ctx.contentResolver.unregisterContentObserver(obs) }
    }
    LaunchedEffect(permsVersion, reload.intValue) {
        log = if (hasPerm(ctx, Manifest.permission.READ_CALL_LOG))
            withContext(Dispatchers.IO) { loadLog(ctx) } else emptyList()
    }

    val dtmf = remember {
        Dtmf(Settings.System.getInt(ctx.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1)
    }
    DisposableEffect(Unit) { onDispose { dtmf.release() } }

    val nameMap = remember(contacts) {
        HashMap<String, String>().also { m -> contacts.forEach { m.putIfAbsent(numberKey(it.number), it.name) } }
    }
    val all = log
    val shown = remember(all, query, missedOnly, nameMap) {
        (all ?: emptyList()).filter { e ->
            (!missedOnly || e.type == CallLog.Calls.MISSED_TYPE) &&
                (query.isBlank() || nameFor(e, nameMap).contains(query, true) || e.number.contains(query))
        }
    }

    BackHandler(enabled = selecting || searching) {
        if (selecting) { selecting = false; selected = emptySet() }
        else { searching = false; query = "" }
    }

    fun key(k: String) {
        if (number.length >= 32) return
        dtmf.play(k)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onNumber(number + k)
    }

    fun call() {
        if (number.isNotEmpty()) {
            placeCall(ctx, number)
            onNumber("")
        } else {
            // empty field + call button = redial the last outgoing number
            all?.firstOrNull { it.type == CallLog.Calls.OUTGOING_TYPE }?.let { onNumber(it.number) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- header ----------
        if (selecting) {
            Header(if (selected.isEmpty()) "Select items" else "${selected.size} selected") {
                TextButton(onClick = {
                    selected = if (selected.size == shown.size) emptySet() else shown.map { it.id }.toSet()
                }) { Text(if (selected.size == shown.size && shown.isNotEmpty()) "None" else "All") }
                HeaderIcon(onClick = {
                    deleteLogEntries(ctx, selected)
                    selecting = false; selected = emptySet(); reload.intValue++
                }) { Icon(Icons.Default.Delete, "Delete", tint = if (selected.isEmpty()) Pal.ChipBg else Pal.Missed) }
                HeaderIcon(onClick = { selecting = false; selected = emptySet() }) {
                    Icon(Icons.Default.Close, "Cancel", tint = Pal.Ink)
                }
            }
        } else {
            Header("Dial") {
                HeaderIcon(onClick = { selecting = true; searching = false; query = "" }) { SelectGlyph() }
                HeaderIcon(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(Icons.Default.Search, "Search", tint = Pal.Ink, modifier = Modifier.size(28.dp))
                }
                Box {
                    HeaderIcon(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "More", tint = Pal.Ink, modifier = Modifier.size(28.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (missedOnly) "Show all calls" else "Show missed calls only") },
                            onClick = { missedOnly = !missedOnly; menuOpen = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear call log") },
                            onClick = { menuOpen = false; confirmClear = true }
                        )
                    }
                }
            }
            if (searching) SearchField(query, { query = it }, { searching = false; query = "" }, "Search calls")
        }

        // ---------- list area ----------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                number.isNotEmpty() && !selecting -> Suggestions(number, contacts) {
                    placeCall(ctx, it); onNumber("")
                }
                !canRead -> PermissionPrompt("Allow call log access to see your recent calls.", onRequestPerms)
                all == null -> Text("Loading…", Modifier.align(Alignment.Center), color = Pal.Gray)
                shown.isEmpty() -> Text(
                    if (missedOnly) "No missed calls" else "No recent calls",
                    Modifier.align(Alignment.Center), color = Pal.Gray
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { e ->
                        val nm = nameFor(e, nameMap)
                        RecentRow(
                            e = e,
                            title = nm.ifBlank { if (isDialable(e.number)) prettyNumber(e.number) else "Unknown" },
                            showNumber = nm.isNotBlank(),
                            selecting = selecting,
                            checked = e.id in selected,
                            onClick = {
                                if (selecting) selected = if (e.id in selected) selected - e.id else selected + e.id
                                else placeCall(ctx, e.number)
                            },
                            onLong = {
                                if (!selecting) { selecting = true; searching = false; selected = setOf(e.id) }
                            },
                            onInfo = { detail = e }
                        )
                    }
                }
            }
        }

        // ---------- keypad ----------
        if (!selecting && !searching) {
            HorizontalDivider(color = Pal.Divider)
            AnimatedVisibility(visible = padVisible) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    KEYS.forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { (d, letters) ->
                                Box(
                                    Modifier.weight(1f).height(66.dp).clip(RoundedCornerShape(20.dp))
                                        .combinedClickable(
                                            onClick = { key(d) },
                                            onLongClick = if (d == "0") ({ key("+") }) else null
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            d, fontSize = if (d == "*" || d == "#") 34.sp else 32.sp,
                                            color = Pal.Ink
                                        )
                                        if (d == "1") VoicemailGlyph()
                                        else if (letters.isNotEmpty()) Text(
                                            letters, fontSize = 12.sp, color = Pal.Letters,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    HeaderIcon(onClick = { padVisible = !padVisible }) { DotsGlyph() }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(26.dp)).background(Pal.Green)
                            .clickable { call() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Call, "Call", tint = Color.White, modifier = Modifier.size(32.dp)) }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(width = 56.dp, height = 40.dp)
                            .alpha(if (number.isNotEmpty()) 1f else 0.45f)
                            .clip(BackspaceShape).background(Pal.BackspaceBg)
                            .combinedClickable(
                                onClick = { if (number.isNotEmpty()) onNumber(number.dropLast(1)) },
                                onLongClick = { onNumber("") }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Close, "Delete digit", tint = Pal.BackspaceX,
                            modifier = Modifier.size(20.dp).offset(x = 5.dp)
                        )
                    }
                }
            }
        }
    }

    detail?.let { e ->
        val nm = nameFor(e, nameMap)
        ActionDialog(
            name = nm, number = e.number,
            history = (all ?: emptyList()).filter { numberKey(it.number) == numberKey(e.number) },
            onDismiss = { detail = null }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Color.White,
            title = { Text("Clear call log?") },
            text = { Text("All recent calls will be deleted.") },
            confirmButton = {
                TextButton(onClick = { clearCallLog(ctx); confirmClear = false; reload.intValue++ }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(
    e: LogEntry,
    title: String,
    showNumber: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onInfo: () -> Unit
) {
    val missed = e.type == CallLog.Calls.MISSED_TYPE
    Column {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLong)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
                if (selecting) SelectDot(checked)
                else if (e.type == CallLog.Calls.OUTGOING_TYPE) OutgoingGlyph()
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (missed) Pal.Missed else Pal.Ink
                )
                if (e.isHd || e.isWifi || showNumber) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (e.isHd) { TagChip("HD"); Spacer(Modifier.width(6.dp)) }
                        if (e.isWifi) { TagChip("Wi-Fi"); Spacer(Modifier.width(6.dp)) }
                        if (showNumber) Text(
                            prettyNumber(e.number), fontSize = 14.sp, color = Pal.Gray,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(formatWhen(e.date), fontSize = 15.sp, color = Pal.Gray, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Pal.Soft).clickable(onClick = onInfo),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.KeyboardArrowRight, "Details", tint = Pal.Ink) }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp), color = Pal.Divider)
    }
}

/** Shown instead of recents while a number is being typed: matching contacts (smart dial). */
@Composable
private fun Suggestions(number: String, contacts: List<Contact>, onCall: (String) -> Unit) {
    val ctx = LocalContext.current
    val matches = remember(number, contacts) { contacts.filter { matchesDial(it, number) }.take(40) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                number, fontSize = if (number.length > 14) 24.sp else 32.sp, color = Pal.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { addContactIntent(ctx, number) }) { Text("Add contact") }
        }
        HorizontalDivider(color = Pal.Divider)
        LazyColumn(Modifier.fillMaxSize()) {
            items(matches, key = { "${it.id}:${numberKey(it.number)}" }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onCall(c.number) }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(c.name, 44.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(c.name, fontSize = 18.sp, color = Pal.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(prettyNumber(c.number), fontSize = 14.sp, color = Pal.Gray)
                    }
                }
            }
        }
    }
}
