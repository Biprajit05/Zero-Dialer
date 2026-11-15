package com.example.quietdialer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Which background the call screens use (a preset id, or "photo" for the user's own picture). */
object CallBg {
    private const val PREFS = "call_bg"
    private val _choice = MutableStateFlow("blue")
    val choice: StateFlow<String> = _choice
    private val _photoVersion = MutableStateFlow(0)
    val photoVersion: StateFlow<Int> = _photoVersion
    private var loaded = false

    fun photoFile(ctx: Context) = File(ctx.filesDir, "call_bg.jpg")

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("choice", "blue") ?: "blue"
        _choice.value = if (saved == "photo" && !photoFile(ctx).exists()) "blue" else saved
    }

    fun select(ctx: Context, id: String) {
        init(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("choice", id).apply()
        _choice.value = id
    }

    /** Copies a picked / photographed image into the app (scaled down) and selects it. */
    suspend fun setPhoto(ctx: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val biggest = maxOf(info.size.width, info.size.height)
                if (biggest > 1600) dec.setTargetSampleSize(Math.ceil(biggest / 1600.0).toInt())
            }
            FileOutputStream(photoFile(ctx)).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            _photoVersion.value = _photoVersion.value + 1
            select(ctx, "photo")
            true
        } catch (e: Exception) { false }
    }
}

class BgPreset(val id: String, val label: String, val draw: DrawScope.() -> Unit)

val BG_PRESETS: List<BgPreset> = listOf(
    BgPreset("blue", "Blue") {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0C5E), Color(0xFF0B2A9E), Color(0xFF0A53D0), Color(0xFF061A7A))))
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x990A9CF0), Color.Transparent),
                center = Offset(size.width * 0.25f, size.height * 0.62f), radius = size.width * 0.95f
            )
        )
    },
    BgPreset("colorful", "Colorful") {
        drawRect(Color(0xFF1A1A1C))
        val r = size.width * 0.42f
        val cx = size.width / 2f
        fun blob(c: Color, y: Float) = drawCircle(
            Brush.radialGradient(listOf(c, c, c.copy(alpha = 0f)), center = Offset(cx, y), radius = r),
            r, Offset(cx, y)
        )
        blob(Color(0xFFD0788F), size.height * 0.34f)
        blob(Color(0xFFD5914A), size.height * 0.48f)
        blob(Color(0xFF5E4FC3), size.height * 0.62f)
    },
    BgPreset("dark", "Dark") {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF2B2B2D), Color(0xFF050505))))
    },
    BgPreset("green", "Green") {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF03281A), Color(0xFF0B7A48))))
    },
    BgPreset("sunset", "Sunset") {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1058), Color(0xFFC2336B), Color(0xFFFF9A5C))))
    }
)

/** Fills its space with the background the user picked for calls. */
@Composable
fun CallBackdrop(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { CallBg.init(ctx) }
    val choice by CallBg.choice.collectAsState()
    BackdropFor(choice, modifier)
}

@Composable
fun BackdropFor(id: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (id == "photo") {
        val ver by CallBg.photoVersion.collectAsState()
        val bmp by produceState<ImageBitmap?>(null, ver) {
            value = withContext(Dispatchers.IO) {
                try { BitmapFactory.decodeFile(CallBg.photoFile(ctx).path)?.asImageBitmap() } catch (e: Exception) { null }
            }
        }
        Box(modifier.background(Color(0xFF111111))) {
            bmp?.let { b -> Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            // Keeps white text readable on bright photos.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
    } else {
        val p = BG_PRESETS.firstOrNull { it.id == id } ?: BG_PRESETS[0]
        Canvas(modifier) { p.draw(this) }
    }
}

// ---------------------------------------------------------------- settings screen

@Composable
fun CallBackgroundScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { CallBg.init(ctx) }
    val choice by CallBg.choice.collectAsState()
    val ver by CallBg.photoVersion.collectAsState()
    val hasPhoto = remember(choice, ver) { CallBg.photoFile(ctx).exists() }
    var sheet by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }

    fun failed() = Toast.makeText(ctx, "Couldn't use that picture", Toast.LENGTH_SHORT).show()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { if (!CallBg.setPhoto(ctx, uri)) failed() }
    }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pending
        pending = null
        if (u != null) scope.launch {
            if (ok && !CallBg.setPhoto(ctx, u)) failed()
            withContext(Dispatchers.IO) { try { ctx.contentResolver.delete(u, null, null) } catch (e: Exception) { } }
        }
    }

    BackHandler(enabled = sheet) { sheet = false }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderIcon(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Pal.Ink) }
                Text("Call background", color = Pal.Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }

            // Phone-shaped preview of the current choice.
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxHeight().aspectRatio(9f / 16f).clip(RoundedCornerShape(26.dp))
                        .border(2.dp, Pal.Chip, RoundedCornerShape(26.dp))
                ) {
                    BackdropFor(choice, Modifier.fillMaxSize())
                    Column(Modifier.fillMaxSize().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Name", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Text("00:12", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth().padding(bottom = 28.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            repeat(3) { Box(Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f))) }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BG_PRESETS.forEach { p ->
                    Thumb(p.label, choice == p.id, onClick = { CallBg.select(ctx, p.id) }) {
                        BackdropFor(p.id, Modifier.fillMaxSize())
                    }
                }
                if (hasPhoto) {
                    Thumb("Photo", choice == "photo", onClick = { CallBg.select(ctx, "photo") }) {
                        BackdropFor("photo", Modifier.fillMaxSize())
                    }
                }
                Thumb("", false, onClick = { sheet = true }) {
                    Box(Modifier.fillMaxSize().background(Color(0xFFF2F2F2)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, "Add background", tint = Color(0xFF555555))
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }

        if (sheet) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { sheet = false }) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Pal.Sheet)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .navigationBarsPadding().padding(bottom = 12.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Add background", color = Pal.Ink, fontSize = 17.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        HeaderIcon(onClick = { sheet = false }) { Icon(Icons.Default.Close, "Close", tint = Pal.Ink) }
                    }
                    Text(
                        "Select photo", color = Pal.Ink, fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().clickable { sheet = false; pick.launch("image/*") }
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                    Text(
                        "Take photo", color = Pal.Ink, fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            sheet = false
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, "call_bg_${System.currentTimeMillis()}.jpg")
                                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            }
                            val u = try { ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) } catch (e: Exception) { null }
                            if (u != null) {
                                pending = u
                                try { shoot.launch(u) } catch (e: Exception) {
                                    Toast.makeText(ctx, "No camera app found", Toast.LENGTH_SHORT).show()
                                }
                            } else Toast.makeText(ctx, "Couldn't open the camera", Toast.LENGTH_SHORT).show()
                        }.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumb(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
        Box(
            Modifier.size(width = 56.dp, height = 100.dp).clip(RoundedCornerShape(10.dp))
                .border(if (selected) 2.dp else 0.dp, if (selected) Pal.Green else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
        ) { content() }
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (selected) Pal.Ink else Pal.Gray, fontSize = 11.sp, maxLines = 1)
    }
}

