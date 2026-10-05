package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** Admission is tied to one verified feed connection, never silently rebound after reconnect. */
internal class TerminalArtifactAccess(val rpc: ArtifactRpc, val current: () -> Boolean)

/** Retains downloads and gallery identity through Activity recreation; contains no Activity or views. */
internal class TerminalFilesPresentation(
    parent: CoroutineScope, val login: String, val key: NativeWorkspaceTabKey,
    val mac: NativeCredentialStore.PairedMac, val terminal: ArtifactAuthorization.Terminal,
    val navigation: TerminalFilesState, val access: TerminalArtifactAccess, private val connectionHold: AutoCloseable,
) : AutoCloseable {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    val galleryPreview = ArtifactPreviewController(scope)
    val directPreview = ArtifactPreviewController(scope)
    private var gallery: ArtifactGalleryStore? = null
    fun galleryStore(): ArtifactGalleryStore = gallery ?: ArtifactGalleryStore(scope, terminal, access.rpc).also {
        gallery = it
        scope.launch { it.initialize().join(); it.setQuery(navigation.gallery.searchText) }
    }
    fun matches(login: String?, key: NativeWorkspaceTabKey?, surface: String?) =
        this.login == login && this.key == key && terminal.surfaceId == surface
    fun current() = owner.isActive && access.current()
    fun closeGallery() { navigation.closeGallery(); gallery?.close(); gallery = null; galleryPreview.clear() }
    fun closePath() { navigation.closePath(); directPreview.clear() }
    override fun close() {
        gallery?.close(); gallery = null; galleryPreview.close(); directPreview.close(); owner.cancel(); connectionHold.close()
    }
}
