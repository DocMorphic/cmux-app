package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAuthIdentityTest {
    private val a=NativeSignInUser("account-a","A",null,null)
    private val b=NativeSignInUser("account-b","B",null,null)
    private fun model(user:NativeSignInUser?=a,hint:Boolean=true,fetch:suspend ()->NativeSignInUser?={user},teams:suspend (NativeSignInUser)->Unit={})=
        NativeSessionRestore(hint,user,{}, {user},{true},{true},fetch,{"refresh"},{true},{},teams)
    @Test fun primingPublishesOnlyAnAuthenticatedCachedIdentityAndDescriptionsRedactTheUser() {
        val owner=model();val identity=checkNotNull(owner.identity())
        assertEquals(0L,identity.generation);assertEquals(a.id,identity.userId);assertTrue(owner.isIdentityCurrent(identity))
        assertNull(model(hint=false).identity());assertNull(model(user=null).identity())
        assertFalse(identity.toString().contains(a.id));assertEquals(identity,owner.identities.value)
    }
    @Test fun sameUserRevalidationAndProfileChangesDoNotRetireTheSessionIdentity()=runTest {
        val owner=model(fetch={a.copy(name="Updated")});val before=owner.identity()
        owner.start(this);owner.awaitBootstrapped();assertEquals(before,owner.identity());assertEquals("Updated",owner.state.value.user?.name)
        owner.revalidate();assertEquals(before,owner.identity())
    }
    @Test fun changedUserPublishesBeforeMembershipAndReturningToTheOldIdCannotReviveItsGeneration()=runTest {
        val membership=CompletableDeferred<Unit>();var next=b
        val owner=model(fetch={next},teams={membership.await()});val original=checkNotNull(owner.identity())
        val events=mutableListOf<NativeAuthIdentity?>();val observing=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.identities.toList(events) }
        owner.start(this);runCurrent()
        assertEquals(b.id,owner.identity()?.userId);assertFalse(owner.isIdentityCurrent(original));assertTrue(owner.revalidating.value)
        membership.complete(Unit);runCurrent();owner.awaitBootstrapped()
        next=a;owner.revalidate();val returned=checkNotNull(owner.identity())
        assertEquals(a.id,returned.userId);assertTrue(returned.generation>original.generation);assertFalse(owner.isIdentityCurrent(original))
        assertTrue(events.any { it?.userId==b.id });observing.cancel()
    }
    @Test fun explicitSameUserSignInAdvancesTheGenerationAndClearRetiresItBeforeCacheCleanup() {
        lateinit var owner:NativeSessionRestore;var cleanup=false
        owner=NativeSessionRestore(true,a,{}, {a},{true},{true},{a},{"refresh"},{true},{},{},onCleared={assertNull(owner.identity());cleanup=true})
        val old=checkNotNull(owner.identity());owner.publishedSignIn(a);assertFalse(owner.isIdentityCurrent(old))
        val fresh=checkNotNull(owner.identity());owner.clear();assertTrue(cleanup);assertFalse(owner.isIdentityCurrent(fresh));assertNull(owner.identities.value)
    }
    @Test fun aLateValidationCannotRepublishTheIdentityAfterANewerVerifiedSignIn()=runTest {
        val gate=CompletableDeferred<Unit>();val owner=model(fetch={withContext(NonCancellable){gate.await()};a})
        owner.start(this);runCurrent();owner.publishedSignIn(b);val published=owner.identity()
        gate.complete(Unit);runCurrent();owner.awaitBootstrapped();assertEquals(published,owner.identity());assertEquals(b.id,owner.state.value.user?.id)
    }
    @Test fun aTransientFailurePreservesTheCredentialFreeIdentityWithoutAdvancingIt()=runTest {
        val owner=model(fetch={throw IOException("Fixture unavailable")});val before=owner.identity()
        owner.start(this);owner.awaitBootstrapped();assertEquals(before,owner.identity());assertTrue(owner.state.value.authenticated)
        owner.revalidate();assertEquals(before,owner.identity())
    }
    @Test fun privateBackgroundOwnersCoalesceRestorationWithoutAnActivityBootstrap()=runTest {
        val gate=CompletableDeferred<Unit>();var calls=0
        val owner=model(user=null,fetch={calls++;gate.await();a})
        val first=async {owner.restoreIdentityIfNeeded()};val second=async {owner.restoreIdentityIfNeeded()}
        runCurrent();assertEquals(1,calls);assertNull(owner.identity());gate.complete(Unit);runCurrent()
        assertEquals(a,first.await());assertEquals(a,second.await());assertEquals(a.id,owner.identity()?.userId)
    }
    @Test fun aPreservedDisplayCacheDoesNotBecomeVerifiedBackgroundAuthorityAfterFailedValidation()=runTest {
        var failing=true;val owner=model(fetch={if(failing)throw IOException("Fixture unavailable");a})
        assertNull(owner.restoreIdentityIfNeeded());assertEquals(a.id,owner.identity()?.userId)
        assertTrue(owner.state.value.authenticated);assertTrue(owner.needsIdentityValidation)
        failing=false;assertEquals(a,owner.restoreIdentityIfNeeded());assertFalse(owner.needsIdentityValidation)
    }
}
