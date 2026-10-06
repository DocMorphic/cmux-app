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
            active = profile.enrollment.attempt; events += "install.end"
        }
        override suspend fun stop() {
            stops++; events += "stop"
            check(!failStop) { "stop failed" }
            active = null
        }
        override fun connected(attempt: String) = active == attempt
    }
    private class Access(val store: CloudVpnStore, val owner: CloudVpnOwner = CloudVpnOwner("u", "t")) {
        var current = true
        var enrollments = 0
        var revocations = 0
        var keys = mutableListOf<String>()
        var failEnroll = false
        var failRevoke = false
        var beforeEnroll: suspend () -> Unit = {}
        var beforeRevoke: suspend () -> Unit = {}
        val access = CloudVpnAccess(owner, { current }, { CloudVpnDevice("device-id", "fingerprint") }, { key, device ->
            assertTrue(store.load().pending.any { it.owner == owner && it.fingerprint == device.fingerprint })
            enrollments++; keys += key.publicKey; beforeEnroll()
            check(!failEnroll) { "private error with secret should not reach UI" }
            CloudTunnelEnrollment("id", "provider", device.fingerprint, "", CloudWireGuardKey.generate().publicKey,
                "vpn.example.test", 51820, listOf("10.0.0.0/8"), "10.0.0.2", null, true, false)
        }, {
            revocations++; beforeRevoke(); check(!failRevoke) { "revocation failed" }
        })
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
            controller.enable(); advanceUntilIdle()
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
}
