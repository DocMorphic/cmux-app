package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.Flow

/** Raw non-PTY SSH exec stream, owned by the account's transport. */
internal interface SshExecPipe : AutoCloseable {
    val output: Flow<ByteArray>
    suspend fun write(bytes: ByteArray)
}
