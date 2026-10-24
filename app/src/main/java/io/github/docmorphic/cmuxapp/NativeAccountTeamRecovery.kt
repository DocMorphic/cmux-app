package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineScope

/** Bind the process account before bootstrap; private worker owners deliberately do not start loops. */
internal class NativeAccountTeamRecovery(scope:CoroutineScope,account:NativeAccount,private val teams:NativeAccountTeams,
    lock:Any,nowMillis:()->Long={android.os.SystemClock.elapsedRealtime()},jitter:()->Double={kotlin.random.Random.nextDouble(.5,1.0)}) : AutoCloseable {
    private val model=account.sessionRestore
    val coordinator=NativeTeamScopeRecovery(scope,{model.sessionGeneration},{model.state.value.authenticated},
        {teams.hasLoadedMembership},{teams.activeMembershipReads},
        {admit->account.retryLocalLogout();model.revalidateIf(admit)},nowMillis,jitter,lock=lock)
    private val outcome:(NativeAuthIdentity?,Exception?)->Unit=coordinator::membershipCompleted
    init {model.recoveryObserver=coordinator;teams.onMembershipCompleted=outcome}
    override fun close() {
        if(model.recoveryObserver===coordinator)model.recoveryObserver=null
        if(teams.onMembershipCompleted===outcome)teams.onMembershipCompleted={_,_->}
        coordinator.close()
    }
}
