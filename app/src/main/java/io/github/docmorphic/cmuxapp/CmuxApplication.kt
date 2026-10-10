package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import java.io.File

internal object MobileDiagnostics {
    var elapsed: () -> Long = System::nanoTime
        private set
    @Volatile var recorder: DiagnosticRecorder? = null
        private set
    fun install(context: Context, role: DiagnosticRole) {
        if (recorder != null) return
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val files = DiagnosticFiles(File(context.noBackupFilesDir, "diagnostics"), File(context.cacheDir, "diagnostic-exports"),
            "${context.packageName} ${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)}) installed=${info.lastUpdateTime} Android=${android.os.Build.VERSION.SDK_INT}",
            debugVerbose = BuildConfig.DEBUG)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        check(boot >= 0) { "Boot identity unavailable" }
        elapsed = SystemClock::elapsedRealtimeNanos
        recorder = DiagnosticRecorder(files, boot, role,
            elapsed = SystemClock::elapsedRealtimeNanos, onClear = MobileDebugLog::clearThrough,
            exitHistory = { androidExitHistory(context.applicationContext) })
        recorder?.recoverExits()
        // Separate synchronous publication: a dying process cannot drain the coroutine writer.
        // Keep ART's original handler responsible for reporting/terminating the process.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous != null) {
            files.crashes.prepare()
            val pid = android.os.Process.myPid()
            val slot = "$role-$pid-${SystemClock.elapsedRealtimeNanos()}"
            val version = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
            Thread.setDefaultUncaughtExceptionHandler(DiagnosticCrashHandler(previous) { error ->
                files.crashes.write(DiagnosticCrash.capture(error, role, boot, SystemClock.elapsedRealtimeNanos(),
                    System.currentTimeMillis(), pid, version), slot)
            })
        }
    }
    fun event(operation: DebugOperation) { recorder?.record(operation, DebugOutcome.SUCCESS) }
}

/** Installs the recorder in both the ordinary app and the isolated browser presentation process. */
class CmuxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else
            getSystemService(android.app.ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName.orEmpty()
        runCatching { MobileDiagnostics.install(this, if (process.endsWith(":browser")) DiagnosticRole.BROWSER else DiagnosticRole.APP) }
        MobileDiagnostics.event(DebugOperation.APP_START)
        if (process == packageName) {
            NativePrivacyConsent.current(this)
            FileSaveWork.recover(this)
            PhoneFcmTokens.observe(this)
        }
        ArtifactPlaybackSessions.recover(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var visible = 0
            override fun onActivityStarted(activity: Activity) { if (visible++ == 0) {
                MobileDiagnostics.event(DebugOperation.APP_FOREGROUND)
                MobileDiagnostics.recorder?.recoverExits()
            } }
            override fun onActivityStopped(activity: Activity) { if (--visible == 0) MobileDiagnostics.event(DebugOperation.APP_BACKGROUND) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityDestroyed(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                if (process == packageName) PhoneFcmTokens.recover(this@CmuxApplication)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        })
    }
}
