package com.example.quietdialer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the call with the microphone and saves it to Recordings/Quiet Dialer (Music/Quiet Dialer
 * before Android 12).
 *
 * Android does not let ordinary apps capture the other person's voice directly, so the far side is
 * only picked up when the call is on speaker. Your own voice is always recorded.
 */
object CallRecorder {
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: android.net.Uri? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context, who: String): Boolean {
        if (_active.value) return true
        if (!hasPermission(ctx)) return false
        val app = ctx.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = who.replace(Regex("[^A-Za-z0-9+ _-]"), "").trim().ifBlank { "call" }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "Call_${safe}_$stamp.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                if (Build.VERSION.SDK_INT >= 31) "Recordings/Quiet Dialer" else "Music/Quiet Dialer"
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val u = try {
            app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) { null } ?: return false
        return try {
            val fd = app.contentResolver.openFileDescriptor(u, "w") ?: error("no fd")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(fd.fileDescriptor)
            r.prepare()
            r.start()
            recorder = r; pfd = fd; uri = u
            _active.value = true
            true
        } catch (e: Exception) {
            try { app.contentResolver.delete(u, null, null) } catch (_: Exception) { }
            cleanup()
            false
        }
    }

    /** Stops and saves. Safe to call when nothing is recording. */
    fun stop(ctx: Context) {
        if (!_active.value) return
        val app = ctx.applicationContext
        val u = uri
        var saved = true
        try { recorder?.stop() } catch (e: Exception) { saved = false }
        cleanup()
        if (u != null) {
            try {
                if (saved) {
                    app.contentResolver.update(
                        u, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null
                    )
                } else app.contentResolver.delete(u, null, null)
            } catch (_: Exception) { }
        }
        Toast.makeText(
            app, if (saved) "Call recording saved" else "Recording was too short to save", Toast.LENGTH_SHORT
        ).show()
    }

    private fun cleanup() {
        try { recorder?.release() } catch (_: Exception) { }
        try { pfd?.close() } catch (_: Exception) { }
        recorder = null; pfd = null; uri = null
        _active.value = false
    }
}
