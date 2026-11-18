package com.example.quietdialer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rikka.shizuku.Shizuku

/** App-side connection to Shizuku: status, permission, and the recorder service. */
object ShizukuRecorder {
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQ_CODE = 4242

    enum class Status { NOT_INSTALLED, NOT_RUNNING, OLD_VERSION, NO_PERMISSION, READY }

    /** Changes whenever Shizuku's state may have changed; screens read it to refresh themselves. */
    var tick by mutableIntStateOf(0)
        private set

    /** Last problem, shown in the Call recording dialog so it can be reported. */
    var lastError by mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private var service: IRecorderService? = null
    private var binding = false
    private var pendingStart: (() -> Unit)? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private fun bump() { main.post { tick++ } }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = if (binder != null && binder.pingBinder()) IRecorderService.Stub.asInterface(binder) else null
            val p = pendingStart
            pendingStart = null
            main.post { p?.invoke() }
            bump()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            bump()
        }
    }

    /** Safe to call many times. */
    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        val app = ctx.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky {
                bump()
                if (status(app) == Status.READY) ensureBound(app)
            }
            Shizuku.addBinderDeadListener { service = null; binding = false; bump() }
            Shizuku.addRequestPermissionResultListener { code, result ->
                if (code == REQ_CODE) {
                    val granted = result == PackageManager.PERMISSION_GRANTED
                    val cb = permissionCallback
                    permissionCallback = null
                    main.post { cb?.invoke(granted) }
                    if (granted) ensureBound(app)
                    bump()
                }
            }
        } catch (t: Throwable) {
            lastError = "Shizuku init: ${t.javaClass.simpleName} ${t.message}"
        }
    }

    fun status(ctx: Context): Status {
        init(ctx)
        val installed = try { ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true } catch (_: Exception) { false }
        return try {
            when {
                !Shizuku.pingBinder() -> if (installed) Status.NOT_RUNNING else Status.NOT_INSTALLED
                Shizuku.isPreV11() -> Status.OLD_VERSION
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Status.READY
                else -> Status.NO_PERMISSION
            }
        } catch (_: Throwable) {
            if (installed) Status.NOT_RUNNING else Status.NOT_INSTALLED
        }
    }

    /** Pops up Shizuku's "allow this app?" dialog. */
    fun requestPermission(onResult: ((Boolean) -> Unit)? = null) {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                onResult?.invoke(false)
                lastError = "Shizuku permission was denied. Open Shizuku > Authorized apps and allow Quiet Dialer."
                return
            }
            permissionCallback = onResult
            Shizuku.requestPermission(REQ_CODE)
        } catch (t: Throwable) {
            lastError = "Shizuku permission: ${t.javaClass.simpleName} ${t.message}"
            onResult?.invoke(false)
        }
    }

    /** Starts the recorder process inside Shizuku (takes a moment the first time). */
    fun ensureBound(ctx: Context) {
        if (service != null || binding) return
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, RecorderUserService::class.java.name))
                .daemon(false)
                .processNameSuffix("recorder")
                .debuggable(false)
                .version(1)
            binding = true
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            binding = false
            lastError = "Shizuku service: ${t.javaClass.simpleName} ${t.message}"
        }
    }

    /** [onDone] gets "OK <source>" or an error text, on the main thread. */
    fun start(ctx: Context, fd: ParcelFileDescriptor, onDone: (String) -> Unit) {
        val app = ctx.applicationContext
        val run = {
            val s = service
            Thread {
                val res = try { s?.start(fd) ?: "ERR Shizuku service not connected" }
                catch (t: Throwable) { "ERR ${t.javaClass.simpleName} ${t.message}" }
                main.post { onDone(res) }
            }.start()
        }
        if (service != null) { run(); return }
        pendingStart = run
        ensureBound(app)
        main.postDelayed({
            if (pendingStart === run) {
                pendingStart = null
                onDone("ERR Shizuku recorder service did not start in time")
            }
        }, 6000)
    }

    /** True when a file was written. */
    fun stop(): Boolean {
        pendingStart = null
        return try { service?.stop() ?: false } catch (_: Throwable) { false }
    }
}

/** Call-recording preferences. */
object RecordSettings {
    var autoRecord by mutableStateOf(false)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) { autoRecord = prefs(ctx).getBoolean("auto_record", false) }

    fun isAuto(ctx: Context) = prefs(ctx).getBoolean("auto_record", false)

    fun setAuto(ctx: Context, v: Boolean) {
        autoRecord = v
        prefs(ctx).edit().putBoolean("auto_record", v).apply()
    }
}
