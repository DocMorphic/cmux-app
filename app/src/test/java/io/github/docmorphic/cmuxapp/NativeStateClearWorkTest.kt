package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NativeStateClearWorkTest {
    @Test fun onlyCurrentSignInOwnersProtectPresentationButStoredCaptureStillWaitsForActualFlows() {
        val work=NativeAuthWork();val current=AtomicBoolean(true);val lease=work.beginSignIn(current::get)
        assertTrue(work.currentSignInOwnsStore);assertTrue(work.storedSessionCaptureBlocked)
        lease.publish();assertTrue(work.currentSignInOwnsStore);assertFalse(work.sessionWriteInProgress)
        current.set(false);assertFalse(work.currentSignInOwnsStore);assertTrue(work.storedSessionCaptureBlocked)
        val end=work.beginSignOutCapture();assertTrue(work.isCapturingSignOutCredentials)
        assertTrue(nativeApplicationTransition(work.currentSignInOwnsStore,false,false,false,work.isCapturingSignOutCredentials))
        end();lease.finish();assertFalse(work.currentSignInOwnsStore);assertFalse(work.storedSessionCaptureBlocked)
    }
    @Test fun writerRegistrationCannotCrossTheFinalCommitAndPublication()=runBlocking<Unit> {
        val work=NativeAuthWork();val entered=CountDownLatch(1);val release=CountDownLatch(1);val published=AtomicBoolean()
        val commit=async(Dispatchers.IO){work.commitSessionRead{entered.countDown();check(release.await(5,TimeUnit.SECONDS));published.set(true)}}
        try {assertTrue(withContext(Dispatchers.IO){entered.await(2,TimeUnit.SECONDS)})
            val writer=async(Dispatchers.IO){work.beginSignIn().also{assertTrue(published.get())}}
            delay(50);assertFalse(writer.isCompleted);release.countDown();commit.await();writer.await().finish()
        }finally{release.countDown();commit.cancelAndJoin()}
    }
    @Test fun canceledClearWaiterReturnsWhileActualWorkRemainsTrackedAndCannotCommit()=runBlocking<Unit> {
        val work=NativeAuthWork();val entered=CountDownLatch(1);val release=CountDownLatch(1);val writes=AtomicInteger()
        val caller=launch(Dispatchers.IO){work.clearState("owner"){check->entered.countDown();kotlin.check(release.await(5,TimeUnit.SECONDS));check();writes.incrementAndGet()}}
        try {assertTrue(withContext(Dispatchers.IO){entered.await(2,TimeUnit.SECONDS)})
            caller.cancel();withTimeout(1000){caller.join()};assertEquals(1,work.activeCount)
            release.countDown();withTimeout(2000){while(work.activeCount>0)delay(1)};assertEquals(0,writes.get())
        }finally{release.countDown();caller.cancelAndJoin()}
    }
}
