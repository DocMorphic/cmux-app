package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import androidx.compose.runtime.*

/** Admission is tied to one verified feed connection, never silently rebound after reconnect. */
internal class TerminalArtifactAccess(val rpc: ArtifactRpc, val current: () -> Boolean,
    val cachedCurrent: () -> Boolean = current)

/** Retains downloads and gallery identity through Activity recreation; contains no Activity or views. */
internal class TerminalFilesPresentation(
    parent: CoroutineScope, val login: String, val key: NativeWorkspaceTabKey,
    val mac: NativeCredentialStore.PairedMac, val terminal: ArtifactAuthorization.Terminal,
    val navigation: TerminalFilesState, access: TerminalArtifactAccess, private val connectionHold: AutoCloseable,
) : AutoCloseable {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    val galleryPreview = ArtifactPreviewController(scope)
    val directPreview = ArtifactPreviewController(scope)
    val galleryFolder = ArtifactFolderController(scope)
    val directFolder = ArtifactFolderController(scope)
    var access by mutableStateOf(access)
        private set
    private var gallery: ArtifactGalleryStore? = null
    fun galleryStore(): ArtifactGalleryStore = gallery ?: ArtifactGalleryStore(scope, terminal, access.rpc).also {
        gallery = it
        scope.launch { it.initialize().join(); it.setQuery(navigation.gallery.searchText) }
    }
    fun matches(login: String?, key: NativeWorkspaceTabKey?, surface: String?) =
        this.login == login && this.key == key && terminal.surfaceId == surface
    fun current() = owner.isActive && (access.current() || access.cachedCurrent())
    fun connectionLost() {
        galleryPreview.connectionLost(); directPreview.connectionLost(); gallery?.connectionLost()
        galleryFolder.connectionLost(); directFolder.connectionLost()
    }
    /** Replace future requests only. Each in-flight RPC remains tied to its original verified feed. */
    fun replaceConnection(next: TerminalArtifactAccess) {
        if (!current() || !next.current() || access.current()) return
        connectionLost()
        galleryPreview.replaceConnection(next.rpc); directPreview.replaceConnection(next.rpc)
        gallery?.replaceConnection(next.rpc)
        galleryFolder.replaceConnection(next.rpc); directFolder.replaceConnection(next.rpc)
        access = next
    }
    fun closeGallery() { navigation.closeGallery(); gallery?.close(); gallery = null; galleryPreview.clear(); galleryFolder.clear() }
    fun closePath() { navigation.closePath(); directPreview.clear(); directFolder.clear() }
    override fun close() {
        gallery?.close(); gallery = null; galleryPreview.close(); directPreview.close(); galleryFolder.close(); directFolder.close(); owner.cancel(); connectionHold.close()
    }
}
