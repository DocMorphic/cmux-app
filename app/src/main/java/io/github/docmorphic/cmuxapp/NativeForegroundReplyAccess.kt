package io.github.docmorphic.cmuxapp

/** Notification grants carry generation zero; only their account/team identity is compared to a live owner. */
internal fun nativePhoneReplyOwnerMatches(target: PhoneReplyDirectTarget, owner: NativeTeamScope?): Boolean =
    owner != null && target.team.login == owner.login && target.team.userId == owner.userId &&
        target.team.teamId == owner.teamId && target.peer.tuple.accountID == owner.userId &&
        (target.peer.tuple.teamID == null || target.peer.tuple.teamID == owner.teamId)

/** Borrow this handle only. The caller validates its exact admitted record and live owner. */
internal fun nativeForegroundReplyAttempt(target: PhoneReplyDirectTarget, mac: NativeCredentialStore.PairedMac,
    client: MobileRpcClient, workspaces: () -> List<NativeWorkspace>, isCurrent: () -> Boolean,
    queue: TerminalInputQueue? = null): PhoneReplyDirectAttempt? {
    if (!mac.ownsOrigin(target.origin) || !isCurrent() || client.isClosed) return null
    val workspace = target.resolve(workspaces()) ?: return null
    fun ready() = isCurrent() && !client.isClosed && target.resolve(workspaces()) == workspace &&
        client.terminalTrafficAllowed(target.surface)
    if (!ready()) return null
    return PhoneReplyDirectAttempt(target, ::ready) { text, allowed ->
        suspend fun paste(): Boolean {
            if (!allowed()) return false
            try { checkPhoneReplyPaste(client.pasteAdmitted(workspace, target.surface, text, submit = true, isCurrent = allowed)) }
            catch (failure: MobileRpcException) {
                if (failure.code in setOf("unauthorized", "forbidden", "permission_denied", "team_access_revoked")) client.close()
                throw failure
            }
            return true
        }
        if (queue == null) paste() else queue.performOrdered { paste() }
    }
}
