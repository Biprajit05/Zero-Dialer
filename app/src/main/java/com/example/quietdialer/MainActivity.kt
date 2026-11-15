package com.example.quietdialer

import android.Manifest
import android.app.role.RoleManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val version = mutableIntStateOf(0)

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { version.intValue++ }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            version.intValue++
            requestDialerRole()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val initial = intent?.data?.schemeSpecificPart ?: ""
        ThemeSettings.load(this)
        if (savedInstanceState == null) DialUiState.filterRevealed = false // fresh open: hide the filter again
        setContent {
            val dark = when (ThemeSettings.mode) {
                ThemeSettings.LIGHT -> false
                ThemeSettings.DARK -> true
                else -> isSystemInDarkTheme()
            }
            Pal.dark = dark
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            val scheme = if (dark) darkColorScheme(
                primary = Pal.Green, onPrimary = Color.White,
                background = Pal.Bg, onBackground = Pal.Ink,
                surface = Pal.Sheet, onSurface = Pal.Ink,
                onSurfaceVariant = Pal.Gray
            ) else lightColorScheme(
                primary = Pal.Green, onPrimary = Color.White,
                background = Pal.Bg, onBackground = Pal.Ink,
                surface = Pal.Sheet, onSurface = Pal.Ink,
                onSurfaceVariant = Pal.Gray
            )
            MaterialTheme(colorScheme = scheme) {
                App(initial, version.intValue, { askPermissions() }, { requestDialerRole() })
            }
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
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
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

/** Full-screen pages that slide over the tabs. */
sealed interface Overlay {
    data class Detail(val id: Long) : Overlay
    data class NewContact(val number: String = "") : Overlay
    data class Number(val number: String) : Overlay
    object PickFavorite : Overlay
    object CallBackground : Overlay
}

@Composable
fun App(initialNumber: String, permsVersion: Int, onRequestPerms: () -> Unit, onRequestRole: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var number by rememberSaveable { mutableStateOf(initialNumber) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var contactsVersion by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var barHidden by remember { mutableStateOf(false) }

    LaunchedEffect(permsVersion, contactsVersion) {
        contacts = if (hasPerm(ctx, Manifest.permission.READ_CONTACTS))
            withContext(Dispatchers.IO) { loadContacts(ctx) } else emptyList()
    }

    val isDefault = remember(permsVersion) {
        val rm = ctx.getSystemService(RoleManager::class.java)
        !rm.isRoleAvailable(RoleManager.ROLE_DIALER) || rm.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    BackHandler(enabled = overlay != null) { overlay = null }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (!isDefault) {
                Row(
                    Modifier.fillMaxWidth().background(if (Pal.dark) Color(0xFF3A3220) else Color(0xFFFFF0C9)).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Phone isn't your default phone app, so calls open the phone's own screen.",
                        fontSize = 13.sp, color = Pal.Ink, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRequestRole) { Text("Set default", color = Pal.Green) }
                }
            }
            val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        // New tab slides in from the side it sits on, old one drifts away and fades.
                        val dir = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(240)) + slideInHorizontally(tween(280)) { (it / 12) * dir }) togetherWith
                            (fadeOut(tween(120)) + slideOutHorizontally(tween(280)) { -(it / 12) * dir })
                    },
                    label = "tabs"
                ) { t ->
                    when (t) {
                        0 -> DialTab(
                            number, { number = it }, contacts, permsVersion, onRequestPerms,
                            onNewContact = { overlay = Overlay.NewContact(it) },
                            onOpenInfo = { num ->
                                val k = numberKey(num)
                                val match = if (k.isNotEmpty()) contacts.firstOrNull { numberKey(it.number) == k } else null
                                overlay = if (match != null) Overlay.Detail(match.id) else Overlay.Number(num)
                            },
                            onOpenCallBg = { overlay = Overlay.CallBackground },
                            onBarHidden = { barHidden = it }
                        )
                        1 -> ContactsTab(
                            contacts, permsVersion, onRequestPerms,
                            onOpenContact = { overlay = Overlay.Detail(it.id) },
                            onNewContact = { overlay = Overlay.NewContact() },
                            onBarHidden = { barHidden = it },
                            onChanged = { contactsVersion++ }
                        )
                        else -> FavoritesTab(
                            contacts, permsVersion, onRequestPerms,
                            onOpenContact = { overlay = Overlay.Detail(it.id) },
                            onAddFavorite = { overlay = Overlay.PickFavorite }
                        )
                    }
                }
                // Floating nav pill (the green action button of each tab sits to its right).
                androidx.compose.animation.AnimatedVisibility(
                    visible = !barHidden && !imeOpen,
                    modifier = Modifier.align(Alignment.BottomStart),
                    enter = slideInVertically(tween(280)) { it } + fadeIn(tween(220)),
                    exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150))
                ) {
                    // On the Dial tab with the keypad open the bar glides to the centre of the screen
                    // (the green dots button has turned into the call button); otherwise it sits left.
                    val centred = tab == 0 && DialUiState.padOpen
                    val navSpec = tween<androidx.compose.ui.unit.Dp>(if (centred) 300 else 240, easing = FastOutSlowInEasing)
                    val navStart by animateDpAsState(if (centred) 64.dp else 16.dp, navSpec, label = "navStart")
                    val navEnd by animateDpAsState(if (centred) 64.dp else 90.dp, navSpec, label = "navEnd")
                    FloatingNav(
                        tab = tab, onTab = { tab = it },
                        modifier = Modifier.fillMaxWidth().padding(start = navStart, end = navEnd, bottom = 14.dp)
                    )
                }
            }
        }

        AnimatedContent(
            targetState = overlay,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (targetState != null)
                    (slideInHorizontally(tween(260)) { it } + fadeIn(tween(200))) togetherWith fadeOut(tween(200))
                else
                    fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(240)) { it } + fadeOut(tween(200)))
            },
            label = "overlay"
        ) { o ->
            if (o == null) {
                Box(Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
                    when (o) {
                        is Overlay.Detail -> ContactDetailScreen(
                            o.id, contacts, onBack = { overlay = null }, onChanged = { contactsVersion++ }
                        )
                        is Overlay.Number -> NumberDetailScreen(o.number, onBack = { overlay = null })
                        is Overlay.NewContact -> NewContactScreen(
                            o.number, onClose = { overlay = null },
                            onSaved = { contactsVersion++; overlay = null }
                        )
                        Overlay.CallBackground -> CallBackgroundScreen(onBack = { overlay = null })
                        Overlay.PickFavorite -> PickFavoriteScreen(
                            contacts, onBack = { overlay = null },
                            onPicked = { contactsVersion++; overlay = null }
                        )
                    }
                }
            }
        }
    }
}
