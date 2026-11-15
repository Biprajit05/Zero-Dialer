package com.example.quietdialer

import android.Manifest
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialTab(
    number: String,
    onNumber: (String) -> Unit,
    contacts: List<Contact>,
    permsVersion: Int,
    onRequestPerms: () -> Unit,
    onNewContact: (String) -> Unit,
    onOpenInfo: (String) -> Unit,
    onOpenCallBg: () -> Unit,
    onBarHidden: (Boolean) -> Unit
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
    var confirmDelete by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 30 }
    }

    val canRead = remember(permsVersion) { hasPerm(ctx, Manifest.permission.READ_CALL_LOG) }

    LaunchedEffect(selecting) { onBarHidden(selecting) }
    DisposableEffect(Unit) { onDispose { onBarHidden(false) } }

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

    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        // ---------- header ----------
        if (selecting) {
            SelectionHeader(
                count = selected.size, total = shown.size,
                onToggleAll = {
                    selected = if (selected.size == shown.size) emptySet() else shown.map { it.id }.toSet()
                },
                onCancel = { selecting = false; selected = emptySet() }
            )
        } else {
            CollapsingHeader("Dial", collapsed) {
                HeaderIcon(onClick = { selecting = true; searching = false; query = "" }) { SelectIcon() }
                HeaderIcon(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(Icons.Outlined.Search, "Search", tint = Pal.Ink, modifier = Modifier.size(24.dp))
                }
                Box {
                    HeaderIcon(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "More", tint = Pal.Ink, modifier = Modifier.size(24.dp))
                    }
                    DropdownMenu(
                        expanded = menuOpen, onDismissRequest = { menuOpen = false },
                        modifier = Modifier.background(Pal.Sheet)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Call background", color = Pal.Ink) },
                            onClick = { menuOpen = false; onOpenCallBg() }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear call log", color = Pal.Ink) },
                            onClick = { menuOpen = false; confirmClear = true }
                        )
                    }
                }
            }
            AnimatedVisibility(visible = searching) {
                SearchPill(query, { query = it }, "Search calls", autoFocus = true, onClose = { searching = false; query = "" })
            }
            // The All / Missed switch slides in once the list is scrolled (and stays while Missed is on).
            AnimatedVisibility(
                visible = (collapsed || missedOnly) && !searching,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
            ) { FilterPill(missedOnly) { missedOnly = it } }
        }

        // ---------- list area ----------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                number.isNotEmpty() && !selecting -> Suggestions(number, contacts, onNewContact) {
                    placeCall(ctx, it); onNumber("")
                }
                !canRead -> PermissionPrompt("Allow call log access to see your recent calls.", onRequestPerms)
                all == null -> Text("Loading…", Modifier.align(Alignment.Center), color = Pal.Gray)
                shown.isEmpty() -> Text(
                    if (missedOnly) "No missed calls" else "No recent calls",
                    Modifier.align(Alignment.Center), color = Pal.Gray
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
                            onInfo = { onOpenInfo(e.number) }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }

            // Small floating button that brings the keypad back.
            androidx.compose.animation.AnimatedVisibility(
                visible = !padVisible && !selecting && !searching,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 14.dp),
                enter = fadeIn(tween(200)) + scaleIn(tween(200)),
                exit = fadeOut(tween(150)) + scaleOut(tween(150))
            ) {
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E1E20))
                        .border(1.dp, Pal.Chip, RoundedCornerShape(16.dp)).clickable { padVisible = true },
                    contentAlignment = Alignment.Center
                ) { DotsGlyph() }
            }
        }

        // ---------- keypad / delete bar ----------
        if (selecting) {
            DeleteBar(enabled = selected.isNotEmpty()) { confirmDelete = true }
        } else {
            AnimatedVisibility(
                visible = padVisible && !searching,
                enter = slideInVertically(tween(240)) { it } + fadeIn(tween(240)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150))
            ) {
                Keypad(
                    number = number,
                    onKey = { key(it) },
                    onHide = { padVisible = false },
                    onCall = { call() },
                    onBackspace = { if (number.isNotEmpty()) onNumber(number.dropLast(1)) },
                    onClear = { onNumber("") }
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            text = "Are you sure you want to delete ${selected.size} call log?",
            confirmLabel = "Delete",
            onConfirm = {
                deleteLogEntries(ctx, selected)
                selecting = false; selected = emptySet(); confirmDelete = false; reload.intValue++
            },
            onDismiss = { confirmDelete = false }
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            text = "Clear the whole call log?",
            confirmLabel = "Clear",
            onConfirm = { clearCallLog(ctx); confirmClear = false; reload.intValue++ },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun FilterPill(missedOnly: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Pal.Chip)) {
            listOf(false to "All", true to "Missed").forEach { (m, label) ->
                val on = missedOnly == m
                Box(
                    Modifier.background(if (on) Pal.GreenDark else Color.Transparent)
                        .clickable { onChange(m) }.padding(horizontal = 18.dp, vertical = 5.dp)
                ) {
                    Text(
                        label, color = if (on) Pal.Green else Pal.Ink, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(
    number: String,
    onKey: (String) -> Unit,
    onHide: () -> Unit,
    onCall: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(Pal.Bg).padding(horizontal = 24.dp)) {
        KEYS.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (d, letters) ->
                    Box(
                        Modifier.weight(1f).height(60.dp).combinedClickable(
                            onClick = { onKey(d) },
                            onLongClick = if (d == "0") ({ onKey("+") }) else null
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(
                                d, fontSize = if (d == "*" || d == "#") 30.sp else 27.sp,
                                color = Pal.Ink, lineHeight = 30.sp
                            )
                            if (d == "1") VoicemailGlyph()
                            else if (letters.isNotEmpty()) Text(
                                letters, fontSize = 9.sp, color = Pal.Gray,
                                fontWeight = FontWeight.Medium, lineHeight = 10.sp
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                HeaderIcon(onClick = onHide) { DotsGlyph() }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(60.dp).clip(CircleShape).background(Pal.Green).clickable(onClick = onCall),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Call, "Call", tint = Color.White, modifier = Modifier.size(28.dp)) }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).combinedClickable(onClick = onBackspace, onLongClick = onClear),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace, "Delete digit",
                        tint = if (number.isNotEmpty()) Pal.Gray else Pal.Chip, modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
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
    val (icon, tint) = when (e.type) {
        CallLog.Calls.MISSED_TYPE -> Icons.Default.CallMissed to Pal.Missed
        CallLog.Calls.OUTGOING_TYPE -> Icons.Default.CallMade to Pal.Gray
        CallLog.Calls.INCOMING_TYPE -> Icons.Default.CallReceived to Pal.Gray
        else -> Icons.Default.CallMissed to Pal.Gray
    }
    val sub = if (showNumber) prettyNumber(e.number) else (e.location?.takeIf { it.isNotBlank() } ?: "Unknown location")
    Column {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLong)
                .padding(start = 14.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) { SelectCircle(checked); Spacer(Modifier.width(12.dp)) }
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (missed) Pal.Missed else Pal.Ink
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.isHd) { TagChip("HD"); Spacer(Modifier.width(4.dp)) }
                    if (e.isWifi) { TagChip("Wi-Fi"); Spacer(Modifier.width(4.dp)) }
                    Text(sub, fontSize = 13.sp, color = Pal.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(formatWhen(e.date), fontSize = 13.sp, color = Pal.Gray, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Pal.Chip).clickable(onClick = onInfo),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.KeyboardArrowRight, "Details", tint = Pal.Gray, modifier = Modifier.size(20.dp)) }
        }
        HorizontalDivider(Modifier.padding(start = 44.dp), color = Pal.Divider, thickness = 0.5.dp)
    }
}

/** Shown instead of recents while a number is being typed: matching contacts (smart dial). */
@Composable
private fun Suggestions(
    number: String,
    contacts: List<Contact>,
    onNewContact: (String) -> Unit,
    onCall: (String) -> Unit
) {
    val matches = remember(number, contacts) { contacts.filter { matchesDial(it, number) }.take(40) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                number, fontSize = if (number.length > 14) 24.sp else 32.sp, color = Pal.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onNewContact(number) }) { Text("Add contact", color = Pal.Green) }
        }
        HorizontalDivider(color = Pal.Divider, thickness = 0.5.dp)
        LazyColumn(Modifier.fillMaxSize()) {
            items(matches, key = { "${it.id}:${numberKey(it.number)}" }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onCall(c.number) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(c.name, 44.dp, c.id)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(c.name, fontSize = 17.sp, color = Pal.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(prettyNumber(c.number), fontSize = 13.sp, color = Pal.Gray)
                    }
                }
            }
        }
    }
}
