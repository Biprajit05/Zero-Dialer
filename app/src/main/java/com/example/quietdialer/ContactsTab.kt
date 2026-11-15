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
import kotlinx.coroutines.launch

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
            SearchPill(query, { query = it }, "$total contacts")
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
                    contentPadding = PaddingValues(top = 6.dp, bottom = 96.dp)
                ) {
                    if (showLead) {
                        item(key = "profile") {
                            LeadRow("Profile") {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Profile.CONTENT_URI))
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "No contacts app found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        item(key = "groups") {
                            LeadRow("Groups") {
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
                            is ListRow.Head -> Box(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 6.dp)) {
                                SectionBadge(r.letter)
                            }
                            is ListRow.Item -> {
                                val c = r.c
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    if (selecting) selected = if (c.id in selected) selected - c.id else selected + c.id
                                                    else onOpenContact(c)
                                                },
                                                onLongClick = { if (!selecting) { selecting = true; selected = setOf(c.id) } }
                                            )
                                            .padding(start = 16.dp, end = 30.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (selecting) { SelectCircle(c.id in selected); Spacer(Modifier.width(12.dp)) }
                                        Avatar(c.name, 40.dp, c.id)
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            c.name, fontSize = 16.sp, color = Pal.Ink, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                        )
                                    }
                                    HorizontalDivider(Modifier.padding(start = 70.dp, end = 30.dp), color = Pal.Divider, thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            }

            // A-Z index on the right edge: tap or drag to jump.
            if (canRead && letters.size > 1 && query.isBlank()) {
                var h by remember { mutableIntStateOf(1) }
                fun jump(y: Float) {
                    val i = ((y / h) * letters.size).toInt().coerceIn(0, letters.lastIndex)
                    val target = (headIndex[letters[i]] ?: 0) + lead
                    scope.launch { listState.scrollToItem(target) }
                }
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).width(24.dp).fillMaxHeight(0.7f)
                        .onSizeChanged { h = it.height.coerceAtLeast(1) }
                        .pointerInput(letters, lead) { detectTapGestures(onTap = { jump(it.y) }) }
                        .pointerInput(letters, lead) {
                            detectVerticalDragGestures(
                                onDragStart = { jump(it.y) },
                                onVerticalDrag = { change, _ -> jump(change.position.y) }
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    letters.forEach { l ->
                        Box(
                            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (l == currentLetter) Pal.Chip else androidx.compose.ui.graphics.Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(l.toString(), fontSize = 9.sp, color = if (l == currentLetter) Pal.Ink else Pal.Gray)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = !selecting,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                enter = fadeIn(tween(200)) + scaleIn(tween(200)),
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

@Composable
private fun LeadRow(title: String, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 30.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(title, 40.dp, person = true)
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 16.sp, color = Pal.Ink, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowRight, null, tint = Pal.Gray, modifier = Modifier.size(20.dp))
        }
        HorizontalDivider(Modifier.padding(start = 70.dp, end = 30.dp), color = Pal.Divider, thickness = 0.5.dp)
    }
}
