package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineScope

/** Retained by the owning ViewModel, never by an Activity or a Compose surface. */
internal class WorkspaceChangesPresentation(parent: CoroutineScope, val access: WorkspaceChangesAccess) : AutoCloseable {
    val navigation = ChangesNavigationState()
    val store = ChangesStore(parent, access.workspaceId,
        { access.read(WorkspaceChangesRead.Files) }, { path, budget -> access.read(WorkspaceChangesRead.Diff(path, budget)) },
        fetchLines = { path -> access.content.currentLines(path) })
    init { store.refresh() }
    override fun close() { store.close() }
}
