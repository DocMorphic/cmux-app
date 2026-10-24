package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

internal data class NativeComputerForgetState(
    val busy: Boolean = false,
    val remoteConfirmed: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null
)

/** One confirmed detail action. A local save retry never repeats a confirmed revoke. */
internal class NativeComputerForgetFlow(
    private val permits: () -> Boolean,
    private val capture: suspend () -> List<NativeCredentialStore.PairedMac>,
    private val prepare: (List<NativeCredentialStore.PairedMac>) -> Unit,
    private val revoke: suspend () -> Unit,
    private val cleanup: suspend (List<NativeCredentialStore.PairedMac>) -> Unit
) {
    private val gate = Mutex()
    private var rows: List<NativeCredentialStore.PairedMac>? = null
    private val mutableState = MutableStateFlow(NativeComputerForgetState())
    val state = mutableState.asStateFlow()
    fun canRetry() = state.value.error != null && !state.value.finished && permits()

    suspend fun confirm(): Boolean {
        if (!gate.tryLock()) return false
        if (state.value.finished) { gate.unlock(); return true }
        mutableState.value = state.value.copy(busy = true, error = null)
        try {
            if (!state.value.remoteConfirmed) {
                check(permits()) { "scope_changed" }
                val captured = capture()
                currentCoroutineContext().ensureActive()
                check(permits()) { "scope_changed" }
                prepare(captured)
                revoke()
                rows = captured
                mutableState.value = state.value.copy(remoteConfirmed = true)
            }
            cleanup(checkNotNull(rows))
            mutableState.value = state.value.copy(finished = true)
            return true
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val message = when {
                state.value.remoteConfirmed -> LOCAL_ERROR
                !permits() -> "Your account or team changed. Reopen Computer Details and try again."
                failure is IrohV2ServerFailure && failure.code == "permission_denied" ->
                    "Your account does not have permission to remove this computer."
                else -> REMOTE_ERROR
            }
            mutableState.value = state.value.copy(error = message)
            return false
        } finally {
            mutableState.value = state.value.copy(busy = false)
            gate.unlock()
        }
    }

    companion object {
        const val LOCAL_ERROR = "The server confirmed removal, but this phone couldn't finish clearing its saved pairing. Retry local cleanup."
        const val REMOTE_ERROR = "Couldn't confirm complete removal. The saved pairing is still on this phone. Check the connection and retry."
    }
}

/** Pure selection/removal for the credential store's single atomic commit. */
internal object NativeComputerForgetLocal {
    fun ownsForeground(code: String, team: NativeTeamScope, target: NativeComputerTarget,
        captured: List<NativeCredentialStore.PairedMac>): Boolean {
        if (captured.any { it.code == code }) return true
        // A discovered Mac may still be handshaking and not yet have a saved row.
        val pairing = PairingCodeParser.parse(code).getOrNull() as? PairingCode.Iroh ?: return false
        return pairing.userId == team.userId && pairing.teamId == team.teamId &&
            pairing.macDeviceId?.let(::canonicalMacDeviceId) == canonicalMacDeviceId(target.deviceId) &&
            pairing.buildTag == target.buildTag
    }

    fun capture(macs: List<NativeCredentialStore.PairedMac>, team: NativeTeamScope, target: NativeComputerTarget) =
        macs.filter { mac ->
            val saved = NativeComputerTarget.from(mac, team)
            saved != null && canonicalMacDeviceId(saved.deviceId) == canonicalMacDeviceId(target.deviceId) &&
                saved.buildTag == target.buildTag
        }

