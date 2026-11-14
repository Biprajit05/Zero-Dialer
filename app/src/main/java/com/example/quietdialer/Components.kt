package com.example.quietdialer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Pal {
    val Green = Color(0xFF00C257)
    val Ink = Color(0xFF1B1B1B)
    val Gray = Color(0xFF8A8A8A)
    val Divider = Color(0xFFE4E4E4)
    val Letters = Color(0xFF7FA98D)
    val Missed = Color(0xFFD93025)
    val ChipBg = Color(0xFFBDBDBD)
    val Soft = Color(0xFFF1F1F1)
    val BackspaceBg = Color(0xFFFFF1CF)
    val BackspaceX = Color(0xFFD7A33A)
}

/** Large page title with action icons on the right (like "Dial" in the reference). */
@Composable
fun Header(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Pal.Ink,
            maxLines = 1, modifier = Modifier.weight(1f)
        )
        actions()
    }
}

@Composable
fun HeaderIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** The rounded-square "select" icon from the reference header. */
@Composable
fun SelectGlyph() {
    Box(
        Modifier.size(24.dp).border(2.dp, Pal.Ink, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Default.Check, null, tint = Pal.Ink, modifier = Modifier.size(16.dp)) }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, hint: String) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text(hint) },
        trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close search") } },
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Pal.Green, unfocusedBorderColor = Pal.Divider, cursorColor = Pal.Green
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(fr)
    )
}

@Composable
fun Avatar(name: String, size: Dp) {
    val palette = listOf(
        Color(0xFF5C6BC0), Color(0xFF26A69A), Color(0xFFEF6C00),
        Color(0xFF8E24AA), Color(0xFF43A047), Color(0xFFD81B60)
    )
    val bg = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(initialOf(name).toString(), color = Color.White, fontSize = (size.value * 0.42f).sp)
    }
}

/** Small grey tag such as "HD". */
@Composable
fun TagChip(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(Pal.ChipBg).padding(horizontal = 6.dp, vertical = 1.dp)
    ) { Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun SelectDot(checked: Boolean) {
    if (checked) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Pal.Green), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    } else {
        Box(Modifier.size(24.dp).border(2.dp, Pal.ChipBg, CircleShape))
    }
}

/** Small phone with an arrow, shown beside outgoing calls. */
@Composable
fun OutgoingGlyph() {
    Box(Modifier.size(26.dp)) {
        Icon(
            Icons.Default.Call, null, tint = Pal.ChipBg,
            modifier = Modifier.size(22.dp).align(Alignment.BottomStart)
        )
        Text("↗", fontSize = 12.sp, color = Pal.ChipBg, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
fun VoicemailGlyph() {
    Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
        val r = 3.dp.toPx()
        val sw = Stroke(width = 1.4.dp.toPx())
        drawCircle(Pal.Letters, r, Offset(r + 1f, size.height / 2), style = sw)
        drawCircle(Pal.Letters, r, Offset(size.width - r - 1f, size.height / 2), style = sw)
        drawLine(Pal.Letters, Offset(r + 1f, size.height - 1f), Offset(size.width - r - 1f, size.height - 1f), 1.4.dp.toPx())
    }
}

/** The 4-row dot grid used for the "hide/show keypad" button. */
@Composable
fun DotsGlyph() {
    Canvas(Modifier.size(width = 28.dp, height = 34.dp)) {
        val r = 2.8.dp.toPx()
        val xs = listOf(size.width * 0.1f, size.width * 0.5f, size.width * 0.9f)
        val ys = listOf(size.height * 0.08f, size.height * 0.36f, size.height * 0.64f, size.height * 0.92f)
        for (row in 0..2) for (x in xs) drawCircle(Pal.Ink, r, Offset(x, ys[row]))
        drawCircle(Pal.Ink, r, Offset(xs[1], ys[3]))
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
        }) { Text("Open app settings") }
    }
}

/** Details popup: call, message, copy, save, plus recent calls with that number. */
@Composable
fun ActionDialog(name: String, number: String, history: List<LogEntry>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(name.ifBlank { prettyNumber(number) }) },
        text = {
            Column {
                if (name.isNotBlank()) Text(prettyNumber(number), color = Pal.Gray)
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { sendSms(ctx, number) }) { Text("Message") }
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("number", number))
                        Toast.makeText(ctx, "Number copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                    if (name.isBlank()) TextButton(onClick = { addContactIntent(ctx, number) }) { Text("Save") }
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
        confirmButton = { TextButton(onClick = { placeCall(ctx, number); onDismiss() }) { Text("Call") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding()) {
        HorizontalDivider(color = Pal.Divider)
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            listOf(
                Icons.Default.Call to "Dial",
                Icons.Default.Person to "Contacts",
                Icons.Default.Star to "Favorites"
            ).forEachIndexed { i, (icon, label) ->
                val c = if (tab == i) Pal.Green else Pal.Gray
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, label, tint = c, modifier = Modifier.size(26.dp))
                    Text(label, fontSize = 14.sp, color = c, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal)
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
