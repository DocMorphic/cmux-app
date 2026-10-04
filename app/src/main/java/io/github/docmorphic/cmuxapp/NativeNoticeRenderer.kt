package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Looper
import android.os.Handler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Main-thread, process-owned engine. No activity, account token or persistent session is retained. */
internal class NativeNoticeEngine private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtime = GeckoRuntime.create(context.applicationContext, GeckoRuntimeSettings.Builder()
        .configFilePath("").remoteDebuggingEnabled(false).consoleOutput(false).debugLogging(false)
        .loginAutofillEnabled(false).aboutConfigEnabled(false).build())
    private val ready = MutableStateFlow<WebExtension.Port?>(null)
    private var used = false
    private var port: WebExtension.Port? = null
    private var serial = 0
    private var generation = 0L
    private var failed = false
    private var needsRestart = false
    private var initialized: Deferred<Unit>? = null
    private var extension: WebExtension? = null
    private var setupStage = "idle"
    var failureCode: String = ""
        private set
    private val pending = mutableMapOf<Int, CompletableDeferred<JSONObject>>()
    private val pages = mutableSetOf<NativeNoticeRenderer>()
    private class Receipt(val contextId: String, val attributes: JSONObject)
    // Acquisition receipts survive extension restarts, but never leave native process memory.
    private val receipts = mutableMapOf<String, Receipt>()
    private val retiring = mutableSetOf<String>()

    private fun initialization(): Deferred<Unit> {
        initialized?.let { return it }
        val ticket = ++generation
        failed = false; used = false
        return scope.async(start = CoroutineStart.LAZY) {
            try {
                withTimeout(10_000) {
                    setupStage = "install"
                    var extension = runtime.webExtensionController.ensureBuiltIn(
                        "resource://android/assets/notice-session/", EXTENSION).awaitNotice()
                    // Restart only this bundled extension. Never restart GeckoRuntime or clear
                    // another profile. Old private tabs were closed by invalidate().
                    if (needsRestart) {
                        setupStage = "disable"
                        extension = runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, false).awaitNotice()
                    }
                    setupStage = "enable"
                    extension = runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, true).awaitNotice()
                    this@NativeNoticeEngine.extension = extension
                    setupStage = "port"
                    extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                        override fun onConnect(candidate: WebExtension.Port) {
                            val sender = candidate.sender
                            if (failed || ticket != generation || (used && port != null) || sender.session != null ||
                                sender.environmentType != WebExtension.MessageSender.ENV_TYPE_EXTENSION ||
                                sender.webExtension.id != EXTENSION) { candidate.disconnect(); return }
                            val previous = port
                            port = candidate; ready.value = null
                            previous?.disconnect()
                            candidate.setDelegate(object : WebExtension.PortDelegate {
                                override fun onPortMessage(message: Any, source: WebExtension.Port) {
                                    if (source !== port || ticket != generation || failed || message !is JSONObject) return
                                    if (message.optBoolean("ready")) ready.value = source
                                    else pending.remove(message.optInt("id", -1))?.complete(message)
                                }
                                override fun onDisconnect(source: WebExtension.Port) {
                                    if (source === port && ticket == generation) {
                                        ready.value = null; port = null
                                        if (used) {
                                            // Gecko calls this delegate BEFORE shutting down the port's
                                            // native dispatcher. Closing private sessions reentrantly can
                                            // destroy that dispatcher first (NullHandle on shutdown).
                                            // Revoke the channel now; retire sessions on the next UI turn.
                                            pages.toList().forEach { it.revokeConnection() }
                                            mainHandler.post { if (ticket == generation) invalidate() }
                                        }
                                    }
                                }
                            })
                        }
                    }, "cmux_notice_session")
                    ready.first { it != null }
                    // A restarted extension has lost its lease map. Only receipts acquired
                    // from exact owned blanks can authorize this narrowly scoped cleanup.
                    setupStage = "cleanup"
                    for (lease in retiring.toList()) {
                        val receipt = receipts[lease] ?: continue
                        send("retire", lease, receipt = receipt.attributes)
                        runtime.storageController.clearDataForSessionContext(receipt.contextId)
                        receipts.remove(lease); retiring.remove(lease)
                    }
                    check(ticket == generation && !failed)
                    needsRestart = false; setupStage = "ready"
                }
            } catch (e: Exception) {
                if (ticket == generation) {
                    failureCode = "setup-$setupStage-" + if (e is TimeoutCancellationException) "timeout" else "failed"
                    invalidate()
                }
                throw e
            }
        }.also { initialized = it; it.start() }
    }
    private fun invalidate() {
        if (failed) return
        failed = true; needsRestart = true; generation++
        val previous = port
        // With no delegate, Gecko queues a restarted background's connect event until
        // the new generation installs its delegate. An old delegate would reject it.
        extension?.setMessageDelegate(null, "cmux_notice_session")
        port = null; ready.value = null
        val oldInitialization = initialized; initialized = null
        oldInitialization?.cancel()
        val failure = IllegalStateException("Private notice engine unavailable")
        pending.values.toList().forEach { it.completeExceptionally(failure) }; pending.clear()
        // Closing every old page prevents late exchanges or seed replies from reviving it.
        pages.toList().forEach { it.engineFailed() }
        previous?.disconnect()
    }
    suspend fun prepare(page: NativeNoticeRenderer): GeckoRuntime {
        checkMain()
        val setup = initialization()
        val ticket = generation
        try {
            setup.await()
            currentCoroutineContext().ensureActive()
            withTimeout(10_000) { ready.first { it != null } }
            check(!failed && ticket == generation) { "Private notice engine unavailable" }
            used = true; pages += page
            return runtime
        } catch (e: Exception) {
            // A canceled presentation must not poison a healthy shared engine.
            if (ticket == generation && (e !is CancellationException ||
                    (e is TimeoutCancellationException && currentCoroutineContext().isActive))) invalidate()
            throw e
        }
    }
    suspend fun acquire(lease: String, contextId: String) {
        val result = send("acquire", lease)
        val attributes = result.getJSONObject("receipt")
        check(attributes.getInt("privateBrowsingId") == 1 && attributes.getInt("userContextId") >= 0 &&
            attributes.getString("geckoViewSessionContextId").length in 1..1024) { "Invalid private scope" }
        receipts[lease] = Receipt(contextId, attributes)
    }
    suspend fun command(op: String, lease: String, url: String? = null, cookies: List<Cookie> = emptyList()) {
        send(op, lease, url, cookies)
    }
    private suspend fun send(op: String, lease: String, url: String? = null,
        cookies: List<Cookie> = emptyList(), receipt: JSONObject? = null): JSONObject {
        checkMain(); check(!failed)
        val channel = checkNotNull(ready.value) { "Private notice connection unavailable" }
        val ticket = generation
        val id = ++serial
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            val data = JSONObject().put("id", id).put("op", op).put("lease", lease)
            if (url != null) data.put("url", url)
            if (receipt != null) data.put("receipt", receipt)
            if (op == "seed") data.put("cookies", JSONArray().apply { cookies.forEach { c ->
                put(JSONObject().put("domain", c.domain).put("path", c.path).put("name", c.name)
                    .put("value", c.value).put("secure", c.secure).put("httpOnly", c.httpOnly)
                    .put("hostOnly", c.hostOnly).put("expires", c.expiresAt))
            } })
            channel.postMessage(data)
            val result = withTimeout(5_000) { reply.await() }
            check(ticket == generation && channel === port && !failed) { "Private notice connection changed" }
            val reported = result.optString("failureCode")
            reported.takeIf { it.matches(Regex("cookie-validation-[0-9]+|cookie-not-stored|cookie-api-threw|cookie-api-no-result|cookie-readback-failed|operation-failed")) }?.let { failureCode = it }
            check(result.optBoolean("done") && !result.optBoolean("failed")) { "Private notice operation failed" }
            return result
        } finally { pending.remove(id) }
    }
    fun theme(dark: Boolean) {
        checkMain()
        runtime.settings.preferredColorScheme = if (dark) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
    }
    fun retire(page: NativeNoticeRenderer, contextId: String, lease: String, acquired: Boolean): Deferred<Boolean> {
        checkMain(); pages -= page
        val ticket = generation
        val canClear = !failed && ready.value != null
        if (receipts.containsKey(lease)) retiring += lease
        // This scope outlives presentation cancellation. Session.close() happens before this call.
        return scope.async {
            var cleared = !acquired
            if (acquired && canClear) try {
                send("clear", lease)
                receipts.remove(lease); retiring.remove(lease); cleared = true
            } catch (_: Exception) {
                if (ticket == generation) invalidate()
            }
            runtime.storageController.clearDataForSessionContext(contextId)
            cleared
        }
    }
    companion object {
        private const val EXTENSION = "notice-session@cmux-app.invalid"
        private var instance: NativeNoticeEngine? = null
        fun get(context: Context): NativeNoticeEngine {
            checkMain()
            return instance ?: NativeNoticeEngine(context).also { instance = it }
        }
        internal fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Notice engine requires main thread" }
    }
}

