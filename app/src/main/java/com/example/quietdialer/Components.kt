package com.example.quietdialer

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dark theme with green accents. */
object Pal {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF1C1C1E)
    val Sheet = Color(0xFF2B2B2D)
    val Field = Color(0xFF1F1F21)
    val Chip = Color(0xFF3A3A3C)
    val Green = Color(0xFF30C85A)
    val GreenDark = Color(0xFF123B20)
    val Ink = Color(0xFFFFFFFF)
    val Gray = Color(0xFF8E8E93)
    val Divider = Color(0xFF262628)
    val Red = Color(0xFFFF4B4B)
    val Missed = Color(0xFFFF5A52)
}

/** Big page title that shrinks to a small one once the list below has been scrolled. */
@Composable
fun CollapsingHeader(title: String, collapsed: Boolean, actions: @Composable RowScope.() -> Unit = {}) {
    val size by animateFloatAsState(if (collapsed) 20f else 30f, tween(180), label = "titleSize")
    val top by animateDpAsState(if (collapsed) 8.dp else 22.dp, tween(180), label = "titleTop")
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = top, bottom = 6.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title, fontSize = size.sp, fontWeight = FontWeight.SemiBold, color = Pal.Ink,
            maxLines = 1, modifier = Modifier.weight(1f)
        )
        actions()
    }
}

@Composable
fun HeaderIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun SelectIcon() {
    Icon(Icons.Outlined.CheckBox, "Select", tint = Pal.Ink, modifier = Modifier.size(24.dp))
}

/** Header shown while picking items: select-all box, "N selected", close. */
@Composable
fun SelectionHeader(count: Int, total: Int, onToggleAll: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderIcon(onClick = onToggleAll) {
            SelectAllGlyph(if (count == 0) 0 else if (count >= total) 2 else 1)
        }
        Text(
            if (count == 0) "Select items" else "$count selected", Modifier.weight(1f),
            color = Pal.Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
        )
        HeaderIcon(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel", tint = Pal.Ink) }
    }
}

/** state: 0 = none, 1 = some, 2 = all. */
@Composable
fun SelectAllGlyph(state: Int) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.size(22.dp).clip(shape)
            .background(if (state == 0) Color.Transparent else Pal.Green)
            .border(2.dp, if (state == 0) Pal.Gray else Pal.Green, shape),
        contentAlignment = Alignment.Center
    ) {
        if (state == 2) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        if (state == 1) Icon(Icons.Default.Remove, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun SelectCircle(checked: Boolean) {
    if (checked) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Pal.Green), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    } else {
        Box(Modifier.size(22.dp).border(2.dp, Pal.Chip, CircleShape))
    }
}

@Composable
fun SearchPill(
    query: String,
    onQuery: (String) -> Unit,
    hint: String,
    autoFocus: Boolean = false,
    onClose: (() -> Unit)? = null
) {
    val fr = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { fr.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(40.dp)
            .clip(RoundedCornerShape(20.dp)).background(Pal.Field).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, null, tint = Pal.Gray, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, color = Pal.Gray, fontSize = 15.sp, maxLines = 1)
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(color = Pal.Ink, fontSize = 15.sp),
                cursorBrush = SolidColor(Pal.Green),
                modifier = Modifier.fillMaxWidth().focusRequester(fr)
            )
        }
        if (query.isNotEmpty() || onClose != null) {
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .clickable { if (query.isNotEmpty()) onQuery("") else onClose?.invoke() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Close, "Clear", tint = Pal.Gray, modifier = Modifier.size(16.dp)) }
        }
    }
}

/** Letter badge that starts each contact section. */
@Composable
fun SectionBadge(letter: Char) {
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(Pal.Green),
        contentAlignment = Alignment.Center
    ) { Text(letter.toString(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

private object PhotoCache {
    private val hit = android.util.LruCache<Long, ImageBitmap>(80)
    private val miss = HashSet<Long>()

    fun get(id: Long): ImageBitmap? = if (id > 0) hit.get(id) else null

    suspend fun load(ctx: Context, id: Long): ImageBitmap? {
        if (id in miss) return null
        val bmp = withContext(Dispatchers.IO) {
            try {
                val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
                ContactsContract.Contacts.openContactPhotoInputStream(ctx.contentResolver, uri, false)
                    ?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            } catch (e: Exception) { null }
        }
        if (bmp != null) hit.put(id, bmp) else miss.add(id)
        return bmp
    }
}

/** Round contact avatar: the contact's photo if there is one, otherwise their first letter. */
@Composable
fun Avatar(name: String, size: Dp, contactId: Long = 0L, person: Boolean = false) {
    val ctx = LocalContext.current
    val photo by produceState<ImageBitmap?>(PhotoCache.get(contactId), contactId) {
        if (contactId <= 0L || value != null) return@produceState
        value = PhotoCache.load(ctx, contactId)
    }
    Box(Modifier.size(size).clip(CircleShape).background(Pal.Chip), contentAlignment = Alignment.Center) {
        val p = photo
        if (p != null) {
            Image(bitmap = p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (person) {
            Icon(Icons.Default.Person, null, tint = Pal.Gray, modifier = Modifier.size(size * 0.6f))
        } else {
            Text(
                initialOf(name).toString(), color = Pal.Ink.copy(alpha = 0.75f),
                fontSize = (size.value * 0.42f).sp
            )
        }
    }
}

/** Small grey tag such as "HD". */
@Composable
fun TagChip(text: String) {
    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(Pal.Chip).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text(text, color = Pal.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 11.sp)
    }
}

@Composable
fun VoicemailGlyph() {
    Canvas(Modifier.size(width = 14.dp, height = 7.dp)) {
        val r = 2.5.dp.toPx()
        val sw = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx())
        drawCircle(Pal.Gray, r, Offset(r + 1f, size.height / 2), style = sw)
        drawCircle(Pal.Gray, r, Offset(size.width - r - 1f, size.height / 2), style = sw)
        drawLine(Pal.Gray, Offset(r + 1f, size.height - 1f), Offset(size.width - r - 1f, size.height - 1f), 1.2.dp.toPx())
    }
}

/** The 3-3-3-1 dot grid used for the show / hide keypad buttons. */
@Composable
fun DotsGlyph(color: Color = Pal.Green) {
    Canvas(Modifier.size(width = 24.dp, height = 28.dp)) {
        val r = 2.6.dp.toPx()
        val xs = listOf(size.width * 0.1f, size.width * 0.5f, size.width * 0.9f)
        val ys = listOf(size.height * 0.08f, size.height * 0.36f, size.height * 0.64f, size.height * 0.92f)
        for (row in 0..2) for (x in xs) drawCircle(color, r, Offset(x, ys[row]))
        drawCircle(color, r, Offset(xs[1], ys[3]))
    }
}

@Composable
fun GreenFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(56.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Pal.Green).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Default.Add, "Add", tint = Color.White, modifier = Modifier.size(28.dp)) }
}

