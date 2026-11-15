package com.example.quietdialer

import android.Manifest
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Videocam
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- contact details

private val WA_GREEN = Color(0xFF1FAF52)

@Composable
fun ContactDetailScreen(contactId: Long, contacts: List<Contact>, onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val entries = remember(contacts, contactId) { contacts.filter { it.id == contactId } }
    val c = entries.firstOrNull()
    if (c == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val numbers = remember(entries) { entries.map { it.number }.distinctBy { numberKey(it) } }
    var starred by remember(c.id) { mutableStateOf(c.starred) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    DetailPage(
        title = c.name, contactId = c.id, numbers = numbers, numberLabel = "Mobile", onBack = onBack,
        actions = {
            HeaderIcon(onClick = {
                if (setStarred(ctx, c.id, !starred)) { starred = !starred; onChanged() }
            }) {
                if (starred) Icon(Icons.Default.Star, "Favorite", tint = Pal.Green)
                else Icon(Icons.Outlined.StarBorder, "Favorite", tint = Pal.Ink)
            }
            HeaderIcon(onClick = { editContactIntent(ctx, c.id) }) { Icon(Icons.Outlined.Edit, "Edit", tint = Pal.Ink) }
            Box {
                HeaderIcon(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Pal.Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Pal.Sheet)) {
                    DropdownMenuItem(text = { Text("Copy number", color = Pal.Ink) }, onClick = { menu = false; copyNumber(ctx, numbers.first()) })
                    DropdownMenuItem(text = { Text("Block number", color = Pal.Ink) }, onClick = { menu = false; blockNumber(ctx, numbers.first()) })
                    DropdownMenuItem(text = { Text("Delete contact", color = Pal.Red) }, onClick = { menu = false; confirm = true })
                }
            }
        },
        extra = { RingtoneRow(c.id) }
    )

    if (confirm) {
        ConfirmDialog(
            text = "Are you sure you want to delete ${c.name}?", confirmLabel = "Delete",
            onConfirm = { confirm = false; deleteContacts(ctx, setOf(c.id)); onChanged(); onBack() },
            onDismiss = { confirm = false }
        )
    }
}

/** Same page for a number that isn't saved as a contact (opened from the call log). */
@Composable
fun NumberDetailScreen(number: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    DetailPage(
        title = if (isDialable(number)) prettyNumber(number) else "Unknown",
        contactId = 0L, numbers = listOf(number), numberLabel = null, onBack = onBack,
        actions = {
            HeaderIcon(onClick = { addContactIntent(ctx, number) }) { Icon(Icons.Default.PersonAdd, "Add contact", tint = Pal.Ink) }
            Box {
                HeaderIcon(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Pal.Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Pal.Sheet)) {
                    DropdownMenuItem(text = { Text("Add to existing contact", color = Pal.Ink) }, onClick = { menu = false; addToExistingIntent(ctx, number) })
                    DropdownMenuItem(text = { Text("Copy number", color = Pal.Ink) }, onClick = { menu = false; copyNumber(ctx, number) })
                    DropdownMenuItem(text = { Text("Block number", color = Pal.Ink) }, onClick = { menu = false; blockNumber(ctx, number) })
                }
            }
        }
    )
}

