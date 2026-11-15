package com.example.quietdialer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay

/** Fake incoming call: rings with the same screen as a real call, but no real call is involved. */
object FakeCall {
    const val EXTRA_NAME = "fake_name"
    const val EXTRA_NUMBER = "fake_number"
    const val CH = "fake_calls"
    const val NOTIF_ID = 77

    fun intent(ctx: Context, name: String, number: String): Intent =
        Intent(ctx, FakeCallActivity::class.java)
            .putExtra(EXTRA_NAME, name).putExtra(EXTRA_NUMBER, number)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** delaySec = 0 rings right away; otherwise an alarm rings the fake call later (even if the phone is locked). */
    fun schedule(ctx: Context, name: String, number: String, delaySec: Int) {
        if (delaySec <= 0) { ctx.startActivity(intent(ctx, name, number)); return }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 7001,
            Intent(ctx, FakeCallReceiver::class.java).putExtra(EXTRA_NAME, name).putExtra(EXTRA_NUMBER, number),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + delaySec * 1000L
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }
}

class FakeCallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val name = i.getStringExtra(FakeCall.EXTRA_NAME) ?: ""
        val number = i.getStringExtra(FakeCall.EXTRA_NUMBER) ?: ""
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(FakeCall.CH, "Fake calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) }
        )
        val open = PendingIntent.getActivity(
            ctx, 7002, FakeCall.intent(ctx, name, number),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, FakeCall.CH)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(name.ifBlank { number })
            .setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(FakeCall.NOTIF_ID, n)
        try { ctx.startActivity(FakeCall.intent(ctx, name, number)) } catch (e: Exception) { }
    }
}

class FakeCallActivity : ComponentActivity() {
    private var ringtone: Ringtone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(NotificationManager::class.java).cancel(FakeCall.NOTIF_ID)
        val name = intent.getStringExtra(FakeCall.EXTRA_NAME) ?: ""
        val number = intent.getStringExtra(FakeCall.EXTRA_NUMBER) ?: ""
        startRinging()
        setContent {
            MaterialTheme {
                FakeCallScreen(name, number, onAnswer = { stopRinging() }, onEnd = { stopRinging(); finish() })
            }
        }
    }

    private fun startRinging() {
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                isLooping = true
                play()
            }
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        } catch (e: Exception) { }
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (e: Exception) { }
        ringtone = null
        try { getSystemService(Vibrator::class.java)?.cancel() } catch (e: Exception) { }
    }

    override fun onDestroy() { stopRinging(); super.onDestroy() }
}

@Composable
fun FakeCallScreen(name: String, number: String, onAnswer: () -> Unit, onEnd: () -> Unit) {
    var answered by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(answered) { if (answered) while (true) { delay(1000); seconds++ } }
    val title = name.ifBlank { number.ifBlank { "Unknown" } }

    GlassHost(Modifier.fillMaxSize()) {
        if (!answered) {
            val head by rememberEntrance(0, 650)
            val body by rememberEntrance(150, 750)
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(88.dp))
                Column(
                    Modifier.graphicsLayer { alpha = head; translationY = -28.dp.toPx() * (1f - head) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (name.isBlank()) FitTitle(title, 44.sp, FontWeight.Medium)
                    else Text(title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Incoming call...", color = Color.White.copy(alpha = 0.8f), fontSize = 24.sp, modifier = Modifier.padding(top = 6.dp))
                    if (name.isNotBlank() && number.isNotBlank()) {
                        Text(number, color = Color.White, fontSize = 26.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                SwipeAnswerControl(
                    onAnswer = { answered = true; onAnswer() }, onDecline = onEnd,
                    modifier = Modifier.graphicsLayer { alpha = body; translationY = 60.dp.toPx() * (1f - body) }
                )
                Spacer(Modifier.weight(1f))
                GlassPill("Quick message", null, true, Modifier.widthIn(min = 220.dp), onEnd)
                Spacer(Modifier.height(28.dp))
            }
        } else {
            val enter by rememberEntrance(0, 450)
            var muted by remember { mutableStateOf(false) }
            var spk by remember { mutableStateOf(false) }
            Column(
                Modifier.fillMaxSize().graphicsLayer { alpha = enter }.statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(72.dp))
                if (name.isBlank()) FitTitle(title, 40.sp, FontWeight.SemiBold)
                else Text(title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Ongoing Call", color = Color.White.copy(alpha = 0.8f), fontSize = 22.sp, modifier = Modifier.padding(top = 4.dp))
                Text("%02d:%02d".format(seconds / 60, seconds % 60), color = Color.White, fontSize = 24.sp, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    FakeBtn("Mute", if (muted) Icons.Default.MicOff else Icons.Default.Mic, muted) { muted = !muted }
                    FakeBtn("Speaker", Icons.Default.VolumeUp, spk) { spk = !spk }
                    FakeBtn("Keypad", Icons.Default.Dialpad, false) { }
                }
                Spacer(Modifier.height(36.dp))
                Box(
                    Modifier.size(76.dp).bounceClick(shape = CircleShape, pressedScale = 0.9f, onClick = onEnd)
                        .background(Brush.verticalGradient(listOf(Color(0xFFF0505A), Color(0xFFC62828)))),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.CallEnd, "End call", tint = Color.White, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun FakeBtn(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).bounceClick(shape = CircleShape, pressedScale = 0.88f, onClick = onClick).glass(CircleShape, if (active) 0.38f else 0f),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = if (active) Color(0xFF1F1F1F) else Color.White, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.95f), fontSize = 14.sp)
    }
}

/** The "Fake call" window opened from the menu: caller name, number and when it should ring. */
@Composable
fun FakeCallDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    val delays = listOf("Now" to 0, "10 sec" to 10, "30 sec" to 30, "1 min" to 60, "5 min" to 300)
    var pick by remember { mutableIntStateOf(1) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = Pal.Ink, unfocusedTextColor = Pal.Ink)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Sheet,
        title = { Text("Fake call", color = Pal.Ink) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Caller name") }, singleLine = true, colors = fieldColors)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    number, { number = it }, label = { Text("Number") }, singleLine = true, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Spacer(Modifier.height(16.dp))
                Text("Ring in", color = Pal.Ink, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    delays.forEachIndexed { i, d ->
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (pick == i) Pal.Green else Pal.Chip)
                                .clickable { pick = i }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) { Text(d.first, color = if (pick == i) Color.White else Pal.Ink, fontSize = 14.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                FakeCall.schedule(ctx, name.trim(), number.trim().ifBlank { "+1 234-567-8900" }, delays[pick].second)
                onDismiss()
            }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
