package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONObject

/** Saved-instance state contains IDs, never terminal output, credentials or mutation requests. */
internal data class NativeScreenCheckpoint(val login: String, val key: NativeWorkspaceTabKey,
    val tab: NativeWorkspaceTab?, val changes: Boolean = false,
    val startup: NativeTerminalStartup.Pending? = null, val failure: NativeTerminalStartup.Failure? = null,
    val savedAt: Long = android.os.SystemClock.elapsedRealtime(), val bootCount: Int? = null) {
    fun matches(login: String?, team: NativeTeamScope?, mac: NativeCredentialStore.PairedMac) =
        this.login == login && workspaceTabKey(login, team, mac, key.workspaceId) == key

    /** Legacy saved rows can lack cached account fields. Identity lookup is not admission. */
    fun matchesSavedIdentity(login: String?, mac: NativeCredentialStore.PairedMac) = this.login == login &&
        workspaceTabKey(login, null, mac, key.workspaceId)?.computerId == key.computerId &&
        (mac.accountUserId == null || mac.accountUserId == key.accountId) &&
        (mac.accountTeamId == null || mac.accountTeamId == key.teamId)

    fun encode(): String = JSONObject().put("version", 1).put("login", login)
        .put("account", key.accountId).put("team", key.teamId ?: JSONObject.NULL)
        .put("computer", key.computerId).put("workspace", key.workspaceId)
        .put("kind", tab?.kind?.wire ?: JSONObject.NULL).put("tab", tab?.id ?: JSONObject.NULL)
        .put("changes", changes).put("saved_at", savedAt).put("boot_count", bootCount ?: JSONObject.NULL)
        .put("startup", startup?.let { JSONObject().put("terminal", it.terminalId).put("deadline", it.deadline).put("id", it.id) } ?: JSONObject.NULL)
        .put("failure", failure?.terminalId ?: JSONObject.NULL).toString()

    companion object {
        fun decode(encoded: String): NativeScreenCheckpoint? = runCatching {
            require(encoded.length <= 32_768)
            val value = JSONObject(encoded)
            require(value.opt("version") == 1)
            fun text(name: String, optional: Boolean = false): String? {
                if (optional && value.isNull(name)) return null
                return (value.opt(name) as? String)?.takeIf { it.isNotBlank() && it.length <= 4096 }
                    ?: error("Invalid saved destination")
            }
            val key = NativeWorkspaceTabKey(text("account")!!, text("team", true), text("computer")!!, text("workspace")!!)
            val kind = text("kind", true)
            val tabId = text("tab", true)
            require((kind == null) == (tabId == null))
            val tab = kind?.let { NativeWorkspaceTab(NativeWorkspaceTabKind.entries.single { it.wire == kind }, tabId!!) }
            val changes = value.opt("changes") as? Boolean ?: error("Invalid saved destination")
            require(!changes || tab == null)
            fun integer(value: Any?): Long = when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> error("Invalid saved destination")
            }
            val savedAt = integer(value.opt("saved_at"))
            require(savedAt >= 0)
            val bootCount = if (value.isNull("boot_count")) null else integer(value.opt("boot_count")).let {
                require(it in 0..Int.MAX_VALUE.toLong()); it.toInt()
            }
            val startup = if (value.isNull("startup")) null else value.getJSONObject("startup").let {
                val terminal = it.opt("terminal") as? String ?: error("Invalid saved destination")
                val id = it.opt("id") as? String ?: error("Invalid saved destination")
                val deadline = integer(it.opt("deadline"))
                require(terminal.isNotBlank() && terminal.length <= 4096 && id.isNotBlank() && id.length <= 128)
                require(deadline >= 0 && deadline - savedAt <= NativeTerminalStartup.TIMEOUT)
                require(tab == NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, terminal) && !changes)
                NativeTerminalStartup.Pending(key, terminal, deadline, id)
            }
            val failure = text("failure", true)?.let { NativeTerminalStartup.Failure(key, it) }
            require(startup == null || failure == null)
            NativeScreenCheckpoint(text("login")!!, key, tab, changes, startup, failure, savedAt, bootCount)
        }.getOrNull()
    }
}

internal class NativeScreenResume(saved: NativeScreenCheckpoint? = null) {
    var pending by mutableStateOf(saved)
        private set
    private var current = saved
    val routeId = java.util.UUID.randomUUID().toString()
    fun observe(value: NativeScreenCheckpoint?) { if (pending == null) current = value }
    fun complete() { pending = null }
    fun cancel() { pending = null; current = null }
    fun save() = current?.encode().orEmpty()
    companion object {
        val saver = Saver<NativeScreenResume, String>(save = { it.save() }, restore = { NativeScreenResume(NativeScreenCheckpoint.decode(it)) })
    }
}

@Composable
internal fun rememberNativeScreenResume() = rememberSaveable(saver = NativeScreenResume.saver) { NativeScreenResume() }

@Composable
internal fun rememberNativeScreenBootCount(): Int? {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    return remember(context) { runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
            .takeIf { it >= 0 }
    }.getOrNull() }
}

/** Scope admission precedes routing. A notification or explicit navigation always wins. */
@Composable
internal fun NativeScreenResumeEffect(resume: NativeScreenResume, login: String?, team: NativeTeamScope?,
    savedMacs: List<NativeCredentialStore.PairedMac>, admittedMacs: List<NativeCredentialStore.PairedMac>,
    admissionReady: Boolean, hasRetainedPane: Boolean, newerNavigation: Boolean,
    onRoute: (NativeWorkspaceRoute?) -> Unit) {
    val route by rememberUpdatedState(onRoute)
    LaunchedEffect(resume.pending, login, team, savedMacs, admittedMacs, admissionReady, hasRetainedPane, newerNavigation) {
        val checkpoint = resume.pending ?: return@LaunchedEffect
        fun cancel() { resume.cancel(); route(null) }
        if (checkpoint.login != login || newerNavigation) { cancel(); return@LaunchedEffect }
        if (hasRetainedPane) { resume.complete(); return@LaunchedEffect }
        val saved = savedMacs.singleOrNull { checkpoint.matchesSavedIdentity(login, it) }
        if (saved == null || (team != null && !checkpoint.matches(login, team, saved))) { cancel(); return@LaunchedEffect }
        if (!admissionReady) return@LaunchedEffect
        val mac = admittedMacs.singleOrNull { it == saved }
        if (mac == null || !checkpoint.matches(login, team, mac)) { cancel(); return@LaunchedEffect }
        route(NativeWorkspaceRoute(mac.origin, checkpoint.key.workspaceId, changes = checkpoint.changes,
            id = resume.routeId, resume = checkpoint))
    }
}