    fun remove(state: JSONObject, team: NativeTeamScope, captured: List<NativeCredentialStore.PairedMac>) {
        check(nativeSessionOwns(state,team.login)) {
            "Account session changed"
        }
        val previous = state.optJSONArray("pairings") ?: JSONArray()
        val next = JSONArray()
        val removed = mutableListOf<NativeCredentialStore.PairedMac>()
        for (index in 0 until previous.length()) {
            val item = previous.getJSONObject(index)
            val code = item.optString("code")
            val device = item.optString("device_id")
            val tag = (item.opt("instance_tag") as? String)?.takeIf { it.isNotBlank() }
            val decoded = NativePairingRecords.decode(item)
            val row = captured.firstOrNull { decoded != null && it.code == code && it.deviceId == device && it.instanceTag == tag &&
                it.accountUserId == decoded.accountUserId && it.accountTeamId == decoded.accountTeamId && it.origin == decoded.origin &&
                it.ticketRevision == decoded.ticketRevision && it.nativeRouteCode == decoded.nativeRouteCode }
            // The captured row must still carry a verified identity in its owning scope.
            if (row != null && NativeComputerTarget.from(row, team) != null) removed += row else next.put(item)
        }
        state.put("pairings", next)
        NativeAttachTicketStore.prune(state)
        NativePairingRecords.clearAffectedSelection(state, removed)
    }
}

internal data class NativeComputerForgetCallbacks(
    val started: (NativeTeamScope, NativeComputerTarget, List<NativeCredentialStore.PairedMac>) -> Unit = { _, _, _ -> },
    val finished: () -> Unit = {}
)

internal fun nativeComputerForgetFlow(runtime: NativeIrohRuntime, team: NativeTeamScope,
    target: NativeComputerTarget, store: NativeCredentialStore, appearance: NativeMacAppearanceStore,
    connectionSettings: NativeMacConnectionStore,
    prepare: (List<NativeCredentialStore.PairedMac>) -> Unit): NativeComputerForgetFlow = NativeComputerForgetFlow(
    permits = { runtime.permitsAppearance(team) },
    capture = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        check(store.taskSession() == team.login) { "Account session changed" }
        NativeComputerForgetLocal.capture(store.pairedMacs(), team, target)
    } },
    prepare = prepare,
    revoke = { runtime.forgetComputer(team, target) },
    cleanup = { rows -> nativeComputerForgetCleanup(team, target, store, appearance, connectionSettings, rows) }
)

internal fun nativeForegroundComputerForgetFlow(runtime: NativeIrohRuntime, management: NativeForegroundComputerManagement,
    store: NativeCredentialStore, appearance: NativeMacAppearanceStore, connectionSettings: NativeMacConnectionStore,
    beginAllowed: () -> Boolean = management::available,
    prepare: (List<NativeCredentialStore.PairedMac>) -> Unit): NativeComputerForgetFlow {
    val team = management.team
    val target = checkNotNull(NativeComputerTarget.from(management.mac, team))
    var permit: NativeForegroundComputerRevoke? = null
    return NativeComputerForgetFlow(
        permits = { permit?.current() == true || (beginAllowed() && management.available()) },
        capture = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            check(store.taskSession() == team.login)
            NativeComputerForgetLocal.capture(store.pairedMacs(), team, target)
        } },
        prepare = { rows ->
            if (permit == null) { check(beginAllowed()); permit = management.beginRevoke() }
            check(permit?.current() == true)
            prepare(rows)
        },
        revoke = { runtime.forgetForegroundComputer(checkNotNull(permit)) },
        cleanup = { rows ->
            nativeComputerForgetCleanup(team, target, store, appearance, connectionSettings, rows)
            permit?.close()
        })
}

private suspend fun nativeComputerForgetCleanup(team: NativeTeamScope, target: NativeComputerTarget,
    store: NativeCredentialStore, appearance: NativeMacAppearanceStore, connectionSettings: NativeMacConnectionStore,
    rows: List<NativeCredentialStore.PairedMac>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Clear metadata first. A failed pairing commit leaves a retryable saved row.
        // Both stores are addressed by the captured owner, never the live display team.
        var discarded = emptyList<NativePairingAppearanceUpgrade>()
        store.update {
            check(store.taskSession() == team.login) { "Account session changed" }
            discarded = NativePairingAppearanceUpgrades.discard(it, team, target)
        }
        appearance.removeComputer(target, permits = { store.taskSession() == team.login }, removeLegacy = {
            discarded.isNotEmpty() && store.pairedMacs().none {
                canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(target.deviceId) && it.instanceTag == null
            }
        })
        connectionSettings.removeComputer(target) { store.taskSession() == team.login }
        store.forgetCapturedNativeMac(team, rows, target)
    }
