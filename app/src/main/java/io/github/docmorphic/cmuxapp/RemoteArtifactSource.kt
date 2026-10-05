package io.github.docmorphic.cmuxapp

import java.io.File

/** Captured by an action at the moment it starts. Never substitutes another Mac or connection. */
internal data class RemoteArtifactSource(val rpc: ArtifactRpc, val authorization: ArtifactAuthorization, val path: String) {
    suspend fun metadata() = ArtifactContentTransfer(rpc, authorization).metadata(path)
    suspend fun prepareSave(files: FileSaveFiles, request: FileSaveSnapshot, metadata: ArtifactMetadata,
        progress: suspend (Long, Long) -> Unit = { _, _ -> }) {
        val transfer = ArtifactContentTransfer(rpc, authorization)
        files.prepareStream(request, metadata.size) { append ->
            transfer.stream(path, metadata, Long.MAX_VALUE) { bytes, received ->
                append(bytes, bytes.size); progress(received, metadata.size)
            }
        }
    }
    suspend fun materialize(root: File, name: (ArtifactMetadata) -> String = { changesPreviewName(path) }) =
        materializeArtifactShare(rpc, authorization, path, root, name)
}