/** Bottom bar shown while items are selected. */
@Composable
fun DeleteBar(enabled: Boolean, onClick: () -> Unit) {
    val c = if (enabled) Pal.Ink else Pal.Chip
    Box(
        Modifier.fillMaxWidth().background(Pal.Bg).navigationBarsPadding()
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Delete, "Delete", tint = c, modifier = Modifier.size(24.dp))
            Text("Delete", color = c, fontSize = 12.sp)
        }
    }
}

@Composable
fun ConfirmDialog(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Pal.Sheet).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text, color = Pal.Ink, fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Pal.Red)
                    .clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center
            ) { Text(confirmLabel, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onDismiss) { Text("Cancel", color = Pal.Ink, fontSize = 16.sp) }
        }
    }
}

@Composable
fun PermissionPrompt(text: String, onRequest: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, color = Pal.Gray, textAlign = TextAlign.Center, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Allow access") }
        TextButton(onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            )
        }) { Text("Open app settings", color = Pal.Green) }
    }
}

/** Details popup: call, message, copy, save, plus recent calls with that number. */
@Composable
fun ActionDialog(name: String, number: String, history: List<LogEntry>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Sheet,
        titleContentColor = Pal.Ink,
        textContentColor = Pal.Ink,
        title = { Text(name.ifBlank { prettyNumber(number) }) },
        text = {
            Column {
                if (name.isNotBlank()) Text(prettyNumber(number), color = Pal.Gray)
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { sendSms(ctx, number) }) { Text("Message", color = Pal.Green) }
                    TextButton(onClick = { copyNumber(ctx, number) }) { Text("Copy", color = Pal.Green) }
                    if (name.isBlank()) TextButton(onClick = { addContactIntent(ctx, number) }) { Text("Save", color = Pal.Green) }
                }
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    history.take(6).forEach { h ->
                        Text(
                            "${typeLabel(h.type)} · ${formatFull(h.date)} · ${formatDuration(h.duration)}",
                            fontSize = 13.sp, color = Pal.Gray, modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { placeCall(ctx, number); onDismiss() }) { Text("Call", color = Pal.Green) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Pal.Gray) } }
    )
}

private class TabItem(val on: ImageVector, val off: ImageVector, val label: String)

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val items = remember {
        listOf(
            TabItem(Icons.Filled.Call, Icons.Outlined.Call, "Dial"),
            TabItem(Icons.Filled.Person, Icons.Outlined.Person, "Contacts"),
            TabItem(Icons.Filled.Star, Icons.Outlined.StarBorder, "Favorites")
        )
    }
    Column(Modifier.fillMaxWidth().background(Pal.Bg).navigationBarsPadding()) {
        HorizontalDivider(color = Pal.Divider, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().height(58.dp)) {
            items.forEachIndexed { i, item ->
                val selected = tab == i
                val c by animateColorAsState(if (selected) Pal.Green else Pal.Gray, tween(150), label = "tabColor")
                val s by animateFloatAsState(if (selected) 1.12f else 1f, tween(150), label = "tabScale")
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(if (selected) item.on else item.off, item.label, tint = c, modifier = Modifier.size(24.dp).scale(s))
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, fontSize = 11.sp, color = c, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

/** Keypad tones, honouring the system "dial pad tones" setting. */
class Dtmf(private val enabled: Boolean) {
    private val tg: ToneGenerator? =
        if (enabled) try { ToneGenerator(AudioManager.STREAM_DTMF, 70) } catch (e: Exception) { null } else null

    fun play(key: String) {
        val t = when (key) {
            "0" -> ToneGenerator.TONE_DTMF_0
            "1" -> ToneGenerator.TONE_DTMF_1
            "2" -> ToneGenerator.TONE_DTMF_2
            "3" -> ToneGenerator.TONE_DTMF_3
            "4" -> ToneGenerator.TONE_DTMF_4
            "5" -> ToneGenerator.TONE_DTMF_5
            "6" -> ToneGenerator.TONE_DTMF_6
            "7" -> ToneGenerator.TONE_DTMF_7
            "8" -> ToneGenerator.TONE_DTMF_8
            "9" -> ToneGenerator.TONE_DTMF_9
            "*" -> ToneGenerator.TONE_DTMF_S
            "#" -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        tg?.startTone(t, 120)
    }

    fun release() { tg?.release() }
}
