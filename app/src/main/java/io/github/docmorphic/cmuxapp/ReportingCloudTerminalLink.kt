package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException

/** Reports live terminal operations to their machine connection's status owner. */
internal class ReportingCloudTerminalLink(private val delegate: CloudTerminalLink,
    private val result: (CloudSessionFailure?) -> Unit) : CloudTerminalLink {
    private inline fun <T> perform(success: Boolean = false, block: () -> T): T {
        try {
            val value = block()
            if (success) result(null)
            return value
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            result(CloudSessionFailure.classify(failure, CloudFailureKind.LINK)); throw failure
        } catch (failure: LinkageError) {
            result(CloudSessionFailure("Cloud native runtime is unavailable", kind = CloudFailureKind.LINK)); throw failure
        }
    }
    override fun attach(terminal: String) = perform(success = true) { delegate.attach(terminal) }
    override fun detach(attachment: Long) = delegate.detach(attachment)
    override fun send(attachment: Long, bytes: ByteArray) = perform {
        delegate.send(attachment, bytes).also {
            if (!it) result(CloudSessionFailure("Cloud terminal input was rejected", kind = CloudFailureKind.LINK))
        }
    }
    override fun resize(attachment: Long, columns: Int, rows: Int) = perform {
        delegate.resize(attachment, columns, rows).also {
            if (it == 0L) result(CloudSessionFailure("Cloud terminal resize was rejected", kind = CloudFailureKind.LINK))
        }
    }
    override suspend fun output(attachment: Long) = perform { delegate.output(attachment) }
}
