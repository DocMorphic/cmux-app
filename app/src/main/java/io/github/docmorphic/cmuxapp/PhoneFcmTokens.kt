package io.github.docmorphic.cmuxapp

import android.app.NotificationManager
import android.content.Context
import androidx.work.*
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Uses the pinned Firebase 25.0.1 token API. No implicit Firebase initialization or project discovery. */
private class InstalledPhoneFcmProvider(override val project: PhoneFcmProject) : PhoneFcmTokenProvider {
    private fun messaging() = FirebaseMessaging.getInstance().also { it.isAutoInitEnabled = false }
    override suspend fun token(): String = messaging().token.completed()
    override suspend fun delete() { messaging().deleteToken().completed() }

    // SDK Tasks cannot be cancelled. Wait for actual completion before releasing
    // the shared mutex, so a stale deletion cannot race a replacement getToken.
    private suspend fun <T> Task<T>.completed(): T = suspendCoroutine { continuation ->
        addOnCompleteListener(Executor { it.run() }) { task ->
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(IllegalStateException("Push provider operation failed"))
        }
    }
}

internal object PhoneFcmTokens {
    private const val NAME = "cmux.push.tokens"
    private const val PERIODIC = "cmux.push.tokens.refresh"
    private val serial = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observing = false
    private fun store(context: Context) = NativeCredentialStore(context, "phone_fcm_tokens")
    private fun allowed(context: Context) = NativeNotificationService.isEnabled(context) &&
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    private fun installed(context: Context): PhoneFcmProject? = FirebaseApp.getApps(context)
        .singleOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }?.options?.let {
            val project = it.projectId ?: return@let null
            val sender = it.gcmSenderId ?: return@let null
            runCatching { PhoneFcmProject(project, it.applicationId, sender) }.getOrNull()
        }

    /** Future helper-enrollment UI calls this only after explaining and accepting cloud push. */
    fun authorize(context: Context): PhoneFcmTokenGrant {
        val credentials = NativeCredentialStore(context)
        val project = checkNotNull(installed(context)) { "Push is not configured in this app" }
        check(allowed(context)) { "Allow notifications before enabling cloud push" }
        val login = checkNotNull(credentials.taskSession()) { "Sign in before enabling cloud push" }
        var owner: PhoneFcmTokenGrant? = null
        store(context).update { owner = PhoneFcmTokenState(it).authorize(login, project) }
        recover(context)
        return checkNotNull(owner)
    }
    fun revoke(context: Context) {
        val credentials = NativeCredentialStore(context)
        try { synchronized(credentials.accountStateLock) {
            val tokens = store(context)
            if (tokens.load()?.has(PhoneFcmTokenState.KEY) == true) tokens.update { PhoneFcmTokenState(it).revoke() }
            if (credentials.load() != null)
                credentials.update {
                    PhonePushHelperState(it).clear()
                    PhoneHelperEnrollmentState(it).retainForToken(null)
                }
        } } finally { recover(context) }
    }
    private fun retainHelpers(context: Context) {
        val credentials = NativeCredentialStore(context)
        synchronized(credentials.accountStateLock) {
            val account = credentials.load() ?: return
            if (!account.has(PhonePushHelperState.KEY) && !account.has(PhoneHelperEnrollmentState.KEY)) return
            // Re-read under the shared store lock: a delayed recovery must not retire a newer enrollment.
            val lifecycle = store(context).load()?.let(::PhoneFcmTokenState)
            lifecycle?.reconcile(credentials.taskSession(), allowed(context), installed(context))
            credentials.update {
                PhonePushHelperState(it).retainForToken(lifecycle?.grant)
                PhoneHelperEnrollmentState(it).retainForToken(lifecycle?.snapshot)
            }
        }
    }
    /** Snapshot for authenticated helper enrollment; consumers must recheck it before and after I/O. */
    fun snapshot(context: Context): PhoneFcmTokenSnapshot? {
        val value = store(context).load() ?: return null
        val state = PhoneFcmTokenState(value)
        state.reconcile(NativeCredentialStore(context).taskSession(), allowed(context), installed(context))
        return state.snapshot
    }
    fun onTokenChanged(context: Context): Job? {
        val tokens = store(context)
        if (tokens.load()?.has(PhoneFcmTokenState.KEY) != true) return null
        tokens.update { PhoneFcmTokenState(it).invalidate() }
        return recover(context)
    }
    @Synchronized fun observe(context: Context) {
        if (observing) return
        observing = true
        val app = context.applicationContext
        scope.launch {
            val credentials = NativeCredentialStore(app)
            credentials.revisions.map { runCatching { credentials.taskSession() }.getOrNull() }
                .distinctUntilChanged().collect { recover(app) }
        }
    }
    /** Startup/resume, boot, login changes, notification preferences and SDK callback converge here. */
    fun recover(context: Context): Job {
        val app = context.applicationContext
        return scope.launch {
            try {
                val tokens = store(app)
                if (tokens.load()?.has(PhoneFcmTokenState.KEY) != true) {
                    retainHelpers(app)
                    return@launch
                }
                var pending = false
                tokens.update { state ->
                    val lifecycle = PhoneFcmTokenState(state)
                    lifecycle.reconcile(NativeCredentialStore(app).taskSession(), allowed(app), installed(app))
                    pending = lifecycle.hasWork
                }
                retainHelpers(app)
                val manager = WorkManager.getInstance(app)
                if (!pending) { manager.cancelUniqueWork(PERIODIC); return@launch }
                val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                // A callback while a pass finishes must leave a successor, not be lost behind KEEP.
                manager.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<PhoneFcmTokenWorker>().setConstraints(constraints)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()).await()
                manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<PhoneFcmTokenWorker>(24, TimeUnit.HOURS)
                        .setConstraints(constraints).build()).await()
            } catch (_: Exception) { currentCoroutineContext().ensureActive() }
            finally { PhoneHelperEnrollments.recover(app) }
        }
    }
    suspend fun run(context: Context): Boolean = serial.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val tokens = store(context)
            val provider = installed(context)?.let(::InstalledPhoneFcmProvider)
            val retry = try { PhoneFcmTokenReconciler(
                transaction = { action -> tokens.update { action(PhoneFcmTokenState(it)) } },
                login = { NativeCredentialStore(context).taskSession() }, allowed = { allowed(context) }, provider = provider
            ).runPass() } finally { retainHelpers(context) }
            if (tokens.load()?.let { PhoneFcmTokenState(it).hasWork } != true)
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            retry
        }
    }
}

class PhoneFcmTokenWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (PhoneFcmTokens.run(applicationContext)) Result.retry() else Result.success()
    } catch (_: Exception) { currentCoroutineContext().ensureActive(); Result.retry() }
}
