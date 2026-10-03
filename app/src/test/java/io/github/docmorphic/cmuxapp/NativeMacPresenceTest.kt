package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMacPresenceTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private fun mac(tag: String? = "default") = NativeCredentialStore.PairedMac("route", "mac", "Studio", tag)

    @Test fun upstreamBuildChannelExamplesAndUnknownChannels() {
        val cases = listOf(
            Triple("com.cmuxterm.app", "default", "Stable"),
            Triple("com.cmuxterm.app.nightly", "default", "Nightly"),
            Triple("com.cmuxterm.app.nightly.my-feature", "default", "Nightly"),
            Triple("com.cmuxterm.app.staging.feat", null, "Staging"),
            Triple("com.cmuxterm.app.debug", "default", "DEV"),
            Triple("com.cmuxterm.app.rc.candidate1", null, "RC"),
            Triple("com.cmuxterm.app.debug.teams", "teams", "DEV · teams"),
            Triple("com.cmuxterm.app", "my-tag", "DEV · my-tag"),
            Triple(null, "default", "Stable"), Triple(null, "stable", "Stable"),
            Triple(null, "nightly", "Nightly"), Triple(null, "staging", "Staging"),
            Triple(null, "rc", "RC"), Triple(null, "dev", "DEV"),
            Triple(null, "future-one", "DEV · future-one"), Triple(null, null, null),
            Triple("com.example.other", "default", null), Triple("com.cmuxterm.app.beta", "default", null),
            Triple(" COM.CMUXTERM.APP.NIGHTLY ", " NIGHTLY ", "Nightly"),
            Triple("dev.cmux.tagged", "default", "DEV"),
            Triple("com.example.other", "nightly", "DEV · nightly"))
        cases.forEach { (bundle, tag, label) -> assertEquals("$bundle / $tag", label, NativeMacBuildLabel.label(bundle, tag)) }
    }
    @Test fun presencePrecedesFallbackAndUsesExactInstanceOrUnambiguousLegacyDevice() {
        val reducer = NativeMacPresenceReducer(team)
        val nightlyDefault = instance(bundle = "com.cmuxterm.app.nightly")
        val state = reducer.apply(snapshot("team", nightlyDefault))
        assertEquals("Nightly", state.buildLabel(mac()))
        assertEquals("Nightly", state.buildLabel(mac(null)))
        assertEquals("RC", state.buildLabel(mac("rc")))
        val multiple = reducer.apply(event("online", instance(tag = "staging", bundle = "com.cmuxterm.app.staging")))
        assertNull(multiple.buildLabel(mac(null)))
        assertEquals("Nightly", multiple.buildLabel(mac()))
        assertEquals("Staging", multiple.buildLabel(mac("staging")))
        assertEquals("Stable", reducer.apply(snapshot("team")).buildLabel(mac()))
    }
    @Test fun transitionsReplaceMetadataAndHeartbeatDoesNotInventAComputer() {
        val reducer = NativeMacPresenceReducer(team)
        reducer.apply(snapshot("team", instance()))
        listOf("online", "routes", "offline").forEach { event ->
            assertEquals("Nightly", reducer.apply(event(event, instance(bundle = "com.cmuxterm.app.nightly"))).buildLabel(mac()))
        }
        val state = reducer.apply(JSONObject().put("type", "seen").put("deviceId", "unknown").put("tag", "default").toString())
        assertEquals(1, state.instances.size)
    }
    @Test fun wrongTeamPreSnapshotDeltasAndAmbiguousInstancesAreRejected() {
        val reducer = NativeMacPresenceReducer(team)
        assertThrows(IllegalStateException::class.java) { reducer.apply(event("online", instance())) }
        assertThrows(IllegalArgumentException::class.java) { reducer.apply(snapshot("other", instance())) }
        assertThrows(IllegalArgumentException::class.java) { reducer.apply(snapshot("team", instance(), instance())) }
        assertEquals("Stable", reducer.apply(snapshot("team", instance())).buildLabel(mac()))
    }
    @Test fun malformedCosmeticMetadataAndNonMacRowsCannotBreakThePairing() {
        val state = NativeMacPresenceReducer(team).apply(snapshot("team", instance().put("bundleId", JSONObject()),
            instance(tag = "ios").put("platform", "ios")))
        assertEquals(1, state.instances.size)
        assertEquals("Stable", state.buildLabel(mac()))
        assertEquals("Stable", state.buildLabel(mac(null)))
    }
    @Test fun capacityAndFrameLimitsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeMacPresenceReducer(team).apply(" ".repeat(NativeMacPresenceReducer.MAX_FRAME + 1))
        }
        val records = Array(NativeMacPresenceReducer.MAX_INSTANCES + 1) { instance(tag = "tag-$it") }
        assertThrows(IllegalArgumentException::class.java) { NativeMacPresenceReducer(team).apply(snapshot("team", *records)) }
    }
    @Test fun retryAfterUsesSecondsDateAndConservativeDefault() {
        assertEquals(120_000L, PresenceHttpFailure(429, "120").retryDelay(0))
        assertEquals(60_000L, PresenceHttpFailure(429, "bad").retryDelay(0))
        assertEquals(60_000L, PresenceHttpFailure(429, "Thu, 01 Jan 1970 00:01:00 GMT").retryDelay(0))
        assertEquals(0L, PresenceHttpFailure(401, "120").retryDelay(0))
    }

    companion object {
        fun instance(tag: String = "default", bundle: String = "com.cmuxterm.app") = JSONObject()
            .put("deviceId", "mac").put("tag", tag).put("platform", "mac").put("bundleId", bundle)
        fun snapshot(team: String, vararg instances: JSONObject) = JSONObject().put("type", "snapshot").put("teamId", team)
            .put("devices", JSONArray().put(JSONObject().put("deviceId", "mac").put("instances", JSONArray(instances.toList())))).toString()
        fun event(type: String, instance: JSONObject) = JSONObject().put("type", type).put("instance", instance).toString()
    }
}
