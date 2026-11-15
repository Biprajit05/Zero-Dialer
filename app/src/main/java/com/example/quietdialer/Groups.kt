package com.example.quietdialer

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------- storage

/** A named set of contacts. Groups live inside this app (they are not synced to the phone's contacts). */
data class ContactGroup(val id: Long, val name: String, val members: List<Long>)

object GroupStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("groups", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<ContactGroup> = try {
        val arr = JSONArray(prefs(ctx).getString("data", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val m = o.getJSONArray("members")
            ContactGroup(o.getLong("id"), o.getString("name"), (0 until m.length()).map { m.getLong(it) })
        }
    } catch (e: Exception) { emptyList() }

    fun save(ctx: Context, list: List<ContactGroup>) {
        val arr = JSONArray()
        list.forEach { g ->
            arr.put(JSONObject().put("id", g.id).put("name", g.name).put("members", JSONArray(g.members)))
        }
        prefs(ctx).edit().putString("data", arr.toString()).apply()
    }
}

// ---------------------------------------------------------------- list of groups

@Composable
fun GroupsScreen(
    groups: List<ContactGroup>,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderIcon(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Pal.Ink) }
                Text("Groups", Modifier.weight(1f), color = Pal.Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar("", 72.dp, icon = Icons.Default.People)
                        Spacer(Modifier.height(14.dp))
                        Text("No groups yet", color = Pal.Ink, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap + to create one", color = Pal.Gray, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp)) {
                    items(groups, key = { it.id }) { g ->
                        Column {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                    .bounceClick(shape = RoundedCornerShape(16.dp), pressedScale = 0.98f) { onOpen(g.id) }
                                    .padding(start = 10.dp, end = 34.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(g.name, 44.dp, icon = Icons.Default.People)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        g.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Pal.Ink,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if (g.members.size == 1) "1 member" else "${g.members.size} members",
                                        fontSize = 14.sp, color = Pal.Gray
                                    )
                                }
                                Icon(Icons.Default.KeyboardArrowRight, null, tint = Pal.Gray, modifier = Modifier.size(24.dp))
                            }
                            HorizontalDivider(Modifier.padding(start = 78.dp, end = 34.dp), color = Pal.Divider, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
        GreenFab(onNew, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 17.dp))
    }
}

// ---------------------------------------------------------------- one group

@Composable
fun GroupDetailScreen(
    group: ContactGroup?,
    contacts: List<Contact>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenContact: (Long) -> Unit
) {
    val ctx = LocalContext.current
    if (group == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val people = remember(group, contacts) {
        group.members.mapNotNull { id -> contacts.firstOrNull { it.id == id } }
    }

    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Pal.Ink) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderIcon(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit group", tint = Pal.Ink) }
                Box {
                    HeaderIcon(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Pal.Ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Pal.Sheet)) {
                        DropdownMenuItem(text = { Text("Rename or add people", color = Pal.Ink) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Delete group", color = Pal.Red) }, onClick = { menu = false; confirm = true })
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Avatar(group.name, 84.dp, icon = Icons.Default.People)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        group.name, color = Pal.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (people.size == 1) "1 member" else "${people.size} members",
                        color = Pal.Gray, fontSize = 14.sp
                    )
                }
            }
            if (people.isEmpty()) {
                item {
                    Text(
                        "No members. Tap the pencil to add people.", color = Pal.Gray, fontSize = 15.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)
                    )
                }
            }
            items(people, key = { it.id }) { c ->
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                            .bounceClick(shape = RoundedCornerShape(16.dp), pressedScale = 0.98f) { onOpenContact(c.id) }
                            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
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
                    HorizontalDivider(Modifier.padding(start = 78.dp, end = 34.dp), color = Pal.Divider, thickness = 0.5.dp)
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            text = "Delete the group \"${group.name}\"? The contacts stay.", confirmLabel = "Delete",
            onConfirm = { confirm = false; onDelete() },
            onDismiss = { confirm = false }
        )
    }
}

// ---------------------------------------------------------------- create / edit

/** New group (existing == null) or rename / change members of an existing one. */
@Composable
fun GroupEditScreen(
    existing: ContactGroup?,
    contacts: List<Contact>,
    onClose: () -> Unit,
    onSave: (name: String, members: List<Long>) -> Unit
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var chosen by remember { mutableStateOf(existing?.members?.toSet() ?: emptySet()) }
    var query by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (existing == null) fr.requestFocus() }

    val list = remember(contacts, query) {
        val all = contacts.distinctBy { it.id }
        if (query.isBlank()) all
        else {
            val q = query.trim().lowercase()
            val qd = digitsOf(query)
            all.map { it to searchRank(it, q, qd) }.filter { it.second >= 0 }
                .sortedWith(compareBy({ it.second }, { it.first.name.lowercase() })).map { it.first }
        }
    }

    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Pal.Ink) }
            Text(
                if (existing == null) "New group" else "Edit group", Modifier.weight(1f), color = Pal.Ink,
                fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
            )
            HeaderIcon(onClick = {
                if (name.isBlank()) Toast.makeText(ctx, "Enter a group name", Toast.LENGTH_SHORT).show()
                else onSave(name.trim(), chosen.toList())
            }) { Icon(Icons.Default.Check, "Save", tint = if (name.isNotBlank()) Pal.Green else Pal.Chip) }
        }

        // name field
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
                .background(Pal.Card).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.People, null, tint = Pal.Gray, modifier = Modifier.padding(end = 16.dp).size(24.dp))
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (name.isEmpty()) Text("Group name", color = Pal.Gray, fontSize = 16.sp)
                BasicTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    textStyle = TextStyle(color = Pal.Ink, fontSize = 16.sp),
                    cursorBrush = SolidColor(Pal.Green),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().focusRequester(fr)
                )
            }
        }

        Text(
            if (chosen.isEmpty()) "Add people" else "Add people  ·  ${chosen.size} selected",
            color = Pal.Gray, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)
        )
        SearchPill(query, { query = it }, "Search contacts")

        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(if (query.isBlank()) "No contacts" else "No matches", color = Pal.Gray, modifier = Modifier.padding(top = 40.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                            .bounceClick(shape = RoundedCornerShape(16.dp), pressedScale = 0.98f) {
                                chosen = if (c.id in chosen) chosen - c.id else chosen + c.id
                            }
                            .padding(start = 10.dp, end = 34.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SelectCircle(c.id in chosen)
                        Spacer(Modifier.width(12.dp))
                        Avatar(c.name, 44.dp, c.id)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            c.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Pal.Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
