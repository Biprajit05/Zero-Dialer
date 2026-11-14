package com.example.quietdialer

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ContactsTab(contacts: List<Contact>, permsVersion: Int, onRequestPerms: () -> Unit) {
    val ctx = LocalContext.current
    val canRead = remember(permsVersion) { hasPerm(ctx, Manifest.permission.READ_CONTACTS) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<Contact?>(null) }

    BackHandler(enabled = searching) { searching = false; query = "" }

    val filtered = remember(contacts, query) {
        if (query.isBlank()) contacts
        else contacts.filter { it.name.contains(query, true) || digitsOf(it.number).contains(digitsOf(query).ifEmpty { "\u0000" }) }
    }
    val groups = remember(filtered) {
        filtered.groupBy { initialOf(it.name) }
            .toSortedMap(compareBy<Char> { if (it == '#') Char.MAX_VALUE else it })
    }

    Column(Modifier.fillMaxSize()) {
        Header("Contacts") {
            HeaderIcon(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(Icons.Default.Search, "Search", tint = Pal.Ink, modifier = Modifier.size(28.dp))
            }
        }
        if (searching) SearchField(query, { query = it }, { searching = false; query = "" }, "Search contacts")

        when {
            !canRead -> PermissionPrompt("Allow contacts access to see your contacts here.", onRequestPerms)
            groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) "No contacts" else "No matches", color = Pal.Gray)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (letter, list) ->
                    item(key = "h$letter") {
                        Text(
                            letter.toString(), color = Pal.Green, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp)
                        )
                    }
                    items(list, key = { "${it.id}:${numberKey(it.number)}" }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = c }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
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
    }

    picked?.let { c -> ActionDialog(c.name, c.number, emptyList()) { picked = null } }
}
