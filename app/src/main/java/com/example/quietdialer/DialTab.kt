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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import android.view.inputmethod.InputMethodManager
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

/** Calls from the same number share one row in Recents. */
private fun groupKey(e: LogEntry): String = numberKey(e.number).ifEmpty { "id${e.id}" }

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
    // The dialled number with its cursor / selection. Keys insert at the cursor; the system
    // long-press menu (Copy / Paste / Select all) works on it. `number` stays the source of truth
    // for everything else; when it changes from outside (call placed, redial) the cursor goes to the end.
    var field by remember { mutableStateOf(TextFieldValue(number, TextRange(number.length))) }
    LaunchedEffect(number) {
        if (field.text != number) field = TextFieldValue(number, TextRange(number.length))
    }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    var padVisible by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var fakeOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val searchState = rememberLazyListState()
    // Every change of the search text starts again from the top of the results.
    LaunchedEffect(query) { searchState.scrollToItem(0) }

    // Keyboard handling for the search: a downward swipe while it is open closes the keyboard (search text stays).
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val imeOpenState = rememberUpdatedState(WindowInsets.ime.getBottom(LocalDensity.current) > 0)
    fun closeSearch() {
        focusManager.clearFocus()
        ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }
    val searchWatcher = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y > 0f && imeOpenState.value) closeSearch()
                return Offset.Zero
            }
        }
    }
    // Switching between All and Missed always starts from the top of the list.
    LaunchedEffect(missedOnly) { listState.scrollToItem(0) }
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

    // Scrolling the suggestions list (while a number is typed) also closes the keypad.
    val padCloseWatcher = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y != 0f) padVisible = false
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
    // How many missed calls each number has (shown in brackets when more than one).
    val missedCounts = remember(all) {
        HashMap<String, Int>().also { m ->
            all?.forEach { if (it.type == CallLog.Calls.MISSED_TYPE) m.merge(groupKey(it), 1, Int::plus) }
        }
    }
    // One row per number: its latest call (the list is newest first).
    val shown = remember(all, query, missedOnly, nameMap) {
        val seen = HashSet<String>()
        (all ?: emptyList()).filter { e ->
            (!missedOnly || e.type == CallLog.Calls.MISSED_TYPE) &&
                (query.isBlank() || nameFor(e, nameMap).contains(query, true) || e.number.contains(query))
        }.filter { seen.add(groupKey(it)) }
    }

    // Contacts that fit the search text (best match first, one row per contact).
    val contactHits = remember(contacts, query) {
        if (query.isBlank()) emptyList()
        else {
            val q = query.trim().lowercase()
            val qd = digitsOf(query)
            contacts.map { it to searchRank(it, q, qd) }
                .filter { it.second >= 0 }
                .sortedWith(compareBy({ it.second }, { it.first.name.lowercase() }))
                .distinctBy { it.first.id }
                .map { it.first }
        }
    }

    BackHandler(enabled = selecting || searching) {
        if (selecting) { selecting = false; selected = emptySet() }
        else { searching = false; query = "" }
    }

    fun key(k: String) {
        val a = field.selection.min.coerceIn(0, field.text.length)
        val b = field.selection.max.coerceIn(0, field.text.length)
        if (field.text.length - (b - a) >= 32) return
        dtmf.play(k)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val t = field.text.replaceRange(a, b, k)
        field = TextFieldValue(t, TextRange(a + k.length))
        onNumber(t)
    }

    fun backspace() {
        val a = field.selection.min.coerceIn(0, field.text.length)
        val b = field.selection.max.coerceIn(0, field.text.length)
        val (from, to) = if (a != b) a to b else if (a > 0) (a - 1) to a else return
        val t = field.text.removeRange(from, to)
        field = TextFieldValue(t, TextRange(from))
        onNumber(t)
    }

    // Typing in the field itself (paste / cut from the long-press menu): keep only dial characters.
    fun fieldChanged(v: TextFieldValue) {
        fun ok(c: Char) = c.isDigit() || c == '+' || c == '*' || c == '#'
        val clean = v.text.filter(::ok).take(32)
        fun idx(i: Int) = v.text.take(i).count(::ok).coerceAtMost(clean.length)
        field = TextFieldValue(clean, TextRange(idx(v.selection.start), idx(v.selection.end)))
        if (clean != number) onNumber(clean)
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
                            text = { Text("Fake call", color = Pal.Ink) },
                            onClick = { menuOpen = false; fakeOpen = true }
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
                        number = number, contacts = contacts, log = all ?: emptyList(), showNumber = !padVisible,
                        onOpenPad = { padVisible = true }, closePad = padCloseWatcher,
                        onNewContact = onNewContact
                    ) { placeCall(ctx, it); onNumber("") }
                }
                !canRead -> PermissionPrompt("Allow call log access to see your recent calls.", onRequestPerms)
                all == null -> Text("Loading…", Modifier.align(Alignment.Center), color = Pal.Gray)
                searching && query.isNotBlank() && !selecting -> LazyColumn(
                    state = searchState, modifier = Modifier.fillMaxSize().nestedScroll(searchWatcher),
                    contentPadding = PaddingValues(bottom = maxOf(120.dp, padSpace))
                ) {
                    if (shown.isNotEmpty()) {
                        item(key = "hCalls") { SearchLabel("Recent calls") }
                        items(shown, key = { "r${it.id}" }) { e ->
                            val nm = nameFor(e, nameMap)
                            RecentRow(
                                e = e,
                                missedCount = missedCounts[groupKey(e)] ?: 0,
                                title = nm.ifBlank { if (isDialable(e.number)) prettyNumber(e.number) else "Unknown" },
                                showNumber = nm.isNotBlank(),
                                selecting = false,
                                checked = false,
                                onClick = { placeCall(ctx, e.number) },
                                onLong = { selecting = true; searching = false; query = ""; selected = setOf(e.id) },
                                onInfo = { onOpenInfo(e.number) },
                                showCall = true,
                                onCall = { placeCall(ctx, e.number) }
                            )
                        }
                    }
                    if (contactHits.isNotEmpty()) {
                        item(key = "hContacts") {
                            SearchLabel(if (contactHits.size == 1) "1 contact found" else "${contactHits.size} contacts found")
                        }
                        items(contactHits, key = { "c${it.id}" }) { c ->
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                        .bounceClick(shape = RoundedCornerShape(16.dp), pressedScale = 0.98f) { onOpenInfo(c.number) }
                                        .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(c.name, 44.dp, c.id)
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            c.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Pal.Ink,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(prettyNumber(c.number), fontSize = 14.sp, color = Pal.Gray, maxLines = 1)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    RoundIconButton(Icons.Default.Call, "Call") { placeCall(ctx, c.number) }
                                }
                                HorizontalDivider(Modifier.padding(start = 78.dp, end = 14.dp), color = Pal.Divider, thickness = 0.5.dp)
                            }
                        }
                    }
                    if (shown.isEmpty() && contactHits.isEmpty()) {
                        item(key = "none") {
                            Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.TopCenter) {
                                Text("No matches", color = Pal.Gray)
                            }
                        }
                    }
                }
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
                            missedCount = missedCounts[groupKey(e)] ?: 0,
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
                    field = field,
                    onField = { fieldChanged(it) },
                    onKey = { key(it) },
                    onHide = { padVisible = false },
                    onCall = { call() },
                    onBackspace = { backspace() },
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

    if (fakeOpen) FakeCallDialog { fakeOpen = false }

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
                // A row stands for all calls of that number (only the missed ones on the Missed tab).
                val keys = shown.filter { it.id in selected }.map { groupKey(it) }.toSet()
                val ids = (all ?: emptyList()).filter {
                    groupKey(it) in keys && (!missedOnly || it.type == CallLog.Calls.MISSED_TYPE)
                }.map { it.id }.toSet()
                deleteLogEntries(ctx, ids)
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
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    onKey: (String) -> Unit,
    onHide: () -> Unit,
    onCall: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit
) {
    val keyShape = RoundedCornerShape(50)
    Column(
        modifier.fillMaxWidth()
            // swipe the sheet down to close it
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { total = 0f },
                    onDragCancel = { total = 0f },
                    onVerticalDrag = { change, dy ->
                        total += dy
                        if (total > 70f) { total = Float.NEGATIVE_INFINITY; change.consume(); onHide() }
                    }
                )
            }
            .background(Pal.PadBg, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            // the nav pill floats over this bottom part
            .padding(bottom = 109.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(width = 34.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Pal.Handle))
        Spacer(Modifier.height(6.dp))
        NumberBar(field, onField)
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
    missedCount: Int = 0,
    title: String,
    showNumber: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onInfo: () -> Unit,
    showCall: Boolean = false,
    onCall: () -> Unit = {}
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
                .padding(start = 8.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) { SelectCircle(checked); Spacer(Modifier.width(12.dp)) }
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (missed && missedCount > 1) "$title ($missedCount)" else title, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
            if (showCall) {
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Default.Call, "Call", size = 34.dp, onClick = onCall)
            }
        }
        HorizontalDivider(Modifier.padding(start = 42.dp), color = Pal.Divider, thickness = 0.5.dp)
    }
}

