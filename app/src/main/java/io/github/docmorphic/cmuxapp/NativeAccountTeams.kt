package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class NativeTeam(val id: String, val name: String)
internal fun nativeResolvedTeamId(selected:String?,teams:List<NativeTeam>):String? =
    if(teams.isEmpty())selected else selected?.takeIf{id->teams.any{it.id==id}} ?: teams.first().id
internal data class NativeTeamScope(val login: String, val userId: String, val teamId: String, val generation: Long)
/** Memory-only proof for an already admitted session; never authorizes a new dial. */
internal class NativeTeamContinuation internal constructor(val admittedScope: NativeTeamScope, internal val stamp: Any) {
    override fun toString() = "NativeTeamContinuation"
}
internal data class NativeAccountTeamsState(
    val userId: String? = null,
    val teams: List<NativeTeam> = emptyList(),
    val selectedTeamId: String? = null,
    val scope: NativeTeamScope? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val createdTeam: NativeTeam? = null,
    val displayName: String? = null,
    val email: String? = null,
    val cached: Boolean = false
)

/** Stack account membership is the authority for choosing the Iroh identity scope. */
internal class NativeAccountTeams(
    private val token: suspend (force: Boolean) -> String?,
    private val login: () -> String?,
    private val origin: HttpUrl = "https://api.stack-auth.com/api/v1/".toHttpUrl(),
    base: OkHttpClient = OkHttpClient(),
    private val refreshCredential: () -> String? = { null },
    private val backendOrigin: HttpUrl = "https://cmux.com/".toHttpUrl(),
    private val cache: NativeAccountProfileCache? = null,
    private val lock: Any = Any(),
    private val sessionIdentity:(()->NativeAuthIdentity?)?=null,
    private val restoreUser:(suspend ()->NativeSignInUser?)?=null,
    private val needsRestoration:()->Boolean={false},
    private val phaseNowMillis:()->Long={System.nanoTime()/1_000_000},
    private val phaseWork:NativeAuthWork=NativeAuthWork(nowMillis=phaseNowMillis),
    private val networkTimeoutMillis:Long=NETWORK_TIMEOUT_MILLIS,
    private val accountUser:(()->NativeSignInUser?)?=null,
    private val sessionUser:(()->NativeSignInUser?)?=null,
    private val sessionGeneration:(()->Long)?=null,
) : AutoCloseable {
    constructor(account: NativeAccount, store: NativeCredentialStore,
        origin:HttpUrl="https://api.stack-auth.com/api/v1/".toHttpUrl(),base:OkHttpClient=OkHttpClient(),
        backendOrigin:HttpUrl="https://cmux.com/".toHttpUrl(),networkTimeoutMillis:Long=NETWORK_TIMEOUT_MILLIS) : this(
        { force -> account.accessToken(force) }, { store.taskSession() },
        origin=origin,base=base,backendOrigin=backendOrigin,
        refreshCredential = { store.load()?.optString("refresh_token") },
        cache = NativeAccountProfileCache(store::load, store::update), lock = store.accountStateLock,
        sessionIdentity=account.sessionRestore::identity,restoreUser=account.sessionRestore::restoreIdentityIfNeeded,
        needsRestoration={account.sessionRestore.needsIdentityValidation},phaseNowMillis={android.os.SystemClock.elapsedRealtime()},phaseWork=store.authWork,
        networkTimeoutMillis=networkTimeoutMillis,accountUser={account.sessionRestore.state.value.user},
        sessionUser=account.sessionRestore::verifiedUser,sessionGeneration={account.sessionRestore.sessionGeneration})

    private class StackFailure(val status: Int) : IOException("Account request failed ($status)")
    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(0,TimeUnit.SECONDS)
        .connectTimeout(30,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS).build()
    private val backendClient=client.newBuilder().connectTimeout(60,TimeUnit.SECONDS)
        .readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build()
    private var membershipReads=0
    private var teamMutation=0L
    private var creatingTeam=false
    private var selectingTeams=0
    private class PhaseLease(val deadline:Long?) {val retired=AtomicBoolean(false)}
    private data class Operation(val run:Long,val identity:NativeAuthIdentity?,val mutation:Long?,val generation:Long?,val phase:PhaseLease?=null)
    private data class Membership(val user:NativeSignInUser,val teams:List<NativeTeam>,val selected:String?=null)
    private class MembershipReadFailure(val user:String,val failure:Exception):Exception("Could not read account membership") {
        override fun toString()="MembershipReadFailure"
    }
    private class PrincipalChanged:CancellationException("Account identity changed")
    val activeMembershipReads get()=synchronized(lock){membershipReads}
    val hasLoadedMembership get()=synchronized(lock) {
        !closed && verifiedLogin!=null && verifiedLogin==login() && membershipUser!=null &&
            verifiedGeneration==sessionGeneration?.invoke() && principalCurrent(checkNotNull(membershipUser))
    }
    @Volatile internal var onMembershipCompleted:(NativeAuthIdentity?,Exception?)->Unit={_,_->}
    @Volatile internal var onMembershipGenerationCompleted:(Long?,Exception?)->Unit={_,_->}
    private val calls = mutableSetOf<Call>()
    private var closed = false
    private var run = 0L
    private var scopeGeneration = 0L
    private var verifiedLogin: String? = null
    private var membershipUser: String? = null
    private val membershipStamps = mutableMapOf<String, Any>()
    private var presentationLogin: String? = null
    private var presentationIdentity:NativeAuthIdentity?=null
    private var verifiedIdentity:NativeAuthIdentity?=null
    private var verifiedGeneration:Long?=null
    private var presentationGeneration:Long?=null
    private val cacheEnvironment = "$origin#${NativeAccount.PROJECT_ID}"
    private val mutableState = MutableStateFlow(NativeAccountTeamsState())
    val state = mutableState.asStateFlow()

    init {
        require(networkTimeoutMillis>0)
        require(backendOrigin.username.isEmpty() && backendOrigin.password.isEmpty() && backendOrigin.query == null && backendOrigin.fragment == null)
        require(backendOrigin.encodedPath == "/")
        require(backendOrigin.isHttps || backendOrigin.host in setOf("localhost", "127.0.0.1", "::1"))
        require(origin.username.isEmpty() && origin.password.isEmpty() && origin.query == null && origin.fragment == null)
        require(origin.encodedPath.endsWith('/'))
        require(origin.isHttps || origin.host in setOf("localhost", "127.0.0.1", "::1"))
        presentationLogin = login()
        presentationIdentity=sessionIdentity?.invoke()
        presentationGeneration=sessionGeneration?.invoke()
        mutableState.value = restore(presentationLogin,presentationIdentity)
    }

    fun isCurrent(captured: NativeTeamScope): Boolean = synchronized(lock) {
        !closed && captured == mutableState.value.scope && login() == captured.login && principalCurrent(captured.userId)
    }

    /** Capture only while this scope can admit a connection, after online verification. */
    fun captureContinuation(captured: NativeTeamScope): NativeTeamContinuation? = synchronized(lock) {
        if (!isCurrent(captured) || verifiedLogin != captured.login || membershipUser != captured.userId ||
            mutableState.value.cached) return@synchronized null
        membershipStamps[captured.teamId]?.let { NativeTeamContinuation(captured, it) }
    }

    /** Selection can change; logout, principal replacement and membership removal cannot. */
    fun allowsContinuation(proof: NativeTeamContinuation): Boolean = synchronized(lock) {
        val admitted = proof.admittedScope
        !closed && login() == admitted.login && verifiedLogin == admitted.login && membershipUser == admitted.userId &&
            principalCurrent(admitted.userId) && membershipStamps[admitted.teamId] === proof.stamp
    }

    private fun clearVerification() {
        verifiedLogin = null
        membershipUser = null
        membershipStamps.clear()
        verifiedIdentity=null
        verifiedGeneration=null
    }
    private fun emptyPrincipalCurrent()=sessionIdentity!=null && sessionIdentity.invoke()==null && sessionUser?.invoke()?.id==""
    private fun principalCurrent(user:String):Boolean=verifiedGeneration==sessionGeneration?.invoke() &&
        (sessionIdentity==null || sessionIdentity.invoke()?.let { it==verifiedIdentity && it.userId==user }==true ||
            user.isEmpty() && emptyPrincipalCurrent())

    suspend fun refresh(): NativeAccountTeamsState {
        if(sessionIdentity!=null && (sessionIdentity.invoke()==null && !emptyPrincipalCurrent() || needsRestoration()) && restoreUser!=null) {
            val user=restoreUser.invoke() ?: throw CancellationException("Account identity unavailable")
            val owner=login() ?: throw CancellationException("Account changed")
            val restored=synchronized(lock) {
                mutableState.value.takeIf { verifiedIdentity==sessionIdentity.invoke() && verifiedGeneration==sessionGeneration?.invoke() && verifiedLogin==owner && membershipUser==user.id &&
                    !it.cached && !it.loading && it.error==null }
            }
            // The process owner may already have refreshed membership in its verified-user callback.
            return restored ?: refreshAfterSignIn(owner,user)
        }
        return membership()
    }

    /** Completion already fetched this user; membership failure cannot undo that publication. */
    suspend fun refreshAfterSignIn(expectedLogin:String,user:NativeSignInUser):NativeAccountTeamsState =
        membership(user,expectedLogin)

    private suspend fun listTeams(owner: String, epoch: Operation): List<NativeTeam> {
        return NativeSdkTeamRows.decode(request("teams?user_id=me",owner,epoch,emptyObjectOnInvalidJson=true))
    }

    private suspend fun eligibleUser(owner:String,epoch:Operation):NativeSignInUser =
        try {NativeSignInUser.decode(request("users/me",owner,epoch))}
        catch(failure:Exception) {
            currentCoroutineContext().ensureActive()
            if(failure is CancellationException || nativeSessionCheckLimit(failure)!=null)throw failure
            throw NativeSessionUserUnavailable()
        }
    private suspend fun selectedTeamId(owner:String,epoch:Operation):String? = try {
        val value=request("users/me",owner,epoch)
        if (!NativeSignInUser.isEligible(value)) null else {
            // The SDK selected-team projection uses Team's string/default id;
            // unrelated profile fields do not determine whether this read succeeds.
            if(sessionIdentity!=null)checkPrincipal(owner,epoch,value.opt("id") as? String ?: "")
            (value.opt("selected_team") as? JSONObject)?.let {it.opt("id") as? String ?: ""}
        }
    } catch(failure:Exception) {
        currentCoroutineContext().ensureActive()
        null // The coordinator's independent selectedTeamID call is best effort.
    }
    private fun claimMutation(create:Boolean):Long=synchronized(lock) {
        check(!closed){"Account team session closed"}
        check(!creatingTeam && (!create || selectingTeams==0)){"A team change is already in progress"}
        if(create)creatingTeam=true else selectingTeams++
        ++teamMutation
    }
    private fun releaseMutation(create:Boolean)=synchronized(lock) {
        if(create)creatingTeam=false else selectingTeams--
        if(!closed) {
            if(creatingTeam || selectingTeams>0)mutableState.value=mutableState.value.copy(loading=true)
            else if(membershipReads==0)mutableState.value=mutableState.value.copy(loading=false)
        }
    }
    private suspend fun persistSelection(owner:String,epoch:Operation,userId:String,id:String?) {
        val fresh=eligibleUser(owner,epoch)
        checkPrincipal(owner,epoch,fresh.id)
        if(fresh.id!=userId)throw PrincipalChanged()
        // CurrentUser.update accepts a successful response even when its optional JSON is absent.
        // The SDK omits selected_team_id for nil; the coordinator still projects nil locally.
        request("users/me",owner,epoch,JSONObject().apply {if(id!=null)put("selected_team_id",id)},discardBody=true)
    }

    suspend fun create(displayName: String): NativeAccountTeamsState {
        val name = displayName.trim()
        require(name.isNotEmpty() && name.length <= 120) { "Enter a team name of 1–120 characters" }
        val mutation=claimMutation(create=true)
        try {return perform(expectedMutation=mutation) { owner, epoch ->
            val before = synchronized(lock) {
                checkCurrent(owner, epoch)
                check(mutableState.value.userId != null) { "Sign in to your account first" }
                requirePrincipal(checkNotNull(mutableState.value.userId))
                mutableState.value.copy(createdTeam = null).also { mutableState.value = it }
            }
            val created = try {phase(epoch,NativeAuthPhase.TEAM_SELECTION,NativeAuthWork.Admission.PHASE) {child->
                checkCurrent(owner,child)
                val access = credential(token(false))
                val refresh = credential(refreshCredential())
                checkCurrent(owner, child)
                val request = Request.Builder().url(checkNotNull(backendOrigin.resolve("api/subrouter/teams")))
                    .header("Accept", "application/json").header("Authorization", "Bearer $access")
                    .header("X-Stack-Refresh-Token", refresh)
                    .post(JSONObject().put("displayName", name).toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                // The backend can create the team before a later error. Never replay this POST.
                val team = exchange(request, owner, child,backend=true).getJSONObject("team")
                NativeTeam(identifier(team.getString("id")), team.getString("name").also {
                    if (it.isBlank() || it.length > 512) throw IOException("Invalid team name")
                })
            }} catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if(failure is NativeSignInFailure && failure.reason==NativeSignInError.TIMED_OUT)throw failure
                throw IOException("Team creation was not confirmed. Refresh your account before trying again.", failure)
            }
            synchronized(lock) {
                checkCurrent(owner, epoch)
                mutableState.value = mutableState.value.copy(createdTeam=created)
            }
            try {
                val listed=try {phase(epoch,NativeAuthPhase.TEAM_LIST,NativeAuthWork.Admission.TOKEN) {child->
                    val fresh=eligibleUser(owner,child);checkPrincipal(owner,child,fresh.id)
                    if(fresh.id!=before.userId)throw PrincipalChanged()
                    listTeams(owner,child)
                }} catch(failure:Exception) {
                    currentCoroutineContext().ensureActive()
                    if(failure is CancellationException)throw failure
                    // A completed create must not be retried because its subsequent list failed.
                    // Stale owner/mutation cancellation still stops before another HTTP request.
                    checkCurrent(owner,epoch)
                    synchronized(lock){checkCurrent(owner,epoch);mutableState.value.takeUnless{it.cached}?.teams.orEmpty()}
                }
                val teams = listed.let { listed ->
                    if (listed.any { it.id == created.id }) listed else listed + created
                }
                synchronized(lock) {
                    checkCurrent(owner,epoch)
                    val current=mutableState.value
                    if(current.userId!=before.userId)throw PrincipalChanged()
                    publish(owner,epoch,checkNotNull(before.userId),teams,current.selectedTeamId,membershipRead=false,
                        created=created,displayName=current.displayName,email=current.email,loading=true)
                }
                phase(epoch,NativeAuthPhase.TEAM_SELECTION,NativeAuthWork.Admission.PHASE) {child->
                    persistSelection(owner,child,checkNotNull(before.userId),created.id)
                }
                synchronized(lock) {
                    checkCurrent(owner,epoch)
                    val current=mutableState.value
                    if(current.userId!=before.userId)throw PrincipalChanged()
                    publish(owner,epoch,checkNotNull(before.userId),current.teams,created.id,membershipRead=false,
                        created=created,displayName=current.displayName,email=current.email)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if(failure is NativeSignInFailure && failure.reason==NativeSignInError.TIMED_OUT)throw failure
                throw IOException("Team created, but switching was not confirmed. Select it from Team or refresh your account.", failure)
            }
        }} finally {releaseMutation(create=true)}
    }

    suspend fun select(id: String?): NativeAccountTeamsState {
        val mutation=synchronized(lock) {
            check(!creatingTeam){"A team change is already in progress"}
            check(id==null || mutableState.value.teams.any {it.id==id}){"Refresh your account first"}
            claimMutation(create=false)
        }
        try {return perform(expectedMutation=mutation) { owner, epoch ->
        val before = synchronized(lock) { mutableState.value }
        check((id==null || before.teams.any { it.id == id }) && before.userId != null && (!before.cached || id==null)) { "Refresh your account first" }
        phase(epoch,NativeAuthPhase.TEAM_SELECTION,NativeAuthWork.Admission.PHASE) {child->
            persistSelection(owner,child,checkNotNull(before.userId),id)
        }
        synchronized(lock) {
            checkCurrent(owner,epoch)
            val current=mutableState.value
            if(current.userId!=before.userId)throw PrincipalChanged()
            publish(owner,epoch,checkNotNull(before.userId),current.teams,id,membershipRead=false,
                displayName=current.displayName,email=current.email,cached=current.cached)
        }
        }} finally {releaseMutation(create=false)}
    }

    private suspend fun membership(publishedUser:NativeSignInUser?=null,expectedLogin:String?=null):NativeAccountTeamsState {
        val (identity,generation)=synchronized(lock){membershipReads++;sessionIdentity?.invoke() to sessionGeneration?.invoke()}
        var failure:Exception?=null
        try {return perform(publishedUser,identity,expectedGeneration=generation) {owner,epoch->
            if(expectedLogin!=null && owner!=expectedLogin)throw CancellationException("Account changed")
            val value=phase(epoch,NativeAuthPhase.TEAM_LIST,NativeAuthWork.Admission.TOKEN) {child->coroutineScope {
                val membership=async(CoroutineName("team-membership")) {
                    val user=eligibleUser(owner,child)
                    checkPrincipal(owner,child,user.id)
                    val teams=try {listTeams(owner,child)} catch(error:Exception) {
                        if(error is CancellationException)throw error
                        throw MembershipReadFailure(user.id,error)
                    }
                    Membership(user,teams)
                }
                val selected=async(CoroutineName("team-selection-read")) {selectedTeamId(owner,child)}
                membership.await().copy(selected=selected.await())
            }}
            synchronized(lock) {
                checkCurrent(owner,epoch)
                if(mutableState.value.userId?.let{it!=value.user.id}==true) {
                    clearVerification();mutableState.value=NativeAccountTeamsState(loading=true)
                    runCatching{cache?.remove(owner)}
                }
            }
            synchronized(lock) {
                checkCurrent(owner,epoch)
                val remembered=mutableState.value.takeIf{it.userId==value.user.id}?.selectedTeamId
                val profile=accountUser?.invoke()?.takeIf{it.id==value.user.id}
                    ?: publishedUser?.takeIf{it.id==value.user.id} ?: value.user
                publish(owner,epoch,value.user.id,value.teams,nativeResolvedTeamId(value.selected ?: remembered,value.teams),
                    displayName=profile.name,email=profile.email)
            }
        }} catch(error:Exception){failure=error;throw error}
        finally {synchronized(lock){membershipReads--};onMembershipCompleted(identity,failure);onMembershipGenerationCompleted(generation,failure)}
    }

    /** The waiter owns retirement; a losing operation cannot start more HTTP or publish state. */
    private suspend fun <T> phase(epoch:Operation,label:NativeAuthPhase,admission:NativeAuthWork.Admission,
        action:suspend (Operation)->T):T {
        val lease=PhaseLease(if(admission==NativeAuthWork.Admission.TOKEN)phaseNowMillis()+networkTimeoutMillis else null)
        val child=epoch.copy(phase=lease)
        try {return phaseWork.run(label,networkTimeoutMillis,admission=admission) {
            try {action(child)} catch(error:Exception) {
                currentCoroutineContext().ensureActive()
                if(lease.deadline?.let{phaseNowMillis()>=it}==true)throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
                throw error
            }
        }} catch(_:NativeAuthDeadlineCancellation) {throw NativeSignInFailure(NativeSignInError.TIMED_OUT)}
        finally {lease.retired.set(true)}
    }
    private fun checkPrincipal(owner:String,epoch:Operation,user:String)=synchronized(lock) {
        checkCurrent(owner,epoch)
        if(sessionIdentity!=null && sessionIdentity.invoke()?.userId!=user && !(user.isEmpty() && emptyPrincipalCurrent()))throw PrincipalChanged()
    }

    private suspend fun perform(publishedUser: NativeSignInUser? = null,expectedIdentity:NativeAuthIdentity?=sessionIdentity?.invoke(),
                                expectedMutation:Long?=null,
                                expectedGeneration:Long?=sessionGeneration?.invoke(),
                                action: suspend (String, Operation) -> NativeAccountTeamsState): NativeAccountTeamsState =
        performNow(publishedUser,expectedIdentity,expectedMutation,expectedGeneration,action)

    private suspend fun performNow(publishedUser:NativeSignInUser?,expectedIdentity:NativeAuthIdentity?,expectedMutation:Long?,expectedGeneration:Long?,
        action:suspend (String,Operation)->NativeAccountTeamsState):NativeAccountTeamsState {
        val owner = login() ?: throw IOException("Sign in to cmux")
        val epoch = synchronized(lock) {
            check(!closed) { "Account team session closed" }
            val identity=sessionIdentity?.invoke()
            val generation=sessionGeneration?.invoke()
            if(generation!=expectedGeneration)throw CancellationException("Account session changed")
            if(sessionIdentity!=null && identity!=expectedIdentity)throw CancellationException("Account identity changed")
            if(expectedMutation!=null && expectedMutation!=teamMutation)throw CancellationException("Team selection changed")
            if(sessionIdentity!=null && identity==null && !emptyPrincipalCurrent())throw CancellationException("Account identity unavailable")
            if (presentationLogin != owner || presentationIdentity!=identity || presentationGeneration!=generation) {
                ++run
                clearVerification()
                presentationLogin = owner
                presentationIdentity=identity
                presentationGeneration=generation
                mutableState.value = restore(owner,identity)
            }
            val before=mutableState.value
            mutableState.value = if(publishedUser!=null)before.copy(userId=publishedUser.id,
                displayName=publishedUser.name,email=publishedUser.email,cached=false,loading=true,error=null)
                else before.copy(loading = true, error = null)
            Operation(run,identity,expectedMutation,generation)
        }
        return try {
            val result = action(owner, epoch)
            synchronized(lock) {
                checkCurrent(owner, epoch)
                // Another valid publication may have completed after this action.
                // Persistence and loading/error decoration must keep that newer state.
                val current=mutableState.value
                var cacheError: String? = null
                try {
                    if(current.cached)cache?.updateSelection(owner,cacheEnvironment,checkNotNull(current.userId),current.selectedTeamId)
                    else cache?.save(owner, cacheEnvironment, current)
                }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    cacheError = "Account refreshed, but details could not be saved for offline use."
                }
                checkCurrent(owner, epoch)
                val otherMutations=if(expectedMutation==null)creatingTeam || selectingTeams>0 else !creatingTeam && selectingTeams>1
                val error=cacheError ?: if(current===result)null else current.error
                mutableState.value = current.copy(loading=otherMutations,error=error)
            }
            mutableState.value
        } catch (caught: Exception) {
            val failure=(caught as? MembershipReadFailure)?.failure ?: caught
            synchronized(lock) {
                if (!closed && run == epoch.run && (epoch.mutation==null || epoch.mutation==teamMutation) &&
                    sessionGeneration?.invoke()==epoch.generation && (sessionIdentity==null || sessionIdentity.invoke()==epoch.identity)) {
                    if(failure is PrincipalChanged || caught is MembershipReadFailure &&
                        mutableState.value.userId?.let {it!=caught.user}==true) {
                        ++run;clearVerification();runCatching{cache?.remove(owner)}
                        mutableState.value=NativeAccountTeamsState(error="Your account changed. Refresh your account.")
                    } else if (login() != owner) {
                        clearVerification()
                        runCatching { cache?.remove(owner) }
                        mutableState.value = NativeAccountTeamsState(error = "Sign in to refresh your account")
                    } else {
                        // A failed list does not mean empty membership. Match the
                        // coordinator by retaining only this generation's loaded proof.
                        val message=if(creatingTeam && failure is NativeSignInFailure && failure.reason==NativeSignInError.TIMED_OUT)
                            if(mutableState.value.createdTeam==null)"Team creation timed out and was not confirmed. Refresh your account before trying again."
                            else "Team created, but switching timed out. Select it from Team or refresh your account."
                            else if(failure is org.json.JSONException)"Could not load account teams"
                            else failure.message ?: "Could not load account teams"
                        val retained=mutableState.value.copy(loading=false,error=message)
                        if(publishedUser!=null)runCatching { cache?.save(owner,cacheEnvironment,retained) }
                        mutableState.value=retained
                    }
                }
            }
            throw failure
        }
    }

    private fun publish(owner:String,epoch:Operation,user:String,teams:List<NativeTeam>,selected:String?,membershipRead:Boolean=true,
        displayName:String?=null,email:String?=null,created:NativeTeam?=null,loading:Boolean=false,cached:Boolean=false):NativeAccountTeamsState = synchronized(lock) {
        checkCurrent(owner,epoch)
        requirePrincipal(user)
        val marker=created ?: mutableState.value.createdTeam.takeIf{creatingTeam}
        if(!membershipRead && !(verifiedLogin==owner && membershipUser==user && verifiedIdentity==epoch.identity))
            return@synchronized NativeAccountTeamsState(user,teams,selected,displayName=displayName,email=email,
                createdTeam=marker,loading=loading,cached=cached).also{mutableState.value=it}
        if(verifiedIdentity!=epoch.identity || verifiedGeneration!=epoch.generation)clearVerification()
        if (verifiedLogin != owner || membershipUser != user) clearVerification()
        verifiedLogin = owner
        membershipUser = user
        verifiedIdentity=epoch.identity
        verifiedGeneration=epoch.generation
        val memberIds = teams.map { it.id }.toSet()
        membershipStamps.keys.retainAll(memberIds)
        memberIds.forEach { membershipStamps.getOrPut(it) { Any() } }
        val previous = mutableState.value.scope
        val resolved=nativeResolvedTeamId(selected,teams)?.takeIf {user.isNotEmpty() && it.isNotEmpty() && it in memberIds}
        val scope = resolved?.let {
            if (previous?.login == owner && previous.userId == user && previous.teamId == resolved) previous
            else NativeTeamScope(owner, user, resolved, ++scopeGeneration)
        }
        NativeAccountTeamsState(user,teams,selected,scope,displayName=displayName,email=email,
            createdTeam=marker,loading=loading,cached=cached).also{mutableState.value=it}
    }

    private suspend fun request(path: String, owner: String, epoch: Operation, body: JSONObject? = null,discardBody:Boolean=false,
        emptyObjectOnInvalidJson:Boolean=false): JSONObject {
        for (attempt in 0..1) {
            checkCurrent(owner, epoch)
            val access = token(attempt == 1)?.takeIf { it.isNotBlank() } ?: throw IOException("Sign in to cmux")
            if (access.length > 8192 || access.any { it == '\r' || it == '\n' }) throw IOException("Invalid account credential")
            checkCurrent(owner, epoch)
            val builder = Request.Builder().url(checkNotNull(origin.resolve(path)))
                .header("x-stack-project-id", NativeAccount.PROJECT_ID)
                .header("x-stack-publishable-client-key", NativeAccount.PUBLISHABLE_KEY)
                .header("x-stack-client-version", "cmux-android@0.2.0")
                .header("x-stack-access-type", "client")
                .header("x-stack-access-token", access)
                .header("x-stack-override-error-status", "true")
                .header("x-stack-random-nonce", UUID.randomUUID().toString())
            if (body != null) builder.patch(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            try {
                val response = exchange(builder.build(), owner, epoch,parseBody=!discardBody,emptyObjectOnInvalidJson=emptyObjectOnInvalidJson)
                checkCurrent(owner, epoch)
                return response
            } catch (failure: StackFailure) {
                // Only an explicit authentication rejection is replayable with a refreshed token.
                // A timeout or lost PATCH response is never treated as proof the mutation failed.
                if (failure.status != 401 || attempt != 0) throw failure
            }
        }
        throw IOException("Sign in to cmux")
    }

    private suspend fun exchange(request: Request, owner: String, epoch: Operation,parseBody:Boolean=true,backend:Boolean=false,
        emptyObjectOnInvalidJson:Boolean=false): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = (if(backend)backendClient else client).newCall(request)
        synchronized(lock) { checkCurrent(owner, epoch); calls += call }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(lock) { calls -= call }
                continuation.resumeWith(Result.failure(IOException("Could not reach your cmux account", e)))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val status = (it.header("x-hexclave-actual-status") ?: it.header("x-stack-actual-status"))?.toIntOrNull() ?: it.code
                        val known=it.header("x-hexclave-known-error") ?: it.header("x-stack-known-error")
                        if(it.code==429 || status==429 || known?.uppercase() in setOf("RATE_LIMITED","RATE_LIMIT"))throw nativeAccountRateLimit(it)
                        if (!it.isSuccessful || status !in 200..299) throw StackFailure(if (it.isSuccessful) status else it.code)
                        val body = it.body ?: throw IOException("Missing account response")
                        if (backend && body.contentLength() > MAX_REPLY) throw IOException("Account response too large")
                        val source = body.source()
                        if (backend && source.request(MAX_REPLY + 1L)) throw IOException("Account response too large")
                        val raw=source.readUtf8()
                        if(!parseBody)JSONObject() else try {JSONObject(raw)} catch(failure:Exception) {
                            if(emptyObjectOnInvalidJson)JSONObject() else throw failure
                        }
                    }
                }
                val guarded = synchronized(lock) {
                    calls -= call
                    runCatching { checkCurrent(owner, epoch); result.getOrThrow() }
                }
                continuation.resumeWith(guarded)
            }
        })
    }

    private fun checkCurrent(owner: String, epoch: Operation) = synchronized(lock) {
        if(epoch.phase?.retired?.get()==true)throw CancellationException("Account team request retired")
        if(epoch.phase?.deadline?.let{phaseNowMillis()>=it}==true)throw NativeSignInFailure(NativeSignInError.TIMED_OUT)
        if (closed || run != epoch.run || login() != owner || sessionGeneration?.invoke()!=epoch.generation || sessionIdentity!=null && sessionIdentity.invoke()!=epoch.identity ||
            epoch.mutation?.let {it!=teamMutation}==true)
            throw CancellationException("Account changed")
    }
    private fun requirePrincipal(user:String)=synchronized(lock) {
        if(sessionIdentity!=null && sessionIdentity.invoke()?.userId!=user && !(user.isEmpty() && emptyPrincipalCurrent())) {
            ++run;clearVerification()
            mutableState.value=NativeAccountTeamsState(error="Your account changed. Refresh your account.")
            throw CancellationException("Account identity changed")
        }
    }

    fun clear() {
        val old = synchronized(lock) {
            ++run
            clearVerification()
            presentationLogin = null
            presentationIdentity=null
            presentationGeneration=null
            mutableState.value = NativeAccountTeamsState()
            calls.toList().also { calls.clear() }
        }
        old.forEach { it.cancel() }
    }

    override fun close() { synchronized(lock) { closed = true }; clear() }

    /** Auth owner changes discard live authority; cache restoration is display-only. */
    fun reconcileLogin() {
        val old = synchronized(lock) {
            if (closed) return
            val owner = login()
            val identity=sessionIdentity?.invoke()
            val generation=sessionGeneration?.invoke()
            if (presentationLogin == owner && presentationIdentity==identity && presentationGeneration==generation) return
            ++run; clearVerification(); presentationLogin = owner;presentationIdentity=identity;presentationGeneration=generation
            mutableState.value = restore(owner,identity)
            calls.toList().also { calls.clear() }
        }
        old.forEach { it.cancel() }
    }

    private fun restore(owner: String?,identity:NativeAuthIdentity?): NativeAccountTeamsState {
        val selected=runCatching{cache?.rememberedSelection(cacheEnvironment)}.getOrNull()
        val cached=owner?.let { runCatching { cache?.read(it, cacheEnvironment) }.getOrNull() }
        val displayedId=identity?.userId ?: accountUser?.invoke()?.id?.takeIf{it.isEmpty()}
        val value=if(sessionIdentity==null)cached ?: NativeAccountTeamsState()
            else cached?.takeIf { displayedId!=null && it.userId==displayedId } ?: NativeAccountTeamsState(userId=displayedId)
        return value.copy(selectedTeamId=selected)
    }

    private fun credential(value: String?): String = value?.takeIf {
        it.isNotBlank() && it.length <= 8192 && it.none { ch -> ch == '\r' || ch == '\n' }
    } ?: throw IOException("Sign in to cmux")

    private fun identifier(value: String) = value.also { if (it.isBlank() || it.length > 128) throw IOException("Invalid account identity") }
    companion object {
        internal const val NETWORK_TIMEOUT_MILLIS=60_000L
        private const val MAX_REPLY=2*1024*1024
    }
}