/** A single private context; owns exchange, navigation policy, timeout and asynchronous retirement. */
internal class NativeNoticeRenderer(
    context: Context, parent: CoroutineScope, private val policy: WhatsNewWebPolicy, val url: String,
    dark: Boolean, deadlineMillis: Long, private val currentOwner: () -> Boolean,
    cookies: suspend (String) -> List<Cookie>
) : NativeWhatsNewPreloadedPage {
    private val application = context.applicationContext
    private var engine: NativeNoticeEngine? = null
    private val lease = UUID.randomUUID().toString()
    private val contextId = "cmux-notice-$lease"
    private val blank = "about:blank#cmux-notice-$lease"
    private var session: GeckoSession? = null
    private var view: GeckoView? = null
    private var closed = false
    private var unavailable = false
    private val mutableClosed = MutableStateFlow(false)
    override val isClosed = mutableClosed.asStateFlow()
    internal val diagnostic: String get() = "$stage/${engine?.failureCode.orEmpty()}"
    internal var stage: String = "starting"
        private set
    private var acquired = false
    private var remoteStarted = false
    private var openingBlank = true
    private var isDark = dark
    private var retired: Deferred<Boolean>? = null
    private val blankReady = CompletableDeferred<Unit>()
    override val load: NativeWhatsNewWebLoad = NativeWhatsNewWebLoad(parent, policy, url, deadlineMillis,
        stopRenderer = { retire() }, closeRenderer = { retire() }) {
        checkCurrent()
        val e = NativeNoticeEngine.get(application).also { engine = it }
        stage = "engine"
        val runtime = e.prepare(this)
        checkCurrent(); e.theme(isDark)
        val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).contextId(contextId)
            .suspendMediaWhenInactive(true).build()).also { session = it }
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, uri: String?,
                permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (uri == blank && !remoteStarted) blankReady.complete(Unit)
            }
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
                navigation(request.uri, true, request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW)
            override fun onSubframeLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
                navigation(request.uri, false, false)
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                load.failedInitialPage(); return null
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, uri: String) {
                if (uri == blank) openingBlank = true
                else if (policy.allows(uri)) { openingBlank = false; remoteStarted = true }
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (closed) return
                if (!currentOwner()) { close(); return }
                if (openingBlank && !remoteStarted) {
                    if (!success) load.failedInitialPage()
                } else if (remoteStarted) {
                    if (success) load.finishedInitialPage() else load.failedInitialPage()
                }
            }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) = engineFailed()
            override fun onKill(session: GeckoSession) = engineFailed()
            override fun onCloseRequest(session: GeckoSession) = close()
        }
        s.open(runtime)
        view?.setSession(s)
        stage = "blank"
        s.loadUri(blank)
        blankReady.await(); checkCurrent()
        // Set before dispatch: canceled/late acquire replies still require scoped cleanup.
        acquired = true
        stage = "acquire"
        e.acquire(lease, contextId)
        checkCurrent()
        stage = "exchange"
        val sessionCookies = cookies(url)
        checkCurrent()
        stage = "seed"
        e.command("seed", lease, url, sessionCookies)
        checkCurrent()
        openingBlank = false
        stage = "navigation"
        s.loadUri(url)
    }
    private fun checkCurrent() {
        NativeNoticeEngine.checkMain()
        check(!closed && !unavailable && currentOwner()) { "Notice owner changed" }
    }
    private fun navigation(uri: String, main: Boolean, newWindow: Boolean): GeckoResult<AllowOrDeny> {
        val allowed = if (closed || unavailable || !currentOwner()) { close(); false }
            else if (newWindow) false
            else if (!remoteStarted && uri == blank) true
            else load.allowsNavigation(uri, main)
        return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
    }
    fun attach(target: GeckoView) {
        NativeNoticeEngine.checkMain()
        if (closed || unavailable || !currentOwner()) { close(); return }
        if (view !== target) { detach(); view = target; session?.let(target::setSession) }
    }
    fun detach(target: GeckoView) { if (view === target) detach() }
    fun detach() { NativeNoticeEngine.checkMain(); view?.releaseSession(); view = null }
    override fun theme(dark: Boolean) { isDark = dark; if (!closed && !unavailable) engine?.theme(dark) }
    // Revoke UI/acknowledgement eligibility without reentering Gecko during its port callback.
    internal fun revokeConnection() { unavailable = true; mutableClosed.value = true }
    internal fun engineFailed() { load.failedInitialPage(); retire() }
    private fun retire() {
        NativeNoticeEngine.checkMain()
        if (closed) return
        closed = true
        mutableClosed.value = true
        detach()
        session?.let { it.stop(); it.close() }; session = null
        retired = engine?.retire(this, contextId, lease, acquired)
    }
    suspend fun awaitRetired(): Boolean = retired?.await() ?: closed
    override fun close() { NativeNoticeEngine.checkMain(); load.close() }
}

private suspend fun <T : Any> GeckoResult<T>.awaitNotice(): T = suspendCancellableCoroutine { continuation ->
    accept({ if (it != null) continuation.resume(it) else continuation.resumeWithException(IllegalStateException("Missing engine result")) },
        { continuation.resumeWithException(it ?: IllegalStateException("Engine operation failed")) })
}
