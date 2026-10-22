package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeChiefTranslationDownloadTest {
    private val source = translationLanguage("fr")
    private val target = translationLanguage("en")
    @Test fun onlyAnAdmittedSettingsReturnLetsTheEngineRecheckModels() = runTest {
        val host = NativeChiefTranslationDownload { true }
        val pending = async { host.request(source, target) }; runCurrent()
        val request = host.state.value!!
        assertFalse(pending.isCompleted)
        assertTrue(host.openSettings(request.id)); assertTrue(host.state.value!!.settingsOpen)
        assertFalse(host.openSettings(request.id))
        host.settingsReturned(request.id + 1); assertFalse(pending.isCompleted)
        host.settingsReturned(request.id); assertTrue(pending.await()); assertNull(host.state.value)
        assertNull(host.externalRequest); host.close()
    }
    @Test fun anEarlyActivityResultDoesNotReplaceARealForegroundReturn() = runTest {
        val host = NativeChiefTranslationDownload { true }
        val pending = async { host.request(source, target) }; runCurrent()
        val id = host.state.value!!.id
        assertTrue(host.openSettings(id))
        host.resumeFromSettings(); assertFalse(pending.isCompleted)
        host.settingsLeftApp(); assertTrue(host.externalDeparted)
        host.resumeFromSettings(); assertTrue(pending.await())
        assertFalse(host.externalDeparted); host.close()
    }
    @Test fun canceledOldExternalResultCannotAdmitANewDownload() = runTest {
        val host = NativeChiefTranslationDownload { true }
        val first = async { host.request(source, target) }; runCurrent()
        val old = host.state.value!!.id
        assertTrue(host.openSettings(old)); first.cancelAndJoin(); assertNull(host.state.value)
        val next = async { host.request(source, target) }; runCurrent()
        val fresh = host.state.value!!.id
        assertNotEquals(old, fresh); assertFalse(host.openSettings(fresh))
        host.settingsReturned(old)
        assertEquals(fresh, host.state.value!!.id); assertFalse(next.isCompleted)
        assertTrue(host.openSettings(fresh)); host.settingsFailed(fresh)
        assertFalse(next.await()); assertNull(host.externalRequest); host.close()
    }
    @Test fun declineAndLaunchFailureLeaveFutureRequestsAvailable() = runTest {
        val host = NativeChiefTranslationDownload { true }
        repeat(2) { index ->
            val pending = async { host.request(source, target) }; runCurrent()
            val id = host.state.value!!.id
            if (index == 0) host.decline(id) else { assertTrue(host.openSettings(id)); host.settingsFailed(id) }
            assertFalse(pending.await()); assertNull(host.state.value); assertNull(host.externalRequest)
        }
        host.close()
    }
    @Test fun retiredOwnerClearsRequestsAndRejectsLateReturns() = runTest {
        var current = true; val host = NativeChiefTranslationDownload { current }
        val pending = async { host.request(source, target) }; runCurrent()
        val id = host.state.value!!.id
        current = false; assertFalse(host.openSettings(id)); assertNull(host.state.value)
        runCurrent(); assertTrue(pending.isCancelled)
        host.settingsReturned(id); assertNull(host.state.value); assertNull(host.externalRequest)
        host.close()
    }
}
