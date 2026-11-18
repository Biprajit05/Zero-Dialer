package com.example.quietdialer

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaRecorder
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.RequiresApi
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku process (shell identity), NOT inside the app. The shell is allowed to
 * capture the call audio (VOICE_CALL = both voices), which a normal app is not.
 *
 * The app passes in an open file; this service just records into it.
 */
class RecorderUserService : IRecorderService.Stub() {
    private var recorder: MediaRecorder? = null
    private var out: ParcelFileDescriptor? = null

    override fun destroy() {
        stopInternal()
        exitProcess(0)
    }

    override fun start(pfd: ParcelFileDescriptor?): String {
        if (pfd == null) return "ERR no output file"
        stopInternal()
        val notes = StringBuilder()
        val ctx = try { shellContext() } catch (t: Throwable) {
            notes.append("context: ${t.javaClass.simpleName} ${t.message}; ")
            null
        }
        // Both voices first; microphone as a last resort.
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
            MediaRecorder.AudioSource.MIC to "MIC"
        )
        for ((src, name) in sources) {
            for (rate in intArrayOf(44_100, 16_000)) {
                var r: MediaRecorder? = null
                try {
                    r = newRecorder(ctx)
                    r.setAudioSource(src)
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    r.setAudioEncodingBitRate(96_000)
                    r.setAudioSamplingRate(rate)
                    r.setOutputFile(pfd.fileDescriptor)
                    r.prepare()
                    r.start()
                    recorder = r
                    out = pfd
                    return "OK $name"
                } catch (t: Throwable) {
                    notes.append("$name@$rate: ${t.javaClass.simpleName} ${t.message}; ")
                    try { r?.release() } catch (_: Throwable) { }
                }
            }
        }
        try { pfd.close() } catch (_: Throwable) { }
        return "ERR $notes"
    }

    override fun stop(): Boolean = stopInternal()

    private fun stopInternal(): Boolean {
        val r = recorder ?: return false
        var ok = true
        try { r.stop() } catch (_: Throwable) { ok = false }
        try { r.release() } catch (_: Throwable) { }
        try { out?.close() } catch (_: Throwable) { }
        recorder = null
        out = null
        return ok
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(ctx: Context?): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31 && ctx != null) MediaRecorder(ctx) else MediaRecorder()

    /**
     * There is no app Context in the Shizuku process, so this builds one that says it belongs to
     * the shell package. Android checks that the package matches the process identity.
     */
    private fun shellContext(): Context {
        val atClass = Class.forName("android.app.ActivityThread")
        var at = atClass.getMethod("currentActivityThread").invoke(null)
        if (at == null) {
            try { if (Looper.getMainLooper() == null) Looper.prepareMainLooper() } catch (_: Throwable) { }
            at = atClass.getMethod("systemMain").invoke(null)
        }
        val sys = atClass.getMethod("getSystemContext").invoke(at) as Context
        return ShellContext(sys)
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        private val pkg = if (Process.myUid() == 0) "root" else "com.android.shell"

        override fun getPackageName(): String = pkg

        // Hidden Android method (not in the public SDK), so no "override" keyword on purpose.
        fun getOpPackageName(): String = pkg

        override fun getAttributionTag(): String? = null

        override fun getApplicationContext(): Context = this

        @RequiresApi(31)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(pkg).build()
    }
}
