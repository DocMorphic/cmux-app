package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/** Independent of discovery/HTTP: restored warnings work while the saved Mac is offline. */
internal fun CoroutineScope.observeMacVersionHistory(store: NativeCredentialStore,
    teams: StateFlow<NativeAccountTeamsState>, isCurrent: (NativeTeamScope) -> Boolean,
    gate: NativeMacCompatibilityGate): Job = launch {
    combine(teams, store.revisions, gate.observations) { state, _, _ -> state.scope }.collect { owner ->
        gate.reconcile()
        if (owner != null && isCurrent(owner)) {
            try {
                // A new pairing can be saved after its first authenticated observation.
                persistMacVersionHistory(store, gate, owner, isCurrent)
                gate.restore(owner, NativeMacVersionHistory.read(store.load(), owner))
            } catch (_: Exception) { currentCoroutineContext().ensureActive() }
        }
    }
}

internal fun persistMacVersionHistory(store: NativeCredentialStore, gate: NativeMacCompatibilityGate,
    owner: NativeTeamScope, isCurrent: (NativeTeamScope) -> Boolean) {
    // Read the immutable latest observation inside the credential transaction, so a delayed
    // callback cannot overwrite a newer version with its own captured snapshot.
    store.recordMacVersions(owner, { isCurrent(owner) }) {
        gate.observations.value.filterKeys { it.owner == owner }.mapKeys { it.key.identity }
    }
}
