package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

/** Retained by the owning ViewModel, never by an Activity or a Compose surface. */
internal class WorkspaceChangesPresentation(parent: CoroutineScope, initialAccess: WorkspaceChangesAccess) : AutoCloseable {
    var access by mutableStateOf(initialAccess)
        private set
    val navigation = ChangesNavigationState()
    val store = ChangesStore(parent, access.workspaceId,
        { access.read(WorkspaceChangesRead.Files) }, { path, budget -> access.read(WorkspaceChangesRead.Diff(path, budget)) },
        fetchLines = { path -> access.content.currentLines(path) })
    init { store.refresh() }
    fun replaceAccess(next: WorkspaceChangesAccess) {
        require(next.workspaceId == access.workspaceId) { "Changes replacement names another workspace" }
        if (next === access) return
        access = next
        store.refresh()
    }
    override fun close() { store.close() }
}
