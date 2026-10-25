package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey

/** Production account/Cloud composition over disposable encrypted state and local HTTP, on the owned emulator only. */
class NativeCloudContextRuntimeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val ui=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val controllers=mutableListOf<NativeAccountTeams>();private val owned=mutableListOf<CloudMachinesController>()
    private val releases=mutableListOf<CountDownLatch>()
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    private fun token(expired:Boolean=false)="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("exp",System.currentTimeMillis()/1000+(if(expired)-3600 else 3600)).toString().toByteArray())+".fixture"
    private fun client(server:MockWebServer)=NativeOAuthClient(NativeAccount.PROJECT_ID,"fixture-key",server.url("/"))
    private fun account(server:MockWebServer,reader:NativeCredentialStore=store)=NativeAccount(reader,client(server),NativeOAuthBrowser(),tokenRefreshLeadMillis=120_000)
    private fun teams(server:MockWebServer,account:NativeAccount)=NativeAccountTeams(account,store,server.url("/api/v1/")).also{controllers+=it}
    private fun seed(server:MockWebServer,access:String=token(),refresh:String="refresh",user:String="cloud-user",selected:String?=null) {
        store.update{root->root.put("task_session","cloud-login").put("access_token",access).put("refresh_token",refresh)
            .put("fixture_pairing_marker","retain").remove(NativeSignInCompletion.PENDING)
            root.remove(NativeAccountProfileCache.KEY)
            NativeAuthUserCache.write(root,client(server).accountEnvironment,NativeSignInUser(user,"Cloud User",null,selected))
            NativeTeamSelectionStore.write(root,client(server).accountEnvironment,selected)
        }
    }
    private fun serve(server:MockWebServer,user:String="cloud-user",selected:String?=null,fresh:String=token(),
        refreshReply:(()->MockResponse)?=null):AtomicReference<String?> {
        val selection=AtomicReference<String?>(selected)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when {
            request.path=="/api/v1/auth/oauth/token"->refreshReply?.invoke() ?: MockResponse().setBody(JSONObject().put("access_token",fresh).toString())
            request.path=="/api/v1/users/me" && request.method=="GET"->MockResponse().setBody(JSONObject().put("id",user).put("display_name","Cloud User")
                .put("selected_team",selection.get()?.let{JSONObject().put("id",it)} ?: JSONObject.NULL).toString())
            request.path=="/api/v1/users/me" && request.method=="PATCH"->{selection.set(JSONObject(request.body.readUtf8()).opt("selected_team_id") as? String);MockResponse().setResponseCode(204)}
            request.path=="/api/v1/teams?user_id=me"->MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"},{\"id\":\"two\",\"display_name\":\"Two\"}]}")
            else->MockResponse().setResponseCode(500)
        }}
        return selection
    }
    private fun cloud(server:MockWebServer,reply:((RecordedRequest)->MockResponse)?=null) {
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=reply?.invoke(request) ?: when(request.path){
            "/api/billing/plan"->MockResponse().setBody("{\"authenticated\":true,\"user\":{\"id\":\"cloud-user\"},\"planId\":\"free\",\"billingSource\":\"none\",\"billingManagement\":\"none\",\"billingAvailable\":false}")
            "/api/vm"->MockResponse().setBody("{\"vms\":[]}")
            else->MockResponse().setResponseCode(500)
        }}
    }
    private fun requests(server:MockWebServer)=CloudApiRequests(server.url("/").toString())
    private fun cipher()=context.getSharedPreferences(name,0).getString("state",null)
    private fun gate()=CountDownLatch(1).also{releases+=it}
    private suspend fun drain(){withTimeout(5000){while(store.authWork.activeCount>0)delay(2)}}
    private fun record(case:String,values:JSONObject){assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
        File(context.getExternalFilesDir(null),"cloud-context").apply{mkdirs()}.resolve("$case.json").writeText(values
            .put("fixtureOnly",true).put("encryptedStoreUsed",true).put("pairingMarkerPreserved",true).toString(2))}
    private fun image(name:String){val file=File(context.getExternalFilesDir(null),"cloud-context/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
    private fun model(account:NativeAccount,teams:NativeAccountTeams,backend:MockWebServer):NativeCloudViewModel {
        val transport=requests(backend)
        lateinit var value:NativeCloudViewModel
        compose.runOnIdle{value=ViewModelProvider(compose.activity,object:ViewModelProvider.Factory{
            @Suppress("UNCHECKED_CAST") override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T=
                NativeCloudViewModel(context,account,store,teams,apiRequests=transport) as T
        })[NativeCloudViewModel::class.java]}
        return value
    }
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="cloud-context-"+UUID.randomUUID();alias="cmux_cloud_context_"+UUID.randomUUID();store=NativeCredentialStore.withKeyProvider(context,name,alias)}
    @After fun cleanup(){releases.forEach{it.countDown()};compose.runOnIdle{owned.forEach{it.close()};compose.activity.viewModelStore.clear()}
        controllers.forEach{it.close()};scope.cancel();ui.cancel();runBlocking{scope.coroutineContext[Job]!!.join();ui.coroutineContext[Job]!!.join();drain()}
        store.clear();context.deleteSharedPreferences(name);keys().deleteEntry(alias)}

    @Test fun completeOriginalCloudAndCurrentTokenDeclarationsRunOnArt()=runBlocking<Unit> {
        store.update{it.put("fixture_pairing_marker","retain")}
        val source=InstrumentationRegistry.getInstrumentation().context.assets.open("native-cloud-context-reference.json").bufferedReader().use{it.readText()}
        record("original-reference",JSONObject().put("cases",checkNativeCloudContextReference(source)))
    }
    @Test fun freshAndExpiredCompletePairsUseLiveCoherenceBeforeCloudHttp()=runBlocking<Unit> {
        val rows=JSONArray()
        for(expired in listOf(false,true))MockWebServer().use{stack->MockWebServer().use{backend->
            val initial=token(expired);val fresh=token();seed(stack,initial);serve(stack,fresh=fresh);cloud(backend)
            val account=account(stack);val teams=teams(stack,account);val owner=checkNotNull(account.cloudScope(teams));assertNull(owner.team)
            nativeCloudApi(account,teams,owner,requests(backend)).use{assertTrue(it.catalog().machines.isEmpty())}
            val request=checkNotNull(backend.takeRequest(2,TimeUnit.SECONDS))
            assertEquals("Bearer ${if(expired)fresh else initial}",request.getHeader("Authorization"))
            assertEquals("refresh",request.getHeader("X-Stack-Refresh-Token"));assertNull(request.getHeader("X-Cmux-Team-Id"))
            assertEquals(if(expired)1 else 0,stack.requestCount);assertEquals(1,backend.requestCount);assertNull(teams.state.value.scope)
            rows.put(JSONObject().put("expired",expired).put("sdkRequests",stack.requestCount).put("backendRequests",1).put("noNativeMembershipInvented",true))
        }}
        record("freshness",JSONObject().put("rows",rows))
    }
    @Test fun missingRefreshMapsToSignedOutCloudWithoutClearingAccountPresentation()=runBlocking<Unit> {
        val rows=JSONArray()
        for(noReceipt in listOf(false,true))MockWebServer().use{stack->MockWebServer().use{backend->seed(stack,refresh="");serve(stack);cloud(backend)
            if(noReceipt)store.update{it.remove("task_session")}
            val account=account(stack);val teams=teams(stack,account);val owner=checkNotNull(account.cloudScope(teams));val before=cipher()
            nativeCloudApi(account,teams,owner,requests(backend)).use{assertTrue(runCatching{it.catalog()}.exceptionOrNull() is CloudNotSignedIn)}
            assertEquals(before,cipher());assertEquals(0,stack.requestCount);assertEquals(0,backend.requestCount);assertTrue(account.sessionRestore.state.value.authenticated)
            assertEquals(owner,account.cloudScope(teams));if(noReceipt)assertNull(store.taskSession())
            assertNull(store.refreshView().owner);assertNull(teams.state.value.scope)
            rows.put(JSONObject().put("noDurableReceipt",noReceipt).put("cloudSignedOutProjection",true).put("ciphertextPreserved",true)
                .put("accountPresentationPreserved",true).put("noRefreshOrNativeAuthority",true))
        }}
        record("missing-refresh",JSONObject().put("rows",rows))
    }
    @Test fun transientLegacyRestoreBindsItsMigratedReceiptWithoutInventingMembership()=runBlocking<Unit> {
        val rows=JSONArray()
        for(earlierReader in listOf(false,true))MockWebServer().use{stack->MockWebServer().use{backend->seed(stack);store.update{it.remove("task_session")};cloud(backend)
            stack.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setResponseCode(503)}
            val account=NativeAccount(store,NativeOAuthClient(NativeAccount.PROJECT_ID,"fixture-key",stack.url("/"),sdkRetrySleep={}),NativeOAuthBrowser())
            val earlyTeams=if(earlierReader)teams(stack,account) else null
            withTimeout(10000){account.sessionRestore.restoreIdentityIfNeeded()}
            val teams=earlyTeams ?: teams(stack,account)
            val login=checkNotNull(store.taskSession());assertTrue(account.sessionRestore.state.value.authenticated)
            assertFalse(account.sessionRestore.state.value.restoring);assertNull(account.sessionRestore.verifiedUser());assertNull(teams.state.value.scope)
            val owner=checkNotNull(account.cloudScope(teams));assertEquals(login,owner.login)
            val stackRequests=stack.requestCount
            nativeCloudApi(account,teams,owner,requests(backend)).use{assertTrue(it.catalog().machines.isEmpty())}
            assertEquals(stackRequests,stack.requestCount);assertEquals(1,backend.requestCount)
            assertEquals(login,account.authenticatedSessionSnapshot().owner.login)
            rows.put(JSONObject().put("receiptCreatedByEarlierReader",earlierReader).put("migratedReceiptBoundToCachedOwner",true)
                .put("transientValidationPreservedAccount",true).put("noNativeMembershipInvented",true).put("cloudBackendRequests",1))
        }}
        record("legacy-preserved",JSONObject().put("rows",rows).put("sdkRetrySleepIsFixture",true))
    }
    @Test fun unavailableStorageIsRetryableForCloudAndDeletionWithoutLosingItsPublishedOwner()=runBlocking<Unit> {
        MockWebServer().use{stack->MockWebServer().use{backend->seed(stack);serve(stack);cloud(backend);var unavailable=false
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(unavailable)throw KeyStoreException("Fixture unavailable");keys().getKey(alias,null) as SecretKey}
            val account=account(stack,reader);val teams=teams(stack,account);val owner=checkNotNull(account.cloudScope(teams));val identity=account.authenticatedSessionSnapshot().owner
            val before=cipher();unavailable=true
            try {
                assertTrue(runCatching{reader.requireReadable()}.exceptionOrNull() is NativeCredentialReadException)
                nativeCloudApi(account,teams,owner,requests(backend)).use{
                    assertEquals(NativeSignInError.NETWORK_ERROR,(runCatching{it.catalog()}.exceptionOrNull() as NativeSignInFailure).reason)}
                assertEquals(NativeSignInError.NETWORK_ERROR,(runCatching{account.deletionCredentials(identity)}.exceptionOrNull() as NativeSignInFailure).reason)
                assertEquals(owner,account.cloudScope(teams));assertEquals(identity.identity,account.sessionRestore.identity());assertEquals(before,cipher())
                assertEquals(0,stack.requestCount);assertEquals(0,backend.requestCount)
            }finally{unavailable=false}
            record("unavailable",JSONObject().put("cloudAndDeletionRetryable",true).put("strictStoreFailureRetained",true).put("ciphertextPreserved",true).put("ownerPreserved",true))
        }}
    }
    @Test fun realTeamSelectionRoundTripCannotPublishAnOldCloudResponse()=runBlocking<Unit> {
        MockWebServer().use{stack->MockWebServer().use{backend->seed(stack,selected="one");serve(stack,selected="one")
            val entered=gate();val release=gate();cloud(backend){entered.countDown();check(release.await(10,TimeUnit.SECONDS));MockResponse().setBody("{\"vms\":[]}")}
            val account=account(stack);account.sessionRestore.restoreIdentityIfNeeded();val teams=teams(stack,account);teams.refresh()
            val original=checkNotNull(account.cloudScope(teams));val api=nativeCloudApi(account,teams,original,requests(backend));val call=scope.async{runCatching{api.catalog()}.exceptionOrNull()}
            try {
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                teams.select("two");assertEquals("two",account.cloudScope(teams)!!.team)
                teams.select("one");val current=checkNotNull(account.cloudScope(teams));assertEquals("one",current.team);assertNotEquals(original,current)
                release.countDown();assertTrue(withTimeout(5000){call.await()} is CancellationException)
                assertEquals(1,backend.requestCount);assertEquals("one",backend.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("X-Cmux-Team-Id"))
                val patches=(0 until stack.requestCount).map{checkNotNull(stack.takeRequest(2,TimeUnit.SECONDS))}.filter{it.method=="PATCH"}
                assertEquals(2,patches.size)
                record("selection-round-trip",JSONObject().put("realSelectionPatches",2).put("oldResponseRejected",true).put("noMixedTeamHeader",true))
            }finally{release.countDown();call.cancelAndJoin();api.close()}
        }}
    }
    @Test fun replacementDuringRealRefreshCannotSendCloudWithAnotherPublishedUser()=runBlocking<Unit> {
        MockWebServer().use{stack->MockWebServer().use{backend->seed(stack,token(true));val entered=gate();val release=gate()
            serve(stack,refreshReply={entered.countDown();check(release.await(10,TimeUnit.SECONDS));MockResponse().setBody(JSONObject().put("access_token",token()).toString())});cloud(backend)
            val account=account(stack);val teams=teams(stack,account);val owner=checkNotNull(account.cloudScope(teams));val api=nativeCloudApi(account,teams,owner,requests(backend))
            val call=scope.async{runCatching{api.catalog()}.exceptionOrNull()}
            try {assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                account.sessionRestore.publishedSignIn(NativeSignInUser("replacement","Replacement",null,null));release.countDown()
                assertTrue(withTimeout(5000){call.await()} is CancellationException);assertEquals(0,backend.requestCount)
                assertEquals("replacement",account.sessionRestore.state.value.user!!.id)
                record("replacement",JSONObject().put("oldCloudRequestNeverSent",true).put("replacementPresentationPreserved",true).put("publicationMutationIsFixture",true))
            }finally{release.countDown();call.cancelAndJoin();api.close();drain()}
        }}
    }
    @Test fun cloudCaptureDeadlineLeavesAnotherSdkWaiterAliveWithoutSendingTheBackendRequest()=runBlocking<Unit> {
        MockWebServer().use{stack->MockWebServer().use{backend->seed(stack,token(true));val fresh=token();val entered=gate();val release=gate()
            serve(stack,refreshReply={entered.countDown();check(release.await(10,TimeUnit.SECONDS));MockResponse().setBody(JSONObject().put("access_token",fresh).toString())});cloud(backend)
            val account=account(stack);account.cloudCaptureTimeoutMillis=1000;val teams=teams(stack,account);val owner=checkNotNull(account.cloudScope(teams))
            val api=nativeCloudApi(account,teams,owner,requests(backend));val started=android.os.SystemClock.elapsedRealtime()
            val call=scope.async{runCatching{api.catalog()}.exceptionOrNull()};var keeper:Deferred<String?>?=null
            try {assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                keeper=scope.async{account.sdkAccessToken()};withTimeout(1000){while(store.refreshCoordinator(client(stack).accountEnvironment).waiterCount<2)delay(1)}
                assertEquals(NativeSignInError.TIMED_OUT,(withTimeout(4000){call.await()} as NativeSignInFailure).reason)
                val elapsed=android.os.SystemClock.elapsedRealtime()-started;assertTrue(elapsed>=1000 && elapsed<4000)
                assertTrue(store.authWork.activeCount>0);assertEquals(0,backend.requestCount);release.countDown()
                assertEquals(fresh,withTimeout(5000){checkNotNull(keeper).await()});drain();assertEquals(owner,account.cloudScope(teams))
                record("deadline",JSONObject().put("configuredMillis",1000).put("elapsedMillis",elapsed).put("anotherSdkWaiterRetained",true)
                    .put("backendRequests",0).put("accountScopePreserved",true))
            }finally{release.countDown();call.cancelAndJoin();keeper?.cancelAndJoin();api.close();drain()}
        }}
    }
    @Test fun vpnBindingDoesNoCredentialIoAndRetirementRevokesOnlyWithItsCapturedBrowserPair()=runBlocking<Unit> {
        MockWebServer().use{stack->MockWebServer().use{backend->val initial=token(true);val fresh=token();seed(stack,initial);serve(stack,fresh=fresh)
            cloud(backend){request->if(request.method=="DELETE")MockResponse().setResponseCode(204) else MockResponse().setBody(
                "{\"tunnelId\":\"tunnel\",\"provider\":\"fixture\",\"deviceFingerprint\":\"fingerprint\",\"clientConfig\":\"fixture\",\"serverPublicKey\":\"key\",\"endpointHost\":\"127.0.0.1\",\"endpointPort\":51820}")}
            val account=account(stack);val teams=teams(stack,account);val owner=checkNotNull(account.cloudScope(teams));val devices=AtomicInteger()
            val access=nativeCloudVpnAccess(context,account,teams,owner,requests(backend)){
                assertEquals(1,stack.requestCount);devices.incrementAndGet();CloudVpnDevice("device","fingerprint")}
            assertEquals(0,stack.requestCount);assertEquals(0,backend.requestCount);assertEquals(0,devices.get())
            val device=access.device();assertEquals(1,stack.requestCount);assertEquals(1,devices.get())
            access.enroll(CloudWireGuardKey.generate(),device);assertEquals(1,backend.requestCount)
            account.sessionRestore.publishedSignIn(NativeSignInUser("replacement","Replacement",null,null))
            store.update{it.put("access_token","replacement-access").put("refresh_token","replacement-refresh")}
            access.revoke("fingerprint");assertEquals(2,backend.requestCount)
            val post=backend.takeRequest(2,TimeUnit.SECONDS)!!;val delete=backend.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("POST",post.method);assertEquals("DELETE",delete.method)
            for(request in listOf(post,delete)){assertEquals("Bearer $fresh",request.getHeader("Authorization"));assertEquals("refresh",request.getHeader("X-Stack-Refresh-Token"))
                assertEquals("browser",JSONObject(request.body.readUtf8()).getString("tunnelPurpose"));assertNull(request.getHeader("X-Cmux-Team-Id"))}
            record("vpn-capture",JSONObject().put("bindingSdkRequests",0).put("bindingDeviceResolutions",0).put("captureBeforeIdentity",true)
                .put("retiredBrowserRevokeUsesCapturedPair",true).put("noRealVpnOrDeviceIdentity",true))
        }}
    }
    @Test fun vpnCredentialFailuresBecomeNilAndCannotEnroll()=runBlocking<Unit> {
        val rows=JSONArray()
        for(kind in listOf("missing","unavailable","transient"))MockWebServer().use{stack->MockWebServer().use{backend->
            seed(stack,token(kind=="transient"),if(kind=="missing")"" else "refresh");serve(stack,refreshReply={MockResponse().setResponseCode(503)});cloud(backend)
            var unavailable=false;val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(unavailable)throw KeyStoreException("Fixture unavailable");keys().getKey(alias,null) as SecretKey}
            val account=account(stack,reader);val teams=teams(stack,account);val owner=checkNotNull(account.cloudScope(teams));val deviceCalls=AtomicInteger()
            val access=nativeCloudVpnAccess(context,account,teams,owner,requests(backend)){deviceCalls.incrementAndGet();CloudVpnDevice("device","fingerprint")}
            assertEquals(0,stack.requestCount);unavailable=kind=="unavailable"
            try {val device=access.device();assertEquals(1,deviceCalls.get())
                assertTrue(runCatching{access.enroll(CloudWireGuardKey.generate(),device)}.exceptionOrNull() is CloudNotSignedIn)
                assertEquals(0,backend.requestCount);assertEquals(if(kind=="transient")1 else 0,stack.requestCount)
            }finally{unavailable=false;drain()}
            rows.put(JSONObject().put("case",kind).put("deviceResolvedAfterNilCredentials",true).put("backendRequests",0))
        }}
        record("vpn-nil",JSONObject().put("rows",rows))
    }
    @Test fun emptySuccessfulUserCanReadCloudWithoutAnAuthenticatedNativeSnapshot()=runBlocking<Unit> {
        MockWebServer().use{stack->MockWebServer().use{backend->seed(stack,user="");serve(stack,user="");cloud(backend)
            val account=account(stack);val teams=teams(stack,account);val owner=checkNotNull(account.cloudScope(teams));assertEquals("",owner.user)
            nativeCloudApi(account,teams,owner,requests(backend)).use{assertTrue(it.catalog().machines.isEmpty())}
            assertNull(account.sessionRestore.identity());assertNull(teams.state.value.scope)
            assertEquals(NativeSignInError.UNAUTHORIZED,(runCatching{account.authenticatedSessionSnapshot()}.exceptionOrNull() as NativeSignInFailure).reason)
            assertEquals(0,stack.requestCount);assertEquals(1,backend.requestCount)
            record("empty-user",JSONObject().put("cloudBackendReadAllowed",true).put("nativeSnapshotDenied",true).put("nativeMembershipNotInvented",true))
        }}
    }
    @Test fun mountedPersonalCloudLoadsWithoutNativeMembership()=runBlocking<Unit> {
        MockWebServer().use{stack->MockWebServer().use{backend->seed(stack);serve(stack);cloud(backend)
            val account=account(stack);account.sessionRestore.restoreIdentityIfNeeded();val teams=teams(stack,account);val model=model(account,teams,backend)
            compose.runOnIdle{model.setForeground(true)}
            compose.setContent{CmuxTheme{Surface{NativeCloudScreen(model.controller.collectAsState().value,{}, {},Modifier.fillMaxSize())}}}
            compose.waitUntil(5000){model.controller.value?.state?.value?.phase==CloudCatalogPhase.LOADED}
            compose.onNodeWithText("No Cloud machines yet. Create one below.").assertIsDisplayed();compose.onNodeWithText("Waiting for your account").assertDoesNotExist()
            assertNull(teams.state.value.scope);assertNull(account.cloudScope(teams)!!.team);assertEquals(1,backend.requestCount)
            assertNull(backend.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("X-Cmux-Team-Id"));image("personal-cloud")
            record("mounted-personal",JSONObject().put("productionViewModelLoaded",true).put("noNativeMembership",true).put("backendRequests",1))
        }}
    }
    @Test fun mountedCloudTransientFailureRetriesThroughTheLiveCredentialAdapter()=runBlocking<Unit> {
        MockWebServer().use{stack->MockWebServer().use{backend->seed(stack,token(true));val status=AtomicInteger(503);val fresh=token()
            serve(stack,refreshReply={if(status.get()==503)MockResponse().setResponseCode(503) else MockResponse().setBody(JSONObject().put("access_token",fresh).toString())});cloud(backend)
            val account=account(stack);val teams=teams(stack,account);val owner=checkNotNull(account.cloudScope(teams));lateinit var controller:CloudMachinesController
            val transport=requests(backend)
            compose.runOnIdle{controller=CloudMachinesController(ui,nativeCloudApi(account,teams,owner,transport),object:CloudCreateJournal{
                override suspend fun resolve(options:CloudMachineCreateOptions):String=error("No create in this read/retry fixture")
                override suspend fun complete(key:String):Unit=error("No create in this read/retry fixture")
            },{account.isCloudScopeCurrent(teams,owner)},listRetryLimit=1)
                owned+=controller;controller.refresh()}
            compose.setContent{CmuxTheme{Surface{NativeCloudScreen(controller,{}, {},Modifier.fillMaxSize())}}}
            compose.waitUntil(5000){controller.state.value.phase==CloudCatalogPhase.FAILED}
            assertFalse(controller.state.value.failure!!.signedOut);assertTrue(controller.state.value.failure!!.retryable);assertEquals(0,backend.requestCount)
            compose.onNodeWithText("The cloud service could not be reached. Try again in a moment.").assertIsDisplayed()
            status.set(200);compose.onNodeWithText("Retry").performClick();compose.waitUntil(5000){controller.state.value.phase==CloudCatalogPhase.LOADED}
            compose.onNodeWithText("No Cloud machines yet. Create one below.").assertIsDisplayed();assertEquals(2,stack.requestCount);assertEquals(1,backend.requestCount)
            image("cloud-retry");record("mounted-retry",JSONObject().put("productionCredentialAdapter",true).put("manualRetryLoaded",true)
                .put("fixtureListRetryLimit",1).put("accountPresentationPreserved",account.sessionRestore.state.value.authenticated))
        }}
    }
    @Test fun mountedPersonalPlanNoLongerRequiresANativeTeamScope()=runBlocking<Unit> {
        MockWebServer().use{stack->MockWebServer().use{backend->seed(stack);serve(stack);cloud(backend)
            val account=account(stack);account.sessionRestore.restoreIdentityIfNeeded();val teams=teams(stack,account);val requests=requests(backend)
            compose.setContent{CmuxTheme{Surface{Column{NativeAccountPlanSettings(account,teams,requests)}}}}
            compose.waitUntil(5000){backend.requestCount>=1};compose.onNodeWithTag("settings.plan").assertIsDisplayed().performClick()
            compose.onNodeWithTag("plan.title").assertIsDisplayed();compose.waitUntil(5000){backend.requestCount==2}
            compose.onNodeWithTag("plan.done").performClick();compose.onNodeWithTag("settings.plan").assertIsDisplayed()
            assertNull(teams.state.value.scope);repeat(2){assertNull(backend.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("X-Cmux-Team-Id"))}
            record("mounted-plan",JSONObject().put("personalPlanAccessible",true).put("nativeScopeRequired",false).put("backendRequests",2).put("noBillingMutation",true))
        }}
    }
    @Test fun nativeMembershipPublicationFencesRoutingWithoutClosingTheCloudController()=runBlocking<Unit> {
        MockWebServer().use{stack->MockWebServer().use{backend->seed(stack,selected="one");serve(stack,selected="one");cloud(backend)
            val account=account(stack);val teams=teams(stack,account);val before=checkNotNull(account.cloudScope(teams));val model=model(account,teams,backend)
            compose.waitUntil(5000){model.controller.value!=null};val first=model.controller.value
            account.sessionRestore.restoreIdentityIfNeeded();teams.refresh();compose.waitForIdle()
            assertNotNull(teams.state.value.scope);assertEquals(before,account.cloudScope(teams));assertSame(first,model.controller.value)
            compose.runOnIdle{model.setForeground(true)};compose.waitUntil(5000){model.controller.value?.state?.value?.phase==CloudCatalogPhase.LOADED}
            assertEquals(1,backend.requestCount)
            record("membership-publication",JSONObject().put("sameCloudOwnerRetained",true).put("sameControllerRetained",true).put("nativeMembershipSeparatelyVerified",true))
        }}
    }
}
