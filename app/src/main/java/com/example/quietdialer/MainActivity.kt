package com.example.quietdialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val version = mutableIntStateOf(0)

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            version.intValue++
            requestDialerRole()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent?.data?.schemeSpecificPart ?: ""
        setContent { MaterialTheme { App(initial, version.intValue) } }

            val wanted = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) requestDialerRole() else permLauncher.launch(missing.toTypedArray())
    }

    private fun requestDialerRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }
}

data class LogEntry(val number: String, val name: String?, val type: Int, val date: Long, val duration: Long)

fun loadLog(ctx: Context): List<LogEntry> {
    val out = mutableListOf<LogEntry>()
    val cols = arrayOf(
        CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
        CallLog.Calls.DATE, CallLog.Calls.DURATION
    )
    ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null, "${CallLog.Calls.DATE} DESC")
        ?.use { c ->
            while (c.moveToNext() && out.size < 200) {
                out += LogEntry(c.getString(0) ?: "Unknown", c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4))
            }
        }
    return out
}

fun placeCall(ctx: Context, number: String) {
    if (number.isBlank()) return
    try {
        ctx.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts("tel", number, null), android.os.Bundle())
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "Phone permission needed", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun App(initialNumber: String, permsVersion: Int) {
    var tab by remember { mutableIntStateOf(0) }
    var number by remember { mutableStateOf(initialNumber) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Keypad") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Recents") })
        }
        if (tab == 0) Keypad(number, { number = it }) { placeCall(ctx, number) }
        else Recents(permsVersion) { placeCall(ctx, it) }
    }
}

@Composable
fun Keypad(number: String, onChange: (String) -> Unit, onCall: () -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number, fontSize = 34.sp)
        }
        Spacer(Modifier.height(16.dp))
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(76.dp).clip(CircleShape).clickable { onChange(number + k) },
                        contentAlignment = Alignment.Center
                    ) { Text(k, fontSize = 28.sp) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Spacer(Modifier.size(56.dp))
            FloatingActionButton(onClick = onCall, containerColor = Color(0xFF2E7D32), contentColor = Color.White) {
                Icon(Icons.Default.Call, contentDescription = "Call")
            }
            Box(
                Modifier.size(56.dp).clip(CircleShape).clickable { onChange(number.dropLast(1)) },
                contentAlignment = Alignment.Center
            ) { Text("⌫", fontSize = 24.sp) }
        }
    }
}

@Composable
fun Recents(permsVersion: Int, onCall: (String) -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<LogEntry>?>(null) }

    LaunchedEffect(permsVersion) {
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        entries = if (ok) withContext(Dispatchers.IO) { loadLog(ctx) } else emptyList()
    }

    val list = entries
    if (list == null || list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (list == null) "Loading…" else "No recent calls (or call log permission not granted)")
        }
        return
    }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list) { e ->
            val kind = when (e.type) {
                CallLog.Calls.INCOMING_TYPE -> "Incoming"
                CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                CallLog.Calls.MISSED_TYPE -> "Missed"
                CallLog.Calls.REJECTED_TYPE -> "Declined"
                else -> "Call"
            }
            Column(Modifier.fillMaxWidth().clickable { onCall(e.number) }.padding(16.dp)) {
                Text(
                    e.name?.takeIf { it.isNotBlank() } ?: e.number, fontSize = 18.sp,
                    color = if (e.type == CallLog.Calls.MISSED_TYPE) Color(0xFFC62828) else Color.Unspecified
                )
                Text("$kind · ${fmt.format(Date(e.date))} · ${e.duration}s", fontSize = 13.sp)
            }
            HorizontalDivider()
        }
    }
}
