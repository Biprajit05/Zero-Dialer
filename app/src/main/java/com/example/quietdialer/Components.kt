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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.ui.graphics.graphicsLayer
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

/** Colours for the current theme (dark or light), always with green accents. */
object Pal {
    var dark by mutableStateOf(true)

    private fun c(d: Long, l: Long) = Color(if (dark) d else l)

    /** Page background (light grey in light mode). */
    val Bg get() = c(0xFF0B0B0C, 0xFFF4F4F4)
    /** Floating things that sit on the page: header buttons, search pill, nav bar, keypad. */
    val Raised get() = c(0xFF1F1F21, 0xFFFFFFFF)
    val Card get() = c(0xFF1C1C1E, 0xFFFFFFFF)
    val Sheet get() = c(0xFF2B2B2D, 0xFFFFFFFF)
    val Field get() = c(0xFF1F1F21, 0xFFFFFFFF)
    val Chip get() = c(0xFF3A3A3C, 0xFFE4E4E4)
    val Green get() = c(0xFF3FBF2E, 0xFF43B02A)
    val GreenDark get() = c(0xFF1D4A19, 0xFFC9F6BF)
    val Ink get() = c(0xFFFFFFFF, 0xFF121212)
    val Gray get() = c(0xFF9A9AA0, 0xFF666666)
    val Divider get() = c(0xFF2A2A2C, 0xFFD3D3D3)
    val Red get() = c(0xFFFF4B4B, 0xFFE5322D)
    val Missed get() = c(0xFFFF5A52, 0xFFD93025)
    /** Soft highlight behind a missed-call row. */
    val MissedBg get() = c(0xFF1F1717, 0xB3FFFFFF)
    /** Colours of the VoLTE / HD tags. */
    val Tag get() = c(0xFF3A3A3C, 0xFFBDBDBD)
    val TagText get() = c(0xFF9A9AA0, 0xFFF7F7F7)
    /** Keypad sheet: panel, key pills, grab handle, call button, small letters. */
    val PadBg get() = c(0xFF161618, 0xFFEAE9EE)
    val Key get() = c(0xFF2C2C2F, 0xFFF5F4F7)
    val Handle get() = c(0xFF5A5A5F, 0xFFAAA9AE)
    val CallGreen get() = c(0xFF00B518, 0xFF00C015)
    val KeyLetters get() = c(0xFF9A9AA0, 0xFF76757A)
    val Link get() = c(0xFF3D8BFF, 0xFF1A6DFF)
}

/** Big page title that shrinks to a small one once the list below has been scrolled. */
@Composable
fun CollapsingHeader(title: String, collapsed: Boolean, actions: @Composable RowScope.() -> Unit = {}) {
    val size by animateFloatAsState(if (collapsed) 24f else 38f, tween(220), label = "titleSize")
    val top by animateDpAsState(if (collapsed) 8.dp else 22.dp, tween(220), label = "titleTop")
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = top, bottom = 8.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title, fontSize = size.sp, fontWeight = FontWeight.Bold, color = Pal.Ink,
            maxLines = 1, modifier = Modifier.weight(1f)
        )
        actions()
    }
}

