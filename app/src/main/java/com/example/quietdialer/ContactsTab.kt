package com.example.quietdialer

import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ListRow {
    data class Head(val letter: Char) : ListRow
    data class Item(val c: Contact) : ListRow
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsTab(
    contacts: List<Contact>,
    permsVersion: Int,
    onRequestPerms: () -> Unit,
    onOpenContact: (Contact) -> Unit,
    onNewContact: () -> Unit,
    onBarHidden: (Boolean) -> Unit,
    onChanged: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val canRead = remember(permsVersion) { hasPerm(ctx, Manifest.permission.READ_CONTACTS) }
    var query by rememberSaveable { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 30 }
    }

    LaunchedEffect(selecting) { onBarHidden(selecting) }
    DisposableEffect(Unit) { onDispose { onBarHidden(false) } }
    BackHandler(enabled = selecting) { selecting = false; selected = emptySet() }

    val googleIds by produceState(emptySet<Long>(), permsVersion, contacts) {
        value = if (canRead) withContext(Dispatchers.IO) { loadGoogleContactIds(ctx) } else emptySet()
    }

    val total = remember(contacts) { contacts.distinctBy { it.id }.size }
    val people = remember(contacts, query) {
        val q = digitsOf(query)
        (if (query.isBlank()) contacts
        else contacts.filter { it.name.contains(query, true) || (q.isNotEmpty() && digitsOf(it.number).contains(q)) })
            .distinctBy { it.id }
    }
    val rows = remember(people) {
        buildList<ListRow> {
            people.groupBy { initialOf(it.name) }
                .toSortedMap(compareBy<Char> { if (it == '#') Char.MIN_VALUE else it })
                .forEach { (letter, list) ->
                    add(ListRow.Head(letter))
                    list.forEach { add(ListRow.Item(it)) }
                }
        }
    }
    val showLead = query.isBlank() && !selecting
    val lead = if (showLead) 2 else 0
    val letters = remember(rows) { rows.filterIsInstance<ListRow.Head>().map { it.letter } }
    val headIndex = remember(rows) {
        HashMap<Char, Int>().also { m -> rows.forEachIndexed { i, r -> if (r is ListRow.Head) m[r.letter] = i } }
    }
    val currentLetter by remember(rows, lead) {
        derivedStateOf {
            when (val r = rows.getOrNull(listState.firstVisibleItemIndex - lead)) {
                is ListRow.Head -> r.letter
                is ListRow.Item -> initialOf(r.c.name)
                null -> letters.firstOrNull()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        if (selecting) {
            SelectionHeader(
                count = selected.size, total = people.size,
                onToggleAll = {
                    selected = if (selected.size == people.size) emptySet() else people.map { it.id }.toSet()
                },
                onCancel = { selecting = false; selected = emptySet() }
            )
        } else {
            CollapsingHeader("Contacts", collapsed) {
                HeaderIcon(onClick = { selecting = true }) { SelectIcon() }
                Box {
                    HeaderIcon(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "More", tint = Pal.Ink, modifier = Modifier.size(24.dp))
                    }
                    DropdownMenu(
                        expanded = menuOpen, onDismissRequest = { menuOpen = false },
                        modifier = Modifier.background(Pal.Sheet)
                    ) {
                        DropdownMenuItem(
                            text = { Text("New contact", color = Pal.Ink) },
                            onClick = { menuOpen = false; onNewContact() }
                        )
                        DropdownMenuItem(
                            text = { Text("Refresh", color = Pal.Ink) },
                            onClick = { menuOpen = false; onChanged() }
                        )
                    }
                }
            }
            SearchPill(query, { query = it }, "$total Contacts")
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !canRead -> PermissionPrompt("Allow contacts access to see your contacts here.", onRequestPerms)
                rows.isEmpty() -> Text(
                    if (query.isBlank()) "No contacts" else "No matches",
                    Modifier.align(Alignment.Center), color = Pal.Gray
                )
                else -> LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp)
                ) {
                    if (showLead) {
                        item(key = "profile") {
                            LeadRow("Profile", Icons.Default.Person) {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Profile.CONTENT_URI))
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "No contacts app found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        item(key = "groups") {
                            LeadRow("Groups", Icons.Default.People) {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Groups.CONTENT_URI))
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "No contacts app found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    items(
                        rows,
                        key = { r -> when (r) { is ListRow.Head -> "h${r.letter}"; is ListRow.Item -> "c${r.c.id}" } }
                    ) { r ->
                        when (r) {
                            is ListRow.Head -> Box(
                                Modifier.fillMaxWidth().animateItemPlacement().padding(start = 18.dp, top = 14.dp, bottom = 6.dp)
                            ) { SectionBadge(r.letter) }
                            is ListRow.Item -> {
                                val c = r.c
                                Column(Modifier.animateItemPlacement()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                            .bounceClick(
                                                shape = RoundedCornerShape(16.dp), pressedScale = 0.98f,
                                                onLongClick = { if (!selecting) { selecting = true; selected = setOf(c.id) } },
                                                onClick = {
                                                    if (selecting) selected = if (c.id in selected) selected - c.id else selected + c.id
                                                    else onOpenContact(c)
                                                }
                                            )
                                            .padding(start = 10.dp, end = 34.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (selecting) { SelectCircle(c.id in selected); Spacer(Modifier.width(12.dp)) }
                                        Avatar(c.name, 44.dp, c.id)
                                        Spacer(Modifier.width(16.dp))
                                        Text(
                                            c.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Pal.Ink, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                        )
                                        if (c.id in googleIds) GoogleBadge()
                                    }
                                    HorizontalDivider(Modifier.padding(start = 78.dp, end = 34.dp), color = Pal.Divider, thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            }

            // A-Z index on the right edge: tap or drag to jump (letters with no contacts jump to the next one).
            if (canRead && letters.isNotEmpty() && query.isBlank()) {
                var h by remember { mutableIntStateOf(1) }
                fun jump(y: Float) {
                    val i = ((y / h) * INDEX.size).toInt().coerceIn(0, INDEX.lastIndex)
                    val target = INDEX.drop(i).firstOrNull { it in letters } ?: letters.last()
                    val at = (headIndex[target] ?: 0) + lead
                    scope.launch { listState.scrollToItem(at) }
                }
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).width(26.dp).fillMaxHeight(0.78f)
                        .onSizeChanged { h = it.height.coerceAtLeast(1) }
                        .pointerInput(letters, lead) { detectTapGestures(onTap = { jump(it.y) }) }
                        .pointerInput(letters, lead) {
                            detectVerticalDragGestures(
                                onDragStart = { jump(it.y) },
                                onVerticalDrag = { change, _ -> jump(change.position.y) }
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    INDEX.forEach { l ->
                        val on = l == currentLetter
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(width = 20.dp, height = 16.dp).clip(RoundedCornerShape(5.dp))
                                    .background(if (on) Pal.Chip else androidx.compose.ui.graphics.Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(l.toString(), fontSize = 10.sp, color = if (on) Pal.Ink else Pal.Gray)
                            }
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = !selecting,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 17.dp),
                enter = fadeIn(tween(200)) + scaleIn(tween(250)),
                exit = fadeOut(tween(120)) + scaleOut(tween(120))
            ) { GreenFab(onNewContact) }
        }

        if (selecting) DeleteBar(enabled = selected.isNotEmpty()) { confirmDelete = true }
    }

    if (confirmDelete) {
        ConfirmDialog(
            text = "Are you sure you want to delete ${selected.size} contact${if (selected.size == 1) "" else "s"}?",
            confirmLabel = "Delete",
            onConfirm = {
                deleteContacts(ctx, selected)
                selecting = false; selected = emptySet(); confirmDelete = false; onChanged()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

private val INDEX: List<Char> = listOf('#') + ('A'..'Z')

/** Small grey "G" tag shown for contacts that live in a Google account. */
@Composable
private fun GoogleBadge() {
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Pal.Chip),
        contentAlignment = Alignment.Center
    ) { Text("G", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Pal.Gray) }
}

@Composable
private fun LeadRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .bounceClick(shape = RoundedCornerShape(16.dp), pressedScale = 0.98f, onClick = onClick)
                .padding(start = 10.dp, end = 34.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(title, 44.dp, icon = icon)
            Spacer(Modifier.width(16.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Pal.Ink, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowRight, null, tint = Pal.Gray, modifier = Modifier.size(24.dp))
        }
        HorizontalDivider(Modifier.padding(start = 78.dp, end = 34.dp), color = Pal.Divider, thickness = 0.5.dp)
    }
}