@Composable
private fun DetailPage(
    title: String,
    contactId: Long,
    numbers: List<String>,
    numberLabel: String?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    val ctx = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    val history by produceState<List<LogEntry>>(emptyList(), numbers) {
        value = if (hasPerm(ctx, Manifest.permission.READ_CALL_LOG)) withContext(Dispatchers.IO) {
            val keys = numbers.map { numberKey(it) }.toSet()
            loadLog(ctx).filter { numberKey(it.number) in keys }
        } else emptyList()
    }

    Column(Modifier.fillMaxSize().background(Pal.Bg).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Pal.Ink) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }

        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(title, 84.dp, contactId, person = contactId <= 0L)
            Spacer(Modifier.height(14.dp))
            Text(
                title, color = Pal.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        numbers.forEach { n ->
            val label = numberLabel
                ?: history.firstOrNull { numberKey(it.number) == numberKey(n) }?.location?.takeIf { it.isNotBlank() }
                ?: "Unknown location"
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                    .background(Pal.Card).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (isDialable(n)) prettyNumber(n) else "Unknown", color = Pal.Ink, fontSize = 17.sp)
                    Text(label, color = Pal.Gray, fontSize = 12.sp)
                }
                RoundIconButton(Icons.Default.Call, "Call") { placeCall(ctx, n) }
                Spacer(Modifier.width(10.dp))
                RoundIconButton(Icons.Default.Videocam, "Video call") { placeVideoCall(ctx, n) }
                Spacer(Modifier.width(10.dp))
                RoundIconButton(Icons.Outlined.ChatBubbleOutline, "Message") { sendSms(ctx, n) }
            }
            if (isDialable(n)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                        .background(Pal.Card).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(painterResource(R.drawable.ic_whatsapp), "WhatsApp", Modifier.size(34.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("WhatsApp", color = Pal.Ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    RoundIconButton(Icons.Default.Call, "WhatsApp call", WA_GREEN) { whatsappCall(ctx, n, false) }
                    Spacer(Modifier.width(10.dp))
                    RoundIconButton(Icons.Default.Videocam, "WhatsApp video call", WA_GREEN) { whatsappCall(ctx, n, true) }
                    Spacer(Modifier.width(10.dp))
                    RoundIconButton(Icons.Outlined.ChatBubbleOutline, "WhatsApp message", WA_GREEN) { whatsappMessage(ctx, n) }
                }
            }
        }

        if (history.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Call logs", color = Pal.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (history.size > 3) {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { showAll = !showAll }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (showAll) "Less" else "All", color = Pal.Gray, fontSize = 14.sp)
                        Icon(
                            if (showAll) Icons.Default.KeyboardArrowUp else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            null, tint = Pal.Gray, modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            val shown = if (showAll) history else history.take(3)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Pal.Card)) {
                shown.forEachIndexed { i, e ->
                    DetailLogRow(e)
                    if (i < shown.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Pal.Divider, thickness = 0.5.dp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        extra()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailLogRow(e: LogEntry) {
    val missed = e.type == android.provider.CallLog.Calls.MISSED_TYPE
    val result = when {
        missed -> "Missed"
        e.type == android.provider.CallLog.Calls.REJECTED_TYPE -> "Declined"
        e.type == 6 -> "Blocked"
        e.duration > 0 -> "${typeLabel(e.type)} ${durationWords(e.duration)}"
        else -> "${typeLabel(e.type)} · not connected"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(formatLogTime(e.date), color = if (missed) Pal.Missed else Pal.Ink, fontSize = 16.sp)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.isHd) { TagChip("HD"); Spacer(Modifier.width(4.dp)) }
                if (e.isWifi) { TagChip("Wi-Fi"); Spacer(Modifier.width(4.dp)) }
                Text(prettyNumber(e.number), color = Pal.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(result, color = Pal.Gray, fontSize = 13.sp)
    }
}

@Composable
private fun RingtoneRow(contactId: Long) {
    val ctx = LocalContext.current
    var uri by remember(contactId) { mutableStateOf(readRingtone(ctx, contactId)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val value = if (picked == null || picked == Settings.System.DEFAULT_RINGTONE_URI) null else picked
            if (setRingtone(ctx, contactId, value)) uri = value
        }
    }
    val label = remember(uri) { ringtoneTitle(ctx, uri) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Pal.Card)
            .clickable {
                val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Ringtone")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    uri?.let { u -> putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, u) }
                }
                try { launcher.launch(i) } catch (e: Exception) {
                    Toast.makeText(ctx, "No ringtone picker found", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Ringtone", color = Pal.Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(label, color = Pal.Gray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Pal.Gray, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, desc: String, tint: Color = Pal.Ink, size: androidx.compose.ui.unit.Dp = 40.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).bounceClick(shape = CircleShape, pressedScale = 0.86f, onClick = onClick).background(Pal.Chip),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(size / 2)) }
}

// ---------------------------------------------------------------- new contact

@Composable
fun NewContactScreen(initialNumber: String, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(initialNumber) }
    var email by remember { mutableStateOf("") }
    val canSave = name.isNotBlank() || phone.isNotBlank()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }

    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Pal.Ink) }
            Text(
                "New contact", Modifier.weight(1f), color = Pal.Ink, fontSize = 18.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
            )
            HeaderIcon(onClick = {
                if (canSave) {
                    if (hasPerm(ctx, Manifest.permission.WRITE_CONTACTS) && saveContact(ctx, name, phone, email, company, title)) {
                        Toast.makeText(ctx, "Contact saved", Toast.LENGTH_SHORT).show()
                        onSaved()
                    } else {
                        Toast.makeText(ctx, "Couldn't save. Allow contacts permission in app settings.", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Icon(Icons.Default.Check, "Save", tint = if (canSave) Pal.Green else Pal.Chip) }
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Pal.Card), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, null, tint = Pal.Green, modifier = Modifier.size(34.dp))
                }
            }

            FormCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FormIcon(Icons.Default.Person)
                    FormField(name, { name = it }, "Name", Modifier.weight(1f).focusRequester(fr), capitalize = true)
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = Pal.Gray, modifier = Modifier.size(22.dp))
                }
            }
            FormCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FormIcon(Icons.Default.Business)
                    FormField(company, { company = it }, "Company", Modifier.weight(1f), capitalize = true)
                }
                HorizontalDivider(Modifier.padding(start = 40.dp), color = Pal.Divider, thickness = 0.5.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(40.dp))
                    FormField(title, { title = it }, "Title", Modifier.weight(1f), capitalize = true)
                }
            }
            FormCard {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    FormIcon(Icons.Default.Call)
                    Text("Mobile", color = Pal.Ink, fontSize = 15.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(40.dp))
                    FormField(phone, { phone = it }, "Phone", Modifier.weight(1f), keyboardType = KeyboardType.Phone)
                }
            }
            FormCard {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    FormIcon(Icons.Default.Email)
                    Text("Personal", color = Pal.Ink, fontSize = 15.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(40.dp))
                    FormField(email, { email = it }, "Email", Modifier.weight(1f), keyboardType = KeyboardType.Email)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(Pal.Card).padding(horizontal = 14.dp),
        content = content
    )
}