/** Round header button: a white chip with a soft shadow (or bare, with [plain]) that bounces when tapped. */
@Composable
fun HeaderIcon(onClick: () -> Unit, plain: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .bounceClick(shape = CircleShape, pressedScale = 0.86f, elevation = if (plain) 0.dp else 3.dp, onClick = onClick)
            .background(if (plain) Color.Transparent else Pal.Raised),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun SelectIcon() {
    Icon(Icons.Outlined.CheckCircle, "Select", tint = Pal.Ink, modifier = Modifier.size(26.dp))
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
    val pop by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "selectPop"
    )
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).border(2.dp, Pal.Chip, CircleShape))
        Box(
            Modifier.size(22.dp)
                .graphicsLayer { scaleX = pop; scaleY = pop; alpha = pop.coerceIn(0f, 1f) }
                .clip(CircleShape).background(Pal.Green),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
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
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp)
            .shadow(3.dp, CircleShape).background(Pal.Raised).padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, null, tint = Pal.Ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, color = Pal.Gray, fontSize = 18.sp, maxLines = 1)
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(color = Pal.Ink, fontSize = 18.sp),
                cursorBrush = SolidColor(Pal.Green),
                modifier = Modifier.fillMaxWidth().focusRequester(fr)
            )
        }
        if (query.isNotEmpty() || onClose != null) {
            Box(
                Modifier.size(32.dp)
                    .bounceClick(shape = CircleShape, pressedScale = 0.8f) { if (query.isNotEmpty()) onQuery("") else onClose?.invoke() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Close, "Clear", tint = Pal.Gray, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** Big green circle with the letter that starts each contact section. */
@Composable
fun SectionBadge(letter: Char) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Pal.Green),
        contentAlignment = Alignment.Center
    ) { Text(letter.toString(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
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
fun Avatar(name: String, size: Dp, contactId: Long = 0L, person: Boolean = false, icon: ImageVector? = null) {
    val ctx = LocalContext.current
    val photo by produceState<ImageBitmap?>(PhotoCache.get(contactId), contactId) {
        if (contactId <= 0L || value != null) return@produceState
        value = PhotoCache.load(ctx, contactId)
    }
    Box(Modifier.size(size).clip(CircleShape).background(Pal.Chip), contentAlignment = Alignment.Center) {
        val p = photo
        if (p != null) {
            Image(bitmap = p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (icon != null) {
            Icon(icon, null, tint = Pal.Gray, modifier = Modifier.size(size * 0.6f))
        } else if (person) {
            Icon(Icons.Default.Person, null, tint = Pal.Gray, modifier = Modifier.size(size * 0.6f))
        } else {
            Text(
                initialOf(name).toString(), color = Pal.Gray,
                fontSize = (size.value * 0.45f).sp
            )
        }
    }
}

/** Small grey tag such as "VoLTE" or "HD". */
@Composable
fun TagChip(text: String) {
    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(Pal.Tag).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text(text, color = Pal.TagText, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 11.sp)
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

/** Big round green action button that floats next to the navigation bar. */
@Composable
fun GreenFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {
        Icon(Icons.Default.Add, "Add", tint = Color.White, modifier = Modifier.size(34.dp))
    }
) {
    Box(
        modifier.size(62.dp)
            .bounceClick(shape = CircleShape, pressedScale = 0.88f, elevation = 8.dp, onClick = onClick)
            .background(Pal.Green),
        contentAlignment = Alignment.Center
    ) { icon() }
}

/** Bottom bar shown while items are selected. */
@Composable
fun DeleteBar(enabled: Boolean, onClick: () -> Unit) {
    val c by animateColorAsState(if (enabled) Pal.Ink else Pal.Chip, tween(150), label = "deleteColor")
    Box(
        Modifier.fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Pal.Raised)
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
                Modifier.fillMaxWidth().height(48.dp)
                    .bounceClick(shape = RoundedCornerShape(24.dp), pressedScale = 0.96f, onClick = onConfirm)
                    .background(Pal.Red),
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

private class TabItem(val icon: ImageVector, val label: String)

/** Floating white pill with Dial / Contacts / Favourites. A grey highlight slides to the chosen tab. */
@Composable
fun FloatingNav(tab: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = remember {
        listOf(
            TabItem(Icons.Filled.Call, "Dial"),
            TabItem(Icons.Filled.People, "Contacts"),
            TabItem(Icons.Filled.Star, "Favourites")
        )
    }
    val pill = RoundedCornerShape(34.dp)
    BoxWithConstraints(
        modifier.height(68.dp).shadow(10.dp, pill).background(Pal.Raised).padding(6.dp)
    ) {
        val tabW = maxWidth / 3
        val x by animateDpAsState(
            targetValue = tabW * tab,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "navHighlight"
        )
        Box(
            Modifier.offset(x = x).width(tabW).fillMaxHeight()
                .clip(RoundedCornerShape(28.dp)).background(Pal.Chip)
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val selected = tab == i
                val c by animateColorAsState(if (selected) Pal.Green else Pal.Gray, tween(180), label = "tabColor")
                val s by animateFloatAsState(
                    if (selected) 1.14f else 1f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    label = "tabScale"
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .bounceClick(shape = RoundedCornerShape(28.dp), pressedScale = 0.9f) { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(item.icon, item.label, tint = c, modifier = Modifier.size(26.dp).scale(s))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label, fontSize = 11.sp, color = c, maxLines = 1,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
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
