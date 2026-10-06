package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The controller must close this client on account/team retirement; creation itself performs no I/O. */
internal fun nativeCloudApi(account: NativeAccount, teams: NativeAccountTeams, scope: NativeTeamScope): CloudApi {
    val owner = CloudAccountScope(scope.login, scope.userId, scope.teamId, scope.generation)
    return CloudApi(owner, credentials = {
        withContext(Dispatchers.IO) {
            if (!teams.isCurrent(scope)) throw CancellationException("Cloud account changed")
            // Reuse the account's coherent snapshot across refresh. Never combine an
            // access token from one login with a refresh token from its replacement.
            val snapshot = account.webSessionSnapshot(scope.login)
            if (!teams.isCurrent(scope)) throw CancellationException("Cloud account changed")
            snapshot?.let { CloudCredentials(owner, it.accessToken, it.refreshToken) }
        }
    }, isCurrent = { it == owner && teams.isCurrent(scope) })
}
