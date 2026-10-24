package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeSessionOwnershipTest {
    private val user=NativeSignInUser("account","Account",null,"team")
    private fun root()=JSONObject().put("task_session","login").put("access_token","access")
    @Test fun rawAccessAndAReceiptCannotImpersonateAVerifiedLocalSession() {
        val root=root();assertFalse(nativeSessionOwns(root,"login"))
        NativeAuthUserCache.write(root,"environment",user);assertTrue(nativeSessionOwns(root,"login"))
        assertFalse(nativeSessionOwns(root,"replacement"));assertFalse(nativeSessionOwns(root,null))
        root.put("task_session","replacement");assertFalse(nativeSessionOwns(root,"replacement"))
        assertNull(NativeRefreshOwner.from(root,0))
    }
    @Test fun presentationRetirementRemovesAccessOnlyLocalAuthorityWithoutDeletingTheToken() {
        val root=root();NativeAuthUserCache.write(root,"environment",user)
        NativeAccountProfileCache.writeVerifiedUser(root,"login","environment",user)
        assertTrue(nativeSessionOwns(root,"login"));root.remove(NativeAuthUserCache.KEY)
        NativeAccountProfileCache.prune(root);assertFalse(root.has(NativeAccountProfileCache.KEY))
        assertFalse(nativeSessionOwns(root,"login"));assertEquals("access",root.getString("access_token"))
        root.put("refresh_token","refresh");assertTrue(nativeSessionOwns(root,"login"))
    }
    @Test fun interactiveCacheStagingDoesNotExposePendingCredentialsAsLocalAuthority() {
        val root=root().put("refresh_token","refresh").put(NativeSignInCompletion.PENDING,"login")
        NativeAccountProfileCache.writeVerifiedUser(root,"login","environment",user)
        NativeAccountProfileCache.prune(root);assertTrue(root.has(NativeAccountProfileCache.KEY))
        assertFalse(nativeSessionOwns(root,"login"));assertNull(NativeRefreshOwner.from(root,0))
        root.remove(NativeSignInCompletion.PENDING);assertTrue(nativeSessionOwns(root,"login"))
    }
    @Test fun malformedOrForeignReceiptProofCannotAuthorizeAccessOnlyLocalWrites() {
        for(change in listOf<(JSONObject)->Unit>({it.put("version",2)},{it.put("verified_for_login","other")},
            {it.put("environment",JSONObject.NULL)},{it.put("user",17)})) {
            val root=root();NativeAuthUserCache.write(root,"environment",user)
            change(root.getJSONObject(NativeAuthUserCache.KEY));assertFalse(nativeSessionOwns(root,"login"))
        }
    }
    @Test fun canceledRestoreWaiterDoesNotWaitForTheEncryptedStoreMonitor()=runBlocking<Unit> {
        val lock=Any();val entered=CountDownLatch(1);val release=CountDownLatch(1);val work=NativeAuthWork()
        val model=NativeSessionRestore(true,null,{},{null},{true},{
            synchronized(lock){entered.countDown();check(release.await(5,TimeUnit.SECONDS))};true
        },{error("Canceled probe cannot validate a user")},{null},{true},{},{},work=work,lock=lock)
        val caller=launch(Dispatchers.IO){model.restoreIdentityIfNeeded()}
        try {assertTrue(withContext(Dispatchers.IO){entered.await(2,TimeUnit.SECONDS)})
            caller.cancel();withTimeout(1000){caller.join()};assertTrue(work.activeCount>0)
            release.countDown();withTimeout(3000){while(work.activeCount>0)delay(1)}
            assertNull(model.identity());assertFalse(model.revalidating.value)
        }finally{release.countDown();caller.cancelAndJoin()}
    }
    @Test fun cancellationAtAdmissionCannotLeaveAValidationRunningWithoutAnOperation()=runBlocking<Unit> {
        val work=NativeAuthWork();val probes=java.util.concurrent.atomic.AtomicInteger()
        val model=NativeSessionRestore(true,null,{},{null},{true},{probes.incrementAndGet();true},
            {error("Canceled admission cannot fetch a user")},{null},{true},{},{},work=work)
        val caller=launch(Dispatchers.Default){val me=currentCoroutineContext().job
            model.revalidateIf{me.cancel();true}}
        withTimeout(2000){caller.join();while(work.activeCount>0)delay(1)}
        assertEquals(0,probes.get());assertFalse(model.revalidating.value);assertNull(model.identity())
    }
}
