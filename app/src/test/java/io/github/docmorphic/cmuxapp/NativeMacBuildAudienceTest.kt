package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMacBuildAudienceTest {
    private val audience = NativeMacBuildAudience.consumer
    private val team = NativeTeamScope("login", "user", "team", 1)
    private fun host(tag: String? = "default", version: String? = "0.64.25", namespace: String? = null) =
        JSONObject().put("mac_device_id", "mac").put("mac_instance_tag", tag)
            .put("mac_app_version", version).put("mac_client_namespace", namespace)

    @Test fun consumerAcceptsOnlyNormalizedStableNightlyAndRcTags() {
        listOf("default", "nightly", "rc", " DEFAULT\n", "NIGHTLY", " RC ").forEach { assertTrue(it, audience.allowsTag(it)) }
        listOf(null, "", "stable", "beta", "staging", "dev", "my-feature", "default.evil").forEach { assertFalse(it, audience.allowsTag(it)) }
    }
    @Test fun authenticatedPushPinsCannotBypassTagOrNamespaceRulesWithoutRpc() {
        val tuple = PhonePushTuple("user", "team", "android.package", "installation", "mac", "default", "com.cmuxterm.app")
        assertTrue(audience.allowsPush(tuple))
        assertFalse(audience.allowsPush(tuple.copy(macInstanceTag = "dev")))
        assertFalse(audience.allowsPush(tuple.copy(macBuildID = "com.cmuxterm.app.debug")))
        assertFalse(audience.allowsPush(tuple.copy(macInstanceTag = null)))
    }
    @Test fun officialNamespaceRequiresExactBoundaryButPermitsLegacyAndOmission() {
        listOf(null, "legacy", "mac:com.cmuxterm.app", "mac:com.cmuxterm.app.nightly", "mac:com.cmuxterm.app.nightly.feature",
            "mac:com.cmuxterm.app.rc", "mac:com.cmuxterm.app.rc.1").forEach { assertTrue(it, audience.allows("default", it)) }
        listOf("", "mac:com.cmuxterm.app.evil", "mac:com.cmuxterm.app.debug", "mac:com.cmuxterm.app.staging",
            "mac:com.cmuxterm.app.nightlyevil", "mac:com.cmuxterm.app.rcevil", "MAC:com.cmuxterm.app", " mac:com.cmuxterm.app").forEach {
            assertFalse(it, audience.allows("nightly", it))
        }
        assertFalse(audience.allows("dev", "mac:com.cmuxterm.app"))
    }
    @Test fun missingTagExceptionRequiresActualLocalTailscaleAndExactLegacyWindow() {
        assertTrue(audience.allowsAuthenticated(null, null, "0.64.17", true))
        assertTrue(audience.allowsAuthenticated(" \n", "legacy", " 0.64.17 ", true))
        assertFalse(audience.allowsAuthenticated(null, null, "0.64.17", false))
        listOf(null, "", "0.64.16", "0.64.18", "0.65", "0.64.17+build", "0.64.17-nightly.1").forEach {
            assertFalse(it, audience.allowsAuthenticated(null, null, it, true))
        }
        assertFalse(audience.allowsAuthenticated("dev", null, "0.64.17", true))
    }
    @Test fun savedLegacyVisibilityIsNotLiveAdmissionAndOtherBuildsStayFiltered() {
        assertTrue(audience.allowsSavedTag(null)); assertFalse(audience.allowsTag(null))
        assertFalse(audience.allowsSavedTag("")); assertFalse(audience.allowsSavedTag("dev"))
        assertTrue(audience.allowsSavedTag("nightly"))
    }
    @Test fun malformedHostFieldsCannotBecomeMissingTagOrNamespaceExceptions() {
        listOf("mac_instance_tag", "mac_client_namespace", "mac_app_version").forEach { field ->
            assertThrows(MacBuildNotSupported::class.java) { audience.requireAuthenticated(host(null, "0.64.17").put(field, 17), true) }
        }
    }
    @Test fun presenceFilteringPreservesOwnerAndExactSiblingWithoutTreatingMetadataAsAuthentication() {
        val stable = NativeMacIdentity("mac", "default"); val dev = NativeMacIdentity("mac", "dev")
        val raw = NativeMacPresenceState(team, mapOf(stable to NativeMacPresenceInstance(stable, "untrusted.bundle", true),
            dev to NativeMacPresenceInstance(dev, "com.cmuxterm.app.debug", true)))
        val visible = audience.presence(raw)
        assertEquals(team, visible.owner); assertEquals(setOf(stable), visible.instances.keys)
        assertEquals(2, raw.instances.size)
    }
    @Test fun incompatibleHostIsClosedBeforeObservationOrPolicyVersionChecks() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == team }, audience = audience)
        for (status in listOf(host("dev", "999.0"), host(namespace = "mac:com.cmuxterm.app.debug"), host(null))) {
            MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
                client.connect()
                assertTrue(runCatching { gate.admit(team, client, status) }.exceptionOrNull() is MacBuildNotSupported)
                assertTrue(client.isClosed); assertTrue(gate.observations.value.isEmpty()); assertTrue(gate.warnings.value.isEmpty())
            }
        }
    }
    @Test fun validEmptyVersionPolicyDoesNotDisableAudienceButLegacyExceptionStillWorks() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == team }, NativeMacCompatibilityPolicy(emptyList()), audience)
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect(); gate.admit(team, client, host(null, "0.64.17"), locallyAuthorizedTailscale = true)
            assertFalse(client.isClosed)
        }
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            assertTrue(runCatching { gate.admit(team, client, host("staging", "999"), true) }.exceptionOrNull() is MacBuildNotSupported)
        }
    }
    @Test fun legacyAudienceExceptionNeverOverridesCurrentMinimumVersion() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == team }, audience = audience)
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            assertTrue(runCatching { gate.admit(team, client, host(null, "0.64.17"), true) }.exceptionOrNull() is MacUpdateRequired)
        }
    }
    @Test fun consumerStableNightlyAndRcCompleteAdmissionWithTheirOwnFloors() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == team }, audience = audience)
        listOf(host(), host("nightly", "0.64.25-nightly.3522337919701", "mac:com.cmuxterm.app.nightly"),
            host("rc", "0.65.0-rc.1", "mac:com.cmuxterm.app.rc")).forEach { status ->
            MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
                client.connect(); gate.admit(team, client, status); assertFalse(client.isClosed)
            }
        }
    }
}