@Composable
private fun FormIcon(icon: ImageVector) {
    Icon(icon, null, tint = Pal.Gray, modifier = Modifier.padding(end = 16.dp).size(24.dp))
}

@Composable
private fun FormField(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalize: Boolean = false
) {
    Box(modifier.padding(vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, color = Pal.Gray, fontSize = 15.sp)
        BasicTextField(
            value = value, onValueChange = onValue, singleLine = true,
            textStyle = TextStyle(color = Pal.Ink, fontSize = 15.sp),
            cursorBrush = SolidColor(Pal.Green),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ---------------------------------------------------------------- add favourites

@Composable
fun PickFavoriteScreen(contacts: List<Contact>, onBack: () -> Unit, onPicked: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val list = remember(contacts, query) {
        contacts.filter { !it.starred && (query.isBlank() || it.name.contains(query, true)) }.distinctBy { it.id }
    }
    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderIcon(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Pal.Ink) }
            Text("Add favorites", Modifier.weight(1f), color = Pal.Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        SearchPill(query, { query = it }, "Search contacts")
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) "No more contacts to add" else "No matches", color = Pal.Gray)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (setStarred(ctx, c.id, true)) { Toast.makeText(ctx, "Added to favorites", Toast.LENGTH_SHORT).show(); onPicked() }
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(c.name, 40.dp, c.id)
                        Spacer(Modifier.width(14.dp))
                        Text(c.name, color = Pal.Ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
