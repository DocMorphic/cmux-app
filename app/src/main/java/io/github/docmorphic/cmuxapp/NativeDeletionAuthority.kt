package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

internal data class NativeDeletionBinding(val user: String, val generation: Long, val epoch: Long, val process: String) {
    override fun toString() = "NativeDeletionBinding(redacted)"
}
internal enum class NativeDeletionReceiptStatus { CURRENT, STALE, WAITING }

/** A durable receipt stores no credentials. A new process must verify its user before consuming it. */
internal class NativeDeletionAuthority(
    private val owner: (String) -> NativeAuthenticatedOwner?,
    private val canSend: (NativeAuthenticatedOwner) -> Boolean,
    private val verified: () -> Boolean,
    private val delete: suspend (NativeAuthenticatedOwner) -> NativeAccountDeletionResult,
    private val process: String = PROCESS,
) {
    fun capture(login: String): NativeDeletionBinding? = runCatching { owner(login)?.takeIf(canSend)?.let(::binding) }.getOrNull()
    private fun binding(owner: NativeAuthenticatedOwner) = NativeDeletionBinding(owner.identity.userId,owner.identity.generation,owner.epoch,process)
    fun status(receipt: NativeAccountDeletionReceipt): NativeDeletionReceiptStatus {
        val pinned = receipt.binding ?: return NativeDeletionReceiptStatus.CURRENT // Legacy receipts are uncertainty only.
        if (pinned.process != process && !verified()) return NativeDeletionReceiptStatus.WAITING
        val current = try { owner(receipt.login) } catch (_: NativeCredentialReadException) { return NativeDeletionReceiptStatus.WAITING }
            ?: return NativeDeletionReceiptStatus.STALE
        val matches = if (pinned.process == process) binding(current) == pinned else current.identity.userId == pinned.user
        return if (matches) NativeDeletionReceiptStatus.CURRENT else NativeDeletionReceiptStatus.STALE
    }
    fun requestOwner(login: String, pinned: NativeDeletionBinding): NativeAuthenticatedOwner? =
        runCatching { owner(login)?.takeIf { binding(it) == pinned && canSend(it) } }.getOrNull()
    suspend fun delete(login: String, pinned: NativeDeletionBinding): NativeAccountDeletionResult =
        delete(requestOwner(login,pinned) ?: throw CancellationException("Account changed"))
    fun signOutOwner(receipt: NativeAccountDeletionReceipt): NativeAuthenticatedOwner? =
        if (receipt.binding != null && status(receipt) == NativeDeletionReceiptStatus.CURRENT)
            runCatching { owner(receipt.login) }.getOrNull() else null
    companion object { private val PROCESS = UUID.randomUUID().toString() }
}

/** All application call sites use this binding; the raw controller constructor is a fixture seam. */
internal fun nativeAccountDeletionController(scope: CoroutineScope, account: NativeAccount, store: NativeCredentialStore,
    origin: HttpUrl = "https://cmux.com/".toHttpUrl()): NativeAccountDeletionController {
    val authority = NativeDeletionAuthority(account::deletionOwner,account::isDeletionOwnerCurrent,
        { !account.sessionRestore.needsIdentityValidation }, { owner ->
            NativeAccountDeletionClient({ account.deletionCredentials(owner) },
                { account.isDeletionOwnerCurrent(owner) },origin).delete()
        })
    return NativeAccountDeletionController(scope,store::requireReadable,store::update,authority) { error("Account authority required") }
}