/**
 * The typed number, shown right above the keys. Tap to place the cursor, long-press for Android's own
 * Copy / Paste / Select all menu. The system keyboard is switched off here - the keypad is the input.
 */
@Composable
private fun NumberBar(field: TextFieldValue, onField: (TextFieldValue) -> Unit) {
    val n = field.text.length
    CompositionLocalProvider(LocalTextInputService provides null) {
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            BasicTextField(
                value = field,
                onValueChange = onField,
                singleLine = true,
                textStyle = TextStyle(
                    textAlign = TextAlign.Center, color = Pal.Ink,
                    fontSize = when { n > 18 -> 22.sp; n > 12 -> 28.sp; else -> 30.sp }
                ),
                cursorBrush = SolidColor(Pal.Green),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Shown instead of recents while a number is being typed: contacts and recent numbers that look like it
 * (most similar first), then - only when the number isn't a saved contact - New contact / Add to existing
 * contact / Send message / Video call.
 */
@Composable
private fun Suggestions(
    number: String,
    contacts: List<Contact>,
    log: List<LogEntry>,
    showNumber: Boolean,
    onOpenPad: () -> Unit,
    closePad: NestedScrollConnection,
    onNewContact: (String) -> Unit,
    onCall: (String) -> Unit
) {
    val ctx = LocalContext.current
    val matches = remember(number, contacts, log) { dialSuggestions(number, contacts, log) }
    val key = numberKey(number)
    val saved = remember(number, contacts) { key.isNotEmpty() && contacts.any { numberKey(it.number) == key } }
    Column(Modifier.fillMaxSize()) {
        if (showNumber) {
            // Keypad is hidden: the typed number sits in the middle; tap it to bring the keypad back.
            Text(
                number, fontSize = if (number.length > 14) 26.sp else 34.sp, color = Pal.Ink,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
                    .bounceClick(shape = RoundedCornerShape(12.dp), pressedScale = 0.98f, ripple = false, onClick = onOpenPad)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
            HorizontalDivider(color = Pal.Divider, thickness = 0.5.dp)
        }
        LazyColumn(Modifier.fillMaxSize().nestedScroll(closePad)) {
            items(matches, key = { "${it.contact?.id ?: 0}:${numberKey(it.number)}" }) { m ->
                val c = m.contact
                Row(
                    Modifier.fillMaxWidth().clickable { onCall(m.number) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (c != null) Avatar(c.name, 44.dp, c.id) else Avatar("", 44.dp, 0L, person = true)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            c?.name ?: prettyNumber(m.number), fontSize = 17.sp, color = Pal.Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (c != null) prettyNumber(m.number) else "Recent call",
                            fontSize = 13.sp, color = Pal.Gray
                        )
                    }
                }
            }
            if (!saved && isDialable(number)) {
                item {
                    Column {
                        if (matches.isNotEmpty()) HorizontalDivider(color = Pal.Divider, thickness = 0.5.dp)
                        OptionRow("New contact") { onNewContact(number) }
                        OptionRow("Add to existing contact") { addToExistingIntent(ctx, number) }
                        OptionRow("Send message") { sendSms(ctx, number) }
                        OptionRow("Video call") { placeVideoCall(ctx, number) }
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

/** Small grey heading above a group of search results ("Recent calls", "3 contacts found"). */
@Composable
private fun SearchLabel(text: String) {
    Text(
        text, color = Pal.Gray, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp)
    )
}
