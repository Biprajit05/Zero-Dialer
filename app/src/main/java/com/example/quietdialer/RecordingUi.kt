package com.example.quietdialer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Menu > Call recording: Shizuku status, permission button and the "record all calls" switch. */
@Composable
fun RecordingDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val refresh = ShizukuRecorder.tick // re-read the status whenever Shizuku changes
    val st = ShizukuRecorder.status(ctx)
    val ready = st == ShizukuRecorder.Status.READY

    val (statusText, dot) = when (st) {
        ShizukuRecorder.Status.READY -> "Shizuku is ready" to Pal.Green
        ShizukuRecorder.Status.NO_PERMISSION -> "Shizuku is running. Quiet Dialer needs your permission" to Color(0xFFF5A623)
        ShizukuRecorder.Status.NOT_RUNNING -> "Shizuku is installed but not running" to Pal.Red
        ShizukuRecorder.Status.OLD_VERSION -> "Shizuku is too old. Please update it" to Pal.Red
        ShizukuRecorder.Status.NOT_INSTALLED -> "Shizuku is not installed" to Pal.Red
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Pal.Sheet)
                .verticalScroll(rememberScrollState()).padding(vertical = 22.dp, horizontal = 24.dp)
        ) {
            Text("Call recording", color = Pal.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(10.dp))
                Text(statusText, color = Pal.Ink, fontSize = 15.sp)
            }

            when (st) {
                ShizukuRecorder.Status.NO_PERMISSION ->
                    ActionButton("Allow Quiet Dialer in Shizuku") { ShizukuRecorder.requestPermission() }
                ShizukuRecorder.Status.NOT_RUNNING, ShizukuRecorder.Status.OLD_VERSION ->
                    ActionButton("Open Shizuku") {
                        val i = ctx.packageManager.getLaunchIntentForPackage(ShizukuRecorder.SHIZUKU_PKG)
                        try { if (i != null) ctx.startActivity(i) } catch (_: Exception) { }
                    }
                ShizukuRecorder.Status.NOT_INSTALLED ->
                    ActionButton("Get Shizuku") {
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) { }
                    }
                ShizukuRecorder.Status.READY -> {}
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Record all calls", color = Pal.Ink, fontSize = 17.sp)
                    Text("Starts when a call is answered", color = Pal.Gray, fontSize = 13.sp)
                }
                Switch(
                    checked = RecordSettings.autoRecord,
                    onCheckedChange = { RecordSettings.setAuto(ctx, it) },
                    enabled = ready,
                    colors = SwitchDefaults.colors(checkedTrackColor = Pal.Green, checkedThumbColor = Color.White)
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "You can also tap Record during any call. Recordings are saved in Recordings/Quiet Dialer. " +
                    "Recording goes through Shizuku because Android doesn't let normal apps capture call audio. " +
                    "Some phones only allow your own voice.",
                color = Pal.Gray, fontSize = 13.sp, lineHeight = 18.sp
            )

            ShizukuRecorder.lastError?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text("Last problem:", color = Pal.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(err, color = Pal.Red, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.fillMaxWidth().height(46.dp)
            .bounceClick(shape = RoundedCornerShape(23.dp), pressedScale = 0.96f, onClick = onClick)
            .background(Pal.Green),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}
