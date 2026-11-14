package com.example.quietdialer

import android.Manifest
import android.app.role.RoleManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        val initial = intent?.data?.schemeSpecificPart ?: ""
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Pal.Green, onPrimary = Color.White,
                    background = Color.White, surface = Color.White
                )
            ) { App(initial, version.intValue) { askPermissions() } }
        }
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        version.intValue++ // pick up permission/contact changes made outside the app
    }

    private fun askPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { !hasPerm(this, it) }
        if (missing.isEmpty()) requestDialerRole() else permLauncher.launch(missing.toTypedArray())
    }

    private fun requestDialerRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }
}

@Composable
fun App(initialNumber: String, permsVersion: Int, onRequestPerms: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var number by rememberSaveable { mutableStateOf(initialNumber) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }

    LaunchedEffect(permsVersion) {
        contacts = if (hasPerm(ctx, Manifest.permission.READ_CONTACTS))
            withContext(Dispatchers.IO) { loadContacts(ctx) } else emptyList()
    }

    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> DialTab(number, { number = it }, contacts, permsVersion, onRequestPerms)
                1 -> ContactsTab(contacts, permsVersion, onRequestPerms)
                else -> FavoritesTab(contacts, permsVersion, onRequestPerms)
            }
        }
        BottomBar(tab) { tab = it }
    }
}
