package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import javax.crypto.SecretKey

/** Published account identity with production team-controller wiring, encrypted fixtures and local HTTP. */
class NativeTeamIdentityRuntimeTest {
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val a=NativeSignInUser("account-a","Account A","a@example.test","one")
    private val b=NativeSignInUser("account-b","Account B","b@example.test","one")
    private val controllers=mutableListOf<NativeAccountTeams>()
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    private fun token()="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("exp",System.currentTimeMillis()/1000+3600).toString().toByteArray())+".fixture"
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun seed(server:MockWebServer,cached:Boolean=true) {
        store.update { root->
            root.put("task_session","same-login").put("access_token",token()).put("refresh_token","saved-refresh").put("fixture_pairing_marker","retain")
            if(cached)NativeAuthUserCache.write(root,client(server).accountEnvironment,a)
        }
    }
    private fun account(server:MockWebServer,reader:NativeCredentialStore=store)=NativeAccount(reader,client(server),NativeOAuthBrowser())
    private fun controller(server:MockWebServer,account:NativeAccount,reader:NativeCredentialStore=store)=
        NativeAccountTeams(account,reader,server.url("/api/v1/")).also {controllers+=it}
    private fun teamReply()=MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"}]}")
    private fun userReply(user:NativeSignInUser)=MockResponse().setBody(JSONObject().put("id",user.id).put("display_name",user.name)
        .put("primary_email",user.email).put("selected_team",JSONObject().put("id","one")).toString())
    private suspend fun establish(server:MockWebServer,teams:NativeAccountTeams):Pair<NativeTeamScope,NativeTeamContinuation> {
        server.teamUsers(2){userReply(a)};server.enqueue(teamReply());val owner=checkNotNull(teams.refreshAfterSignIn("same-login",a).scope)
        return owner to checkNotNull(teams.captureContinuation(owner))
    }
    private fun record(case:String,values:JSONObject) { File(target.getExternalFilesDir(null),"team-identity").apply {mkdirs()}
        .resolve("$case.json").writeText(values.put("fixtureOnly",true).toString(2)) }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="team-identity-fixture-"+UUID.randomUUID();alias="cmux_team_identity_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(target,name,alias,null)
    }
    @After fun cleanup() {scope.cancel();controllers.forEach {it.close()};store.clear();target.deleteSharedPreferences(name);keys().deleteEntry(alias)}
    @Test fun actualRestorationPublishesTheNewUserAndRetiresOldTeamProofBeforeMembershipFinishes()=runBlocking<Unit> {
        MockWebServer().use { server->
            seed(server);val account=account(server);val teams=controller(server,account)
            val (old,proof)=establish(server,teams);val original=checkNotNull(account.sessionRestore.identity())
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            account.onSignedIn={login,user->entered.complete(Unit);release.await();teams.refreshAfterSignIn(login,user)}
            server.teamUsers(3){userReply(b)};server.enqueue(teamReply());account.sessionRestore.start(scope)
            try {
                withTimeout(5000){entered.await()}
                assertEquals(b.id,account.sessionRestore.identity()?.userId);assertFalse(account.sessionRestore.isIdentityCurrent(original))
                assertEquals("same-login",store.taskSession());assertFalse(teams.isCurrent(old));assertFalse(teams.allowsContinuation(proof))
                assertEquals(4,server.requestCount);release.complete(Unit);account.sessionRestore.awaitBootstrapped()
                val fresh=checkNotNull(teams.state.value.scope);assertEquals(b.id,fresh.userId);assertTrue(teams.isCurrent(fresh))
                assertFalse(teams.allowsContinuation(proof));assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
                record("new-user-before-membership",JSONObject().put("unchangedStoredLogin",true).put("publishedNewUser",true)
                    .put("oldAdmissionAndContinuationRetiredBeforeMembership",true).put("newMembershipAdmitted",true).put("pairingPreserved",true))
            } finally {release.complete(Unit)}
        }
    }
    @Test fun actualSameUserRevalidationKeepsTheExistingScopeAndConnectionContinuation()=runBlocking<Unit> {
        MockWebServer().use { server->
            seed(server);val account=account(server);val teams=controller(server,account)
            val (old,proof)=establish(server,teams);val original=account.sessionRestore.identity()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            account.onSignedIn={login,user->entered.complete(Unit);release.await();teams.refreshAfterSignIn(login,user)}
            server.teamUsers(3){userReply(a.copy(name="Updated A"))};server.enqueue(teamReply());account.sessionRestore.start(scope)
            try {
                withTimeout(5000){entered.await()};assertEquals(original,account.sessionRestore.identity())
                assertTrue(teams.isCurrent(old));assertTrue(teams.allowsContinuation(proof));release.complete(Unit)
                account.sessionRestore.awaitBootstrapped();assertEquals(old,teams.state.value.scope);assertTrue(teams.allowsContinuation(proof))
                record("same-user-revalidation",JSONObject().put("generationStable",true).put("existingAdmissionAndContinuationKept",true).put("profileUpdated",account.sessionRestore.state.value.user?.name=="Updated A"))
            } finally {release.complete(Unit)}
        }
    }
    @Test fun localSignOutRetiresTeamProofEvenWhileTheEncryptedDocumentIsUnavailable()=runBlocking<Unit> {
        MockWebServer().use { server->
            seed(server);var unavailable=false
            val reader=NativeCredentialStore.withKeyProvider(target,name,alias) {if(unavailable)throw KeyStoreException("Fixture unavailable");keys().getKey(alias,null) as SecretKey}
            val account=account(server,reader);val teams=controller(server,account,reader);val (old,proof)=establish(server,teams)
            val cipher=target.getSharedPreferences(name,0).getString("state",null);unavailable=true
            assertTrue(account.signOut());assertNull(account.sessionRestore.identity());assertFalse(teams.isCurrent(old));assertFalse(teams.allowsContinuation(proof))
            assertEquals(cipher,target.getSharedPreferences(name,0).getString("state",null))
            unavailable=false;account.retryLocalLogout();assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
            assertFalse(teams.allowsContinuation(proof));record("unavailable-signout",JSONObject().put("publishedIdentityClearedImmediately",true)
                .put("oldTeamProofRetired",true).put("ciphertextPreservedUntilReplay",true).put("pairingPreserved",true))
        }
    }
    @Test fun aNewSameUserPublicationAndReverifiedMembershipCannotReviveTheOldProof()=runBlocking<Unit> {
        MockWebServer().use { server->
            seed(server);val account=account(server);val teams=controller(server,account);val (old,proof)=establish(server,teams)
            val original=checkNotNull(account.sessionRestore.identity());account.sessionRestore.publishedSignIn(a)
            assertFalse(account.sessionRestore.isIdentityCurrent(original));assertFalse(teams.isCurrent(old));assertFalse(teams.allowsContinuation(proof))
            server.teamUsers(2){userReply(a)};server.enqueue(teamReply());val fresh=checkNotNull(teams.refreshAfterSignIn("same-login",a).scope)
            assertNotEquals(old.generation,fresh.generation);assertTrue(teams.isCurrent(fresh));assertFalse(teams.allowsContinuation(proof))
            record("same-user-new-publication",JSONObject().put("controlledVerifiedPublication",true).put("newGenerationRequired",true).put("oldProofNeverRevived",true))
        }
    }
    @Test fun rawStoredCredentialsCannotAdmitTeamsUntilActualUserRestorationPublishesAnIdentity()=runBlocking<Unit> {
        MockWebServer().use { server->
            seed(server,cached=false);val account=account(server);val teams=controller(server,account)
            assertNull(account.sessionRestore.identity());assertEquals(0,server.requestCount);assertNull(teams.state.value.scope)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.requestUrl!!.encodedPath.endsWith("/users/me")) {
                        entered.countDown();check(release.await(10,TimeUnit.SECONDS));return userReply(a)
                    }
                    return teamReply()
                }
            }
            val restoring=async(Dispatchers.IO) {teams.refresh()}
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(1,server.requestCount)
                assertNull(account.sessionRestore.identity());assertNull(teams.state.value.scope);release.countDown()
                assertNotNull(restoring.await().scope)
            } finally {release.countDown();restoring.cancel()}
            assertEquals(a.id,account.sessionRestore.identity()?.userId);assertNotNull(teams.state.value.scope);assertEquals(4,server.requestCount)
            record("raw-credentials",JSONObject().put("noRawTokenAuthority",true).put("noPrematureMembership",true)
                .put("privateBackgroundRestoration",true).put("actualUserThenMembershipAdmitted",true).put("httpRequests",server.requestCount))
        }
    }
    @Test fun aBackendUserMismatchRetiresOldMembershipWithoutRequestingTheOtherUsersTeams()=runBlocking<Unit> {
        MockWebServer().use { server->
            seed(server);val account=account(server);server.teamUsers{userReply(a)};account.sessionRestore.start(scope);account.sessionRestore.awaitBootstrapped()
            val teams=controller(server,account);val (old,proof)=establish(server,teams)
            server.teamUsers(2){userReply(b)};assertTrue(runCatching{teams.refresh()}.isFailure)
            assertEquals(6,server.requestCount);assertEquals(a.id,account.sessionRestore.identity()?.userId)
            assertNull(teams.state.value.scope);assertFalse(teams.isCurrent(old));assertFalse(teams.allowsContinuation(proof))
            record("backend-user-mismatch",JSONObject().put("mismatchedUserNeverAdmitted",true).put("noOtherUserMembershipRequest",true).put("oldProofRetired",true))
        }
    }
    @Test fun coldPrivateCachedOwnersRevalidateBeforeLoadingMembershipForTheirPublishedUser()=runBlocking<Unit> {
        MockWebServer().use {server->
            seed(server);val account=account(server);val teams=controller(server,account)
            assertTrue(account.sessionRestore.needsIdentityValidation);assertEquals(a.id,account.sessionRestore.identity()?.userId)
            server.teamUsers{MockResponse().setResponseCode(503)}
            assertTrue(runCatching {teams.refresh()}.isFailure);assertEquals(1,server.requestCount)
            assertNull(teams.state.value.scope);assertTrue(account.sessionRestore.needsIdentityValidation)
            assertEquals(a.id,account.sessionRestore.identity()?.userId)
            server.teamUsers(3){userReply(b)};server.enqueue(teamReply());val fresh=teams.refresh()
            assertFalse(account.sessionRestore.needsIdentityValidation);assertEquals(b.id,account.sessionRestore.identity()?.userId)
            assertEquals(b.id,fresh.userId);assertTrue(teams.isCurrent(checkNotNull(fresh.scope)));assertEquals(5,server.requestCount)
            record("cold-cached-background",JSONObject().put("cachedUserRevalidated",true).put("membershipMatchesPublishedUser",true)
                .put("failedValidationPreservedCacheWithoutGrantingAuthority",true).put("freshUserAndSelectionReadsAfterValidation",true).put("httpRequests",server.requestCount))
        }
    }
}
