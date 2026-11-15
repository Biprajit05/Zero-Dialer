package com.example.quietdialer

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** User's theme choice: Follow system (default), Light or Dark. Saved between launches. */
object ThemeSettings {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    var mode by mutableIntStateOf(SYSTEM)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) { mode = prefs(ctx).getInt("theme", SYSTEM) }

    fun save(ctx: Context, m: Int) {
        mode = m
        prefs(ctx).edit().putInt("theme", m).apply()
    }
}

/** UI state that lives until the app process is closed / the app is opened fresh. */
object DialUiState {
    /** The All / Missed switch: appears on the first scroll and then stays. */
    var filterRevealed by mutableStateOf(false)

    /** True while the Dial tab's keypad sheet is open (the bottom bar centres itself then). */
    var padOpen by mutableStateOf(false)
}

@Composable
fun ThemeDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Pal.Sheet).padding(vertical = 20.dp)
        ) {
            Text(
                "Theme", color = Pal.Ink, fontSize = 20.sp,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            listOf(
                ThemeSettings.LIGHT to "Light mode",
                ThemeSettings.DARK to "Dark mode",
                ThemeSettings.SYSTEM to "Follow system"
            ).forEach { (m, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(m) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .background(if (current == m) Pal.Green else Pal.Chip),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(if (current == m) 8.dp else 14.dp).clip(CircleShape).background(Pal.Sheet))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(label, color = Pal.Ink, fontSize = 17.sp)
                }
            }
        }
    }
}
