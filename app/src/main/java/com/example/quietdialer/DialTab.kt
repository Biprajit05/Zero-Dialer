package com.example.quietdialer

import android.Manifest
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
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
    var themeOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 30 }
    }

    // Any finger scroll/swipe on the recents list closes the keypad and reveals the All / Missed
    // switch, which then stays until the app is opened fresh next time.
    val scrollWatcher = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y != 0f) {
                    padVisible = false
                    DialUiState.filterRevealed = true
                }
                return Offset.Zero
            }
        }
    }

    // --- keypad sheet state -------------------------------------------------------------
    val padShown = padVisible && !searching && !selecting
    // 0 = keypad closed (green dots button), 1 = keypad open (call pill). Drives the button morph.
    val morph by animateFloatAsState(
        targetValue = if (padShown) 1f else 0f,
        animationSpec = tween(if (padShown) 300 else 240, easing = FastOutSlowInEasing),
        label = "padMorph"
    )
    val fabVisible by animateFloatAsState(
        targetValue = if (!selecting && !searching) 1f else 0f, animationSpec = tween(180), label = "fabVisible"
    )
    val density = LocalDensity.current
    var padPx by remember { mutableIntStateOf(0) }
    // Room the list leaves for the keypad sheet (it slides over the bottom of the list).
    val padSpace by animateDpAsState(
        targetValue = if (padShown) with(density) { padPx.toDp() } else 0.dp,
        animationSpec = tween(260, easing = FastOutSlowInEasing), label = "padSpace"
    )
    val screenW = LocalConfiguration.current.screenWidthDp.dp

    SideEffect { DialUiState.padOpen = padShown }

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
            CollapsingHeader("Recents", collapsed) {
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
                            text = { Text("Theme", color = Pal.Ink) },
                            onClick = { menuOpen = false; themeOpen = true }
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
            // The All / Missed switch slides in on the first scroll and then stays (until the app is
            // opened fresh next time); it is also always shown while Missed is on.
            AnimatedVisibility(
                visible = (DialUiState.filterRevealed || missedOnly) && !searching,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
            ) { FilterPill(missedOnly) { missedOnly = it } }
        }

        // ---------- list area ----------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                number.isNotEmpty() && !selecting -> Box(Modifier.fillMaxSize().padding(bottom = padSpace)) {
                    Suggestions(
                        number = number, contacts = contacts, showNumber = !padVisible,
                        onNewContact = onNewContact
                    ) { placeCall(ctx, it); onNumber("") }
                }
                !canRead -> PermissionPrompt("Allow call log access to see your recent calls.", onRequestPerms)
                all == null -> Text("Loading…", Modifier.align(Alignment.Center), color = Pal.Gray)
                shown.isEmpty() -> Text(
                    if (missedOnly) "No missed calls" else "No recent calls",
                    Modifier.align(Alignment.Center), color = Pal.Gray
                )
                else -> LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize().nestedScroll(scrollWatcher),
                    contentPadding = PaddingValues(bottom = maxOf(120.dp, padSpace))
                ) {
                    items(shown, key = { it.id }) { e ->
                        val nm = nameFor(e, nameMap)
                        RecentRow(
                            modifier = Modifier.animateItemPlacement(),
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
                }
            }

            // The keypad is a sheet that slides up over the bottom of the list.
            androidx.compose.animation.AnimatedVisibility(
                visible = padShown,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 900f)) { it },
                exit = slideOutVertically(tween(230, easing = FastOutLinearInEasing)) { it }
            ) {
                Keypad(
                    modifier = Modifier.onSizeChanged { padPx = it.height },
                    number = number,
                    morph = { morph },
                    onPaste = { text ->
                        val clean = text.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                        if (clean.isEmpty()) Toast.makeText(ctx, "No number to paste", Toast.LENGTH_SHORT).show()
                        else onNumber((number + clean).take(32))
                    },
                    onCopy = { copyNumber(ctx, number) },
                    onKey = { key(it) },
                    onHide = { padVisible = false },
                    onCall = { call() },
                    onBackspace = { if (number.isNotEmpty()) onNumber(number.dropLast(1)) },
                    onClear = { onNumber("") }
                )
            }

            // Green dots button (same spot as the + button on Contacts). When the keypad opens it
            // shrinks and slides toward the centre while the call pill grows out of it.
            val fabAlpha = fabVisible * (1f - morph * 1.6f).coerceIn(0f, 1f)
            if (fabAlpha > 0.01f) {
                GreenFab(
                    onClick = { padVisible = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 17.dp)
                        .graphicsLayer {
                            alpha = fabAlpha
                            val sc = 1f - 0.5f * morph
                            scaleX = sc; scaleY = sc
                            translationX = -(screenW.toPx() * 0.28f) * morph
                            translationY = -(84.dp.toPx()) * morph
                        },
                    icon = { DotsGlyph(Color.White) }
                )
            }
        }

        // ---------- delete bar (selection mode) ----------
        if (selecting) DeleteBar(enabled = selected.isNotEmpty()) { confirmDelete = true }
    }

    if (themeOpen) {
        ThemeDialog(
            current = ThemeSettings.mode,
            onPick = { ThemeSettings.save(ctx, it); themeOpen = false },
            onDismiss = { themeOpen = false }
        )
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
    val segW = 68.dp
    val segH = 30.dp
    Box(Modifier.fillMaxWidth().padding(top = 0.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.shadow(3.dp, CircleShape).background(Pal.Raised).padding(3.dp)) {
            // The highlight slides between All and Missed and changes colour: green for All, red for Missed.
            val x by animateDpAsState(
                targetValue = if (missedOnly) segW else 0.dp,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                label = "filterHighlight"
            )
            val hl by animateColorAsState(if (missedOnly) Pal.MissedPill else Pal.GreenDark, tween(220), label = "filterHighlightColor")
            Box(Modifier.offset(x = x).size(width = segW, height = segH).clip(CircleShape).background(hl))
            Row {
                listOf(false to "All", true to "Missed").forEach { (m, label) ->
                    val on = missedOnly == m
                    val target = if (!on) Pal.Gray else if (m) Pal.MissedPillText else Pal.Green
                    val c by animateColorAsState(target, tween(220), label = "filterText")
                    Box(
                        Modifier.size(width = segW, height = segH)
                            .bounceClick(shape = CircleShape, pressedScale = 0.92f, ripple = false) { onChange(m) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = c, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** The keypad sheet: pill-shaped keys, a wide green call pill, a grab handle on top. */
@Composable
private fun Keypad(
    modifier: Modifier = Modifier,
    number: String,
    morph: () -> Float,
    onPaste: (String) -> Unit,
    onCopy: () -> Unit,
    onKey: (String) -> Unit,
    onHide: () -> Unit,
    onCall: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit
) {
    val keyShape = RoundedCornerShape(50)
    Column(
        modifier.fillMaxWidth()
            .background(Pal.PadBg, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            // the nav pill floats over this bottom part
            .padding(bottom = 109.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(width = 34.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Pal.Handle))
        Spacer(Modifier.height(6.dp))
        AnimatedVisibility(visible = number.isNotEmpty(), enter = expandVertically(tween(160)) + fadeIn(tween(160)), exit = shrinkVertically(tween(140)) + fadeOut(tween(100))) {
            NumberBar(number, onPaste, onCopy)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            KEYS.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (d, letters) ->
                        Box(
                            Modifier.weight(1f).height(46.dp)
                                .bounceClick(
                                    shape = keyShape, pressedScale = 0.93f,
                                    onLongClick = if (d == "0") ({ onKey("+") }) else null,
                                    onClick = { onKey(d) }
                                )
                                .background(Pal.Key),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(
                                    d, fontSize = if (d == "*" || d == "#") 26.sp else 23.sp,
                                    color = Pal.Ink, lineHeight = 24.sp
                                )
                                if (d == "1") VoicemailGlyph()
                                else if (letters.isNotEmpty()) Text(
                                    letters, fontSize = 8.sp, color = Pal.KeyLetters,
                                    fontWeight = FontWeight.Medium, lineHeight = 8.sp
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                // hide keypad
                Box(
                    Modifier.weight(1f).height(46.dp)
                        .bounceClick(shape = keyShape, pressedScale = 0.93f, onClick = onHide)
                        .background(Pal.Key),
                    contentAlignment = Alignment.Center
                ) { DotsGlyph(Pal.Ink) }
                // call: grows out of the green button when the sheet opens
                Box(
                    Modifier.weight(1f).height(46.dp).graphicsLayer {
                        val m = morph()
                        scaleX = 0.35f + 0.65f * m
                        scaleY = 0.6f + 0.4f * m
                        alpha = (m * 1.6f).coerceIn(0f, 1f)
                    }
                ) {
                    Box(
                        Modifier.fillMaxSize()
                            .bounceClick(shape = keyShape, pressedScale = 0.93f, onClick = onCall)
                            .background(Pal.CallGreen),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Call, "Call", tint = Color.White, modifier = Modifier.size(26.dp)) }
                }
                // backspace (long press clears)
                Box(
                    Modifier.weight(1f).height(46.dp)
                        .bounceClick(shape = keyShape, pressedScale = 0.93f, onLongClick = onClear, onClick = onBackspace)
                        .background(Pal.Key),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace, "Delete digit",
                        tint = Pal.KeyLetters.copy(alpha = if (number.isNotEmpty()) 1f else 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentRow(
    modifier: Modifier = Modifier,
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
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .bounceClick(shape = shape, pressedScale = 0.98f, onLongClick = onLong, onClick = onClick)
                .background(if (missed) Pal.MissedBg else Color.Transparent)
                .padding(start = 8.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) { SelectCircle(checked); Spacer(Modifier.width(12.dp)) }
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (missed) Pal.Missed else Pal.Ink
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.isVolte) { TagChip("VoLTE"); Spacer(Modifier.width(5.dp)) }
                    if (e.isHd) { TagChip("HD"); Spacer(Modifier.width(5.dp)) }
                    if (e.isWifi) { TagChip("Wi-Fi"); Spacer(Modifier.width(5.dp)) }
                    Text(
                        sub, fontSize = 14.sp, color = if (missed) Pal.Missed else Pal.Gray,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(formatWhen(e.date), fontSize = 14.sp, color = if (missed) Pal.Missed else Pal.Gray, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(34.dp)
                    .bounceClick(shape = CircleShape, pressedScale = 0.82f, onClick = onInfo)
                    .background(Pal.Chip),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.KeyboardArrowRight, "Details", tint = Pal.Ink, modifier = Modifier.size(22.dp)) }
        }
        HorizontalDivider(Modifier.padding(start = 42.dp), color = Pal.Divider, thickness = 0.5.dp)
    }
}

/** The typed number, shown right above the keys, with paste (left) and copy (right). */
@Composable
private fun NumberBar(number: String, onPaste: (String) -> Unit, onCopy: () -> Unit) {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(android.content.ClipboardManager::class.java) }
    // Re-check the clipboard whenever the typed number changes.
    val canPaste = remember(number) { cm.hasPrimaryClip() }
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (canPaste) HeaderIcon(plain = true, onClick = {
                val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                onPaste(t)
            }) { Icon(Icons.Default.ContentPaste, "Paste number", tint = Pal.Gray, modifier = Modifier.size(22.dp)) }
        }
        Text(
            number,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = when { number.length > 18 -> 22.sp; number.length > 12 -> 28.sp; else -> 30.sp },
            color = Pal.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (number.isNotEmpty()) HeaderIcon(plain = true, onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, "Copy number", tint = Pal.Gray, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Shown instead of recents while a number is being typed. For a number that isn't saved yet it
 * lists New contact / Add to existing contact / Send message / Video call, then matching contacts.
 */
@Composable
private fun Suggestions(
    number: String,
    contacts: List<Contact>,
    showNumber: Boolean,
    onNewContact: (String) -> Unit,
    onCall: (String) -> Unit
) {
    val ctx = LocalContext.current
    val matches = remember(number, contacts) { contacts.filter { matchesDial(it, number) }.take(40) }
    val key = numberKey(number)
    val saved = remember(number, contacts) { key.isNotEmpty() && contacts.any { numberKey(it.number) == key } }
    Column(Modifier.fillMaxSize()) {
        if (showNumber) {
            // Keypad is hidden, so show the number here instead.
            Text(
                number, fontSize = if (number.length > 14) 24.sp else 32.sp, color = Pal.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )
            HorizontalDivider(color = Pal.Divider, thickness = 0.5.dp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (!saved) {
                item {
                    Column {
                        OptionRow("New contact") { onNewContact(number) }
                        OptionRow("Add to existing contact") { addToExistingIntent(ctx, number) }
                        OptionRow("Send message") { sendSms(ctx, number) }
                        OptionRow("Video call") { placeVideoCall(ctx, number) }
                        if (matches.isNotEmpty()) HorizontalDivider(color = Pal.Divider, thickness = 0.5.dp)
                    }
                }
            }
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

@Composable
private fun OptionRow(label: String, onClick: () -> Unit) {
    Text(
        label, color = Pal.Link, fontSize = 18.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 15.dp)
    )
}
