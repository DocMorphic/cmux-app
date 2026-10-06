package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSystemVpnControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cipher = object : CloudIdentityCipher {
        override fun encrypt(bytes: ByteArray) = bytes.copyOf()
        override fun decrypt(bytes: ByteArray) = bytes.copyOf()
    }
    private class Platform : CloudSystemVpnPlatform {
        override var consentGranted = true
        var active: String? = null
        var installed: CloudVpnProfile? = null
        var starts = 0
        var stops = 0
        var interruptions = 0
        var failStop = false
        var beforeInstall: suspend () -> Unit = {}
        val events = mutableListOf<String>()
        override fun interrupt() { interruptions++; events += "interrupt" }
        override suspend fun install(profile: CloudVpnProfile, current: () -> Boolean) {
            starts++; events += "install.begin"
            beforeInstall()
            if (!current()) throw CancellationException("retired")
            installed = profile; active = profile.enrollment.attempt; events += "install.end"
        }
        override suspend fun stop() {
            stops++; events += "stop"
            check(!failStop) { "stop failed" }
            active = null
        }
        override fun connected(attempt: String) = active == attempt
    }
    private class Access(val store: CloudVpnStore, val owner: CloudVpnOwner = CloudVpnOwner("u", "t"), val session: String? = null) {
        var current = true
        var devices = 0
        var enrollments = 0
        var revocations = 0
        val revokedFingerprints = mutableListOf<String>()
        var keys = mutableListOf<String>()
        var failEnroll = false
        var failRevoke = false
        var beforeEnroll: suspend () -> Unit = {}
        var beforeRevoke: suspend () -> Unit = {}
        val access = CloudVpnAccess(owner, { current }, { devices++; CloudVpnDevice("device-id", "fingerprint") }, { key, device ->
            assertTrue(store.load().pending.any { it.owner == owner && it.fingerprint == device.fingerprint })
            enrollments++; keys += key.publicKey; beforeEnroll()
            check(!failEnroll) { "private error with secret should not reach UI" }
            CloudTunnelEnrollment("id", "provider", device.fingerprint, "", CloudWireGuardKey.generate().publicKey,
                "vpn.example.test", 51820, listOf("10.0.0.0/8"), "10.0.0.2", null, true, false)
        }, { fingerprint ->
            revocations++; revokedFingerprints += fingerprint; beforeRevoke(); check(!failRevoke) { "revocation failed" }
        }, session = session)
    }
    @Test fun bindingAndConsentNeverEnrollUntilExplicitEnableAndDisableRetiresBeforeRevoke() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform(); val access = Access(store)
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            controller.bind(access.access); runCurrent()
            assertEquals(0, access.enrollments); assertEquals(CloudSystemVpnPhase.OFF, controller.state.value.phase)
            platform.consentGranted = false
            assertFalse(controller.enable()); runCurrent(); assertTrue(store.load().pending.isEmpty())
            platform.consentGranted = true
            assertTrue(controller.enable()); runCurrent()
            assertEquals(CloudSystemVpnPhase.CONNECTED, controller.state.value.phase)
            assertNotNull(store.load().profile)
            assertTrue(controller.enable()); runCurrent(); assertEquals(1, access.enrollments)
            access.beforeRevoke = { assertNull(platform.active); assertNull(store.load().profile) }
            controller.disable(); runCurrent()
            assertEquals(CloudSystemVpnPhase.OFF, controller.state.value.phase)
            assertTrue(store.load().pending.isEmpty()); assertEquals(1, access.revocations)
            controller.enable(); runCurrent()
            assertEquals(2, access.keys.distinct().size)
        } finally { controller.close(); runCurrent() }
    }
    @Test fun accountSwitchStopsOldProfileAndUsesCapturedOldRevokerWithoutEnrollingNewAccount() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform()
        val old = Access(store); val next = Access(store, CloudVpnOwner("another", "team"))
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            controller.bind(old.access); controller.enable(); runCurrent()
            val oldAttempt = platform.active!!
            old.current = false; controller.bind(next.access); runCurrent()
            assertNull(platform.active); assertEquals(1, old.revocations); assertEquals(0, next.revocations)
            assertEquals(0, next.enrollments)
            controller.enable(); runCurrent()
            assertEquals(CloudSystemVpnPhase.CONNECTED, controller.state.value.phase)
            controller.platformChanged(oldAttempt, false); runCurrent()
            assertEquals(CloudSystemVpnPhase.CONNECTED, controller.state.value.phase)
            controller.platformChanged(platform.active!!, false); runCurrent()
            assertNull(platform.active); assertEquals(1, next.revocations)
        } finally { controller.close(); runCurrent() }
    }
    @Test fun failedEnrollmentNeverRetriesPostAndFailedRevocationRemainsDurableUntilRetry() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform(); val access = Access(store)
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            controller.bind(access.access); access.failEnroll = true; access.failRevoke = true
            controller.enable(); runCurrent(); advanceTimeBy(751); runCurrent()
            assertEquals(CloudSystemVpnPhase.FAILED, controller.state.value.phase)
            assertEquals(1, access.enrollments); assertEquals(3, access.revocations)
            assertEquals(1, store.load().pending.size); assertNull(store.load().profile)
            assertFalse(controller.state.value.message!!.contains("secret"))
            access.failRevoke = false
            controller.retryCleanup(); runCurrent()
            assertTrue(store.load().pending.isEmpty()); assertEquals(1, access.enrollments)
        } finally { controller.close(); runCurrent() }
    }
    @Test fun timeoutFencesNonCooperativeInstallAndReplacementWaitsForLateCleanup() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform(); val access = Access(store)
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler), timeoutMillis = 1000)
        try {
            val release = CompletableDeferred<Unit>()
            platform.beforeInstall = { withContext(NonCancellable) { release.await() } }
            controller.bind(access.access); controller.enable(); runCurrent()
            assertEquals(1, platform.starts)
            advanceTimeBy(1001); runCurrent()
            assertEquals(CloudSystemVpnPhase.FAILED, controller.state.value.phase)
            controller.enable(); runCurrent()
            assertEquals(1, access.enrollments)
            platform.beforeInstall = {}; release.complete(Unit); runCurrent()
            assertEquals(2, access.enrollments)
            assertEquals(CloudSystemVpnPhase.CONNECTED, controller.state.value.phase)
            assertEquals(1, access.revocations)
            assertEquals(1, store.load().pending.size)
        } finally { controller.close(); runCurrent() }
    }
    @Test fun lateEnrollmentAfterSignOutIsCleanedWithoutInstallingAndNullInitialScopeCleansSavedProfile() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform(); val access = Access(store)
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            val release = CompletableDeferred<Unit>()
            access.beforeEnroll = { withContext(NonCancellable) { release.await() } }
            controller.bind(access.access); controller.enable(); runCurrent()
            access.current = false; controller.bind(null); runCurrent(); release.complete(Unit); runCurrent()
            assertEquals(0, platform.starts); assertEquals(1, access.revocations); assertTrue(store.load().pending.isEmpty())
        } finally { controller.close(); runCurrent() }
        val entry = store.begin(access.owner, "other fingerprint")
        store.install(entry, "[Interface]\nPrivateKey=fixture\nAddress=10.0.0.2/32\n[Peer]\nAllowedIPs=10.0.0.0/8\nPublicKey=fixture")
        val fresh = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            fresh.bind(null); runCurrent()
            assertNull(store.load().profile)
            assertEquals(listOf(entry), store.load().pending) // No new owner's credentials may revoke it.
        } finally { fresh.close(); runCurrent() }
    }
    @Test fun failedLocalStopKeepsProfileAndBlocksPeerRevocationAndReplacement() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform(); val access = Access(store)
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            controller.bind(access.access); controller.enable(); runCurrent()
            platform.failStop = true; controller.disable(); runCurrent()
            assertEquals(CloudSystemVpnPhase.FAILED, controller.state.value.phase)
            assertNotNull(store.load().profile); assertEquals(0, access.revocations)
            assertFalse(store.load().profile!!.requested)
            controller.enable(); runCurrent(); assertEquals(1, access.enrollments)
            platform.failStop = false; controller.retryCleanup(); runCurrent()
            assertNull(store.load().profile); assertTrue(store.load().pending.isEmpty())
        } finally { controller.close(); runCurrent() }
    }

    @Test fun currentCancellationReportsFailureAndCanRetryWithoutStrandingPreparing() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform(); val access = Access(store)
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            access.beforeEnroll = { throw CancellationException("cancelled request") }
            controller.bind(access.access); controller.enable(); runCurrent()
            assertEquals(CloudSystemVpnPhase.FAILED, controller.state.value.phase)
            assertTrue(store.load().pending.isEmpty()); assertNull(platform.active)
            access.beforeEnroll = {}; controller.enable(); runCurrent()
            assertEquals(CloudSystemVpnPhase.CONNECTED, controller.state.value.phase)
        } finally { controller.close(); runCurrent() }
    }

    @Test fun lifetimeEndCleansIndependentlyAndClosedControllerRejectsNewEnable() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform(); val access = Access(store)
        val parent = SupervisorJob()
        val controller = CloudSystemVpnController(CoroutineScope(parent), store, platform, StandardTestDispatcher(testScheduler))
        controller.bind(access.access); controller.enable(); runCurrent()
        assertNotNull(platform.active)
        parent.cancel(); runCurrent()
        assertEquals(CloudSystemVpnPhase.CLOSED, controller.state.value.phase)
        assertNull(platform.active); assertNull(store.load().profile); assertTrue(store.load().pending.isEmpty())
        assertFalse(controller.enable())
        assertEquals(1, access.enrollments)
    }

    @Test fun backlogDrainsAcrossBatchesWithoutEnrollmentOrUsingAnotherOwnersCredentials() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform(); val access = Access(store)
        repeat(25) { store.begin(access.owner, "old-$it") }
        val unavailable = store.begin(CloudVpnOwner("unavailable", "team"), "another-owner")
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            controller.bind(access.access); advanceUntilIdle()
            assertEquals(25, access.revocations); assertEquals(25, access.revokedFingerprints.distinct().size)
            assertEquals(listOf(unavailable), store.load().pending)
            assertEquals(1, controller.state.value.pendingCleanup)
            assertEquals(CloudSystemVpnPhase.OFF, controller.state.value.phase)
            assertEquals(0, platform.starts); assertEquals(0, access.enrollments)
            assertEquals(1, platform.stops)
        } finally { controller.close(); runCurrent() }
    }

    @Test fun backgroundCleanupAndManualRetryNeverStopOrRevokeAnActiveProfile() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform(); val access = Access(store)
        repeat(9) { store.begin(access.owner, "old-$it") }
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            platform.beforeInstall = { access.failRevoke = true }
            controller.bind(access.access); controller.enable(); runCurrent()
            val active = platform.active
            assertNotNull(active)
            advanceUntilIdle()
            assertEquals(active, platform.active)
            assertEquals(CloudSystemVpnPhase.CONNECTED, controller.state.value.phase)
            assertEquals(17, access.revocations) // Eight cleanups, then three bounded failed batches of three.
            assertFalse("fingerprint" in access.revokedFingerprints)
            assertEquals(2, store.load().pending.size); assertEquals(1, controller.state.value.pendingCleanup)
            access.failRevoke = false
            controller.retryCleanup(); advanceUntilIdle()
            assertEquals(active, platform.active)
            assertEquals(1, store.load().pending.size); assertEquals(0, controller.state.value.pendingCleanup)
            assertEquals(1, platform.starts); assertEquals(1, platform.stops); assertEquals(1, access.enrollments)
        } finally { controller.close(); runCurrent() }
    }

    @Test fun accountRetirementCancelsScheduledCleanupAndCloseDoesNotLeaveATimer() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform(); val access = Access(store)
        repeat(9) { store.begin(access.owner, "old-$it") }
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        controller.bind(access.access); runCurrent()
        assertEquals(8, access.revocations)
        controller.close(); advanceUntilIdle()
        assertEquals(9, access.revocations); assertTrue(store.load().pending.isEmpty())
        assertEquals(CloudSystemVpnPhase.CLOSED, controller.state.value.phase)
        val count = access.revocations; advanceTimeBy(100_000); runCurrent(); assertEquals(count, access.revocations)
    }

    @Test fun failedStopIntentWriteStillStopsLocalVpnAndRetainsUnconfirmedCleanup() = runTest {
        var failWrites = false
        val failingCipher = object : CloudIdentityCipher {
            override fun encrypt(bytes: ByteArray): ByteArray {
                if (failWrites) throw java.io.IOException("Disk full")
                return bytes.copyOf()
            }
            override fun decrypt(bytes: ByteArray) = bytes.copyOf()
        }
        val store = CloudVpnStore(temporary.newFolder(), failingCipher); val platform = Platform(); val access = Access(store)
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            controller.bind(access.access); controller.enable(); runCurrent()
            assertNotNull(platform.active)
            failWrites = true; controller.disable(); runCurrent()
            assertNull(platform.active)
            assertEquals(CloudSystemVpnPhase.FAILED, controller.state.value.phase)
            assertNotNull(store.load().profile); assertEquals(0, access.revocations)
            failWrites = false; controller.retryCleanup(); advanceUntilIdle()
            assertNull(store.load().profile); assertTrue(store.load().pending.isEmpty())
            assertEquals(1, access.enrollments)
        } finally { failWrites = false; controller.close(); runCurrent() }
    }

    private fun savedProfile(store: CloudVpnStore, owner: CloudVpnOwner, session: String? = "login-1"): CloudVpnProfile {
        val entry = store.begin(owner, "saved-fingerprint")
        val config = "[Interface]\nPrivateKey=fixture\nAddress=10.0.0.2/32\n[Peer]\nAllowedIPs=10.0.0.0/8\nPublicKey=fixture"
        store.install(entry, config, session)
        return store.load().profile!!
    }

    @Test fun verifiedRestartRestoresSamePeerAndKeyWithoutEnrollmentOrDeviceLookup() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform()
        val access = Access(store, session = "login-1")
        store.begin(access.owner, "old-orphan")
        val saved = savedProfile(store, access.owner)
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            controller.platformRecovered()
            assertEquals(0, platform.interruptions)
            controller.bind(access.access, restoreSaved = true); advanceUntilIdle()
            assertEquals(CloudSystemVpnPhase.CONNECTED, controller.state.value.phase)
            assertEquals(saved.enrollment.attempt, platform.active)
            assertEquals(saved.configuration, platform.installed!!.configuration)
            assertEquals(saved.session, platform.installed!!.session)
            assertEquals(0, access.enrollments); assertEquals(0, access.devices)
            assertEquals(listOf("old-orphan"), access.revokedFingerprints)
            assertEquals(listOf(saved.enrollment), store.load().pending)
            assertEquals(0, platform.stops); assertEquals(0, platform.interruptions)
            controller.disable(); advanceUntilIdle()
            assertNull(store.load().profile); assertNull(platform.active); assertTrue(store.load().pending.isEmpty())
        } finally { controller.close(); runCurrent() }
    }

    @Test fun wrongOwnerLoginOrPermissionAndStoppedProfilesNeverRestore() = runTest {
        for (scenario in listOf("user", "team", "session", "legacy", "consent", "stopped", "stale")) {
            val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform()
            val savedOwner = CloudVpnOwner("u", "t")
            val owner = when (scenario) {
                "user" -> CloudVpnOwner("other", "t")
                "team" -> CloudVpnOwner("u", "other")
                else -> savedOwner
            }
            val access = Access(store, owner, if (scenario == "session") "login-2" else "login-1")
            val saved = savedProfile(store, savedOwner, if (scenario == "legacy") null else "login-1")
            if (scenario == "consent") platform.consentGranted = false
            if (scenario == "stopped") store.requestStop()
            if (scenario == "stale") access.current = false
            val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
            try {
                controller.bind(access.access, restoreSaved = true); advanceUntilIdle()
                assertEquals(scenario, 0, platform.starts); assertEquals(scenario, 0, access.enrollments)
                assertNull(scenario, store.load().profile)
                assertEquals(scenario, CloudSystemVpnPhase.OFF, controller.state.value.phase)
                if (owner != savedOwner) {
                    assertEquals(listOf(saved.enrollment), store.load().pending); assertEquals(0, access.revocations)
                } else assertTrue(scenario, store.load().pending.isEmpty())
            } finally { controller.close(); runCurrent() }
        }
    }

    @Test fun disconnectWhileWaitingForVerificationCannotBeUndoneByFirstBinding() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform()
        val access = Access(store, session = "login-1"); savedProfile(store, access.owner)
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            controller.disable()
            controller.bind(access.access, restoreSaved = true); advanceUntilIdle()
            assertEquals(0, platform.starts); assertEquals(0, access.enrollments)
            assertNull(store.load().profile); assertTrue(store.load().pending.isEmpty())
            val stopped = platform.interruptions
            controller.platformRecovered(); assertEquals(stopped + 1, platform.interruptions)
            controller.enable(); runCurrent() // A later explicit request remains allowed.
            assertEquals(1, access.enrollments); assertNotNull(platform.active)
            val runningInterruptions = platform.interruptions
            controller.platformRecovered(); assertEquals(runningInterruptions, platform.interruptions)
        } finally { controller.close(); runCurrent() }
    }

    @Test fun lateRestoredInstallCannotOvertakeAccountRetirementOrNewEnrollment() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform()
        val old = Access(store, session = "login-1"); val saved = savedProfile(store, old.owner)
        val next = Access(store, CloudVpnOwner("another", "team"), "login-2")
        val release = CompletableDeferred<Unit>()
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        try {
            platform.beforeInstall = { withContext(NonCancellable) { release.await() } }
            controller.bind(old.access, restoreSaved = true); runCurrent()
            old.current = false; controller.bind(next.access, restoreSaved = true); controller.enable(); runCurrent()
            assertEquals(0, next.enrollments); assertEquals(saved.enrollment, store.load().profile!!.enrollment)
            platform.beforeInstall = {}; release.complete(Unit); advanceUntilIdle()
            assertEquals(0, old.enrollments); assertEquals(listOf("saved-fingerprint"), old.revokedFingerprints)
            assertEquals(1, next.enrollments); assertEquals(0, next.revocations)
            assertEquals(CloudSystemVpnPhase.CONNECTED, controller.state.value.phase)
            assertNotEquals(saved.enrollment.attempt, platform.active)
            assertEquals(next.owner, store.load().profile!!.enrollment.owner)
        } finally { release.complete(Unit); controller.close(); runCurrent() }
    }

    @Test fun restoreTimeoutFencesLateNativeCompletionAndDrainsItsOriginalPeer() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform()
        val access = Access(store, session = "login-1"); savedProfile(store, access.owner)
        val release = CompletableDeferred<Unit>()
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler), timeoutMillis = 1000)
        try {
            platform.beforeInstall = { withContext(NonCancellable) { release.await() } }
            controller.bind(access.access, restoreSaved = true); runCurrent()
            advanceTimeBy(1001); runCurrent()
            assertEquals(CloudSystemVpnPhase.FAILED, controller.state.value.phase)
            assertEquals(0, access.revocations)
            release.complete(Unit); advanceUntilIdle()
            assertNull(platform.active); assertNull(store.load().profile); assertTrue(store.load().pending.isEmpty())
            assertEquals(listOf("saved-fingerprint"), access.revokedFingerprints); assertEquals(0, access.enrollments)
        } finally { release.complete(Unit); controller.close(); runCurrent() }
    }

    @Test fun adoptedServiceAfterSignedOutBindingIsStoppedWithoutEnrollment() = runTest {
        val store = CloudVpnStore(temporary.newFolder(), cipher); val platform = Platform()
        val controller = CloudSystemVpnController(backgroundScope, store, platform, StandardTestDispatcher(testScheduler))
        controller.bind(null); advanceUntilIdle()
        val count = platform.interruptions
        controller.platformRecovered(); assertEquals(count + 1, platform.interruptions)
        assertEquals(0, platform.starts)
        controller.close(); runCurrent()
        val closedCount = platform.interruptions
        controller.platformRecovered(); assertEquals(closedCount + 1, platform.interruptions)
    }
}
