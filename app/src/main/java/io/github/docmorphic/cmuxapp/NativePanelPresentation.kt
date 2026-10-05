package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** Title is the host's refresh token; focus changes alone do not invalidate the file. */
internal data class NativePanelTarget(val workspace: String, val surface: String, val path: String,
    val kind: String, val title: String) {
    val authorization get() = ArtifactAuthorization.Panel(workspace, surface, path)
    companion object {
        fun from(workspace: String, surface: NativeSurface): NativePanelTarget? = surface.takeIf { it.isPanelFile }?.let {
            NativePanelTarget(workspace, it.id, checkNotNull(it.filePath), it.kind, it.title)
        }
    }
}

internal class PanelArtifactAccess(val rpc: ArtifactRpc, val current: () -> Boolean)

/** One visible panel and its exact admission survive recreation, without retaining Android views. */
internal class NativePanelPresentation(parent: CoroutineScope, val login: String,
    val key: NativeWorkspaceTabKey, val mac: NativeCredentialStore.PairedMac, val target: NativePanelTarget,
    val access: PanelArtifactAccess, private val connectionHold: AutoCloseable) : AutoCloseable {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    val preview = ArtifactPreviewController(CoroutineScope(parent.coroutineContext + owner))
    fun current() = owner.isActive && access.current()
    fun matches(login: String?, key: NativeWorkspaceTabKey?, mac: NativeCredentialStore.PairedMac?, target: NativePanelTarget?) =
        this.login == login && this.key == key && this.mac == mac && this.target == target
    override fun close() { preview.close(); owner.cancel(); connectionHold.close() }
}
