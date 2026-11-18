package com.example.quietdialer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
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
 * Records calls into Recordings/Quiet Dialer (Music/Quiet Dialer before Android 12).
 *
 * Main way: through Shizuku. The recording runs in Shizuku's shell process, which is allowed to
 * capture the call audio, so both voices are recorded (see RecorderUserService).
 *
 * Fallback: the microphone, which only picks up the other person when the call is on speaker.
 */
object CallRecorder {
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    /** True while the recording is running inside Shizuku. */
    var viaShizuku = false
        private set

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** @param useShizuku true = use Shizuku when it is ready; false = microphone only. */
    fun start(ctx: Context, who: String, useShizuku: Boolean = true): Boolean {
        if (_active.value) return true
        val app = ctx.applicationContext
        val shizuku = useShizuku && ShizukuRecorder.status(app) == ShizukuRecorder.Status.READY
        if (!shizuku && !hasPermission(app)) return false

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
        val fd = try { app.contentResolver.openFileDescriptor(u, "w") } catch (e: Exception) { null }
        if (fd == null) {
            try { app.contentResolver.delete(u, null, null) } catch (_: Exception) { }
            return false
        }
        uri = u
        pfd = fd

        if (shizuku) {
            viaShizuku = true
            _active.value = true
            ShizukuRecorder.start(app, fd) { res -> onShizukuResult(app, fd, res) }
            return true
        }
        return if (startMic(app, fd)) { _active.value = true; true } else { abort(app); false }
    }

    private fun onShizukuResult(app: Context, fd: ParcelFileDescriptor, res: String) {
        if (!_active.value || pfd !== fd || !viaShizuku) return // stopped in the meantime
        if (res.startsWith("OK")) {
            ShizukuRecorder.lastError = null
            val both = res.contains("VOICE_CALL")
            Toast.makeText(
                app, if (both) "Recording both sides" else "Recording (this phone could only give your side)",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        ShizukuRecorder.lastError = res.removePrefix("ERR").trim()
        viaShizuku = false
        if (hasPermission(app) && startMic(app, fd)) {
            Toast.makeText(app, "Shizuku recording failed, using the microphone instead", Toast.LENGTH_LONG).show()
        } else {
            abort(app)
            Toast.makeText(app, "Couldn't record. See Call recording in the menu for details", Toast.LENGTH_LONG).show()
        }
        CallService.instance?.refreshNotification()
    }

    private fun startMic(app: Context, fd: ParcelFileDescriptor): Boolean = try {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(96_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(fd.fileDescriptor)
        r.prepare()
        r.start()
        recorder = r
        true
    } catch (e: Exception) { false }

    /** Stops and saves. Safe to call when nothing is recording. */
    fun stop(ctx: Context) {
        if (!_active.value) return
        val app = ctx.applicationContext
        val u = uri
        var saved = true
        if (viaShizuku) saved = ShizukuRecorder.stop()
        else try { recorder?.stop() } catch (e: Exception) { saved = false }
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

    private fun abort(app: Context) {
        val u = uri
        if (viaShizuku) ShizukuRecorder.stop()
        cleanup()
        if (u != null) try { app.contentResolver.delete(u, null, null) } catch (_: Exception) { }
    }

    private fun cleanup() {
        try { recorder?.release() } catch (_: Exception) { }
        try { pfd?.close() } catch (_: Exception) { }
        recorder = null; pfd = null; uri = null
        viaShizuku = false
        _active.value = false
    }
}
