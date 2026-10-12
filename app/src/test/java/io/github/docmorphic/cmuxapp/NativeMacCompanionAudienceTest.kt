package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMacCompanionAudienceTest {
    private val app = NativeMacBuildAudience.application
    private val tag = NativeMacBuildAudience.COMPANION_TAG
    private val namespace = NativeMacBuildAudience.COMPANION_NAMESPACE
    private val owner = NativeTeamScope("fixture-login", "fixture-user", "fixture-team", 1)
    private fun host(version: String? = "0.65.0", ns: String? = namespace) = JSONObject()
        .put("mac_device_id", "fixture-mac").put("mac_instance_tag", tag)
        .put("mac_client_namespace", ns).put("mac_app_version", version)

    @Test fun explicitApplicationProfileAcceptsOnlyTheExactCompanionAlongsidePublicReleases() {
        assertTrue(app.allowsTag(tag)); assertTrue(app.allowsTag(" ANDROID-PUSH "))
        assertFalse(NativeMacBuildAudience.consumer.allowsTag(tag))
        for (private in listOf(null, "", "dev", "staging", "android-push.evil", "other-feature"))
            assertFalse(app.allowsTag(private))
        assertTrue(app.allows(tag, namespace))
        for (ns in listOf(null, "", "legacy", "mac:com.cmuxterm.app", "mac:com.cmuxterm.app.debug", namespace + ".evil",
            "mac:com.cmuxterm.app.debug.other", namespace.uppercase(), " $namespace")) {
            assertFalse("Companion namespace: $ns", app.allows(tag, ns))
            assertFalse(app.allowsAuthenticated(tag, ns, "0.65.0", true))
            assertFalse(app.allowsAuthenticated(tag, ns, "0.65.0", false))
        }
        for (public in listOf("default", "nightly", "rc")) {
            assertTrue(app.allows(public, null)); assertTrue(app.allows(public, "legacy"))
            assertFalse(app.allows(public, namespace))
        }
    }

    @Test fun pushTupleNeedsExactCompanionTagAndBundleAndCannotBorrowTheLegacyException() {
        val tuple = PhonePushTuple("fixture-user", "fixture-team", "android.package", "installation", "fixture-mac", tag,
            NativeMacBuildAudience.COMPANION_BUNDLE)
        assertTrue(app.allowsPush(tuple)); assertFalse(NativeMacBuildAudience.consumer.allowsPush(tuple))
        for (bundle in listOf(null, "", "com.cmuxterm.app", "com.cmuxterm.app.debug", "com.cmuxterm.app.debug.other",
            NativeMacBuildAudience.COMPANION_BUNDLE + ".evil")) assertFalse(app.allowsPush(tuple.copy(macBuildID = bundle)))
        assertFalse(app.allowsPush(tuple.copy(macInstanceTag = "dev")))
        assertFalse(app.allowsPush(tuple.copy(macInstanceTag = "default")))
        assertFalse(app.allowsPush(tuple.copy(macInstanceTag = null)))
    }

    @Test fun companionBaselineAndDownloadedProtocolFloorRemainIndependentOfTagAdmission() {
        val empty = NativeMacCompatibilityPolicy(emptyList())
        for (version in listOf(null, "", "0.64.25", "0.64.99", "0.65.0-dev", "NaN", "https://evil.test/")) {
            val violation = checkNotNull(app.violation(empty, tag, version))
            assertTrue(violation.companion); assertEquals("0.65.0", violation.required)
            assertTrue(violation.message.contains("notification companion"))
            assertEquals("https://github.com/DocMorphic/cmux-app/blob/main/docs/MAC_NOTIFICATION_BUILD.md", violation.downloadUrl)
        }
        assertNull(app.violation(empty, tag, "0.65.0")); assertNull(app.violation(empty, tag, "0.65.1+owned-source"))
        val raised = NativeMacCompatibilityPolicy(listOf(NativeMacCompatibilityPolicy.Tier(
            checkNotNull(MacNumericVersion.parse("1.0.6")), null,
            MacVersionRequirement(checkNotNull(MacNumericVersion.parse("0.66.0"))), emptyMap())))
        assertEquals("0.66.0", app.violation(raised, tag, "0.65.0")?.required)
        assertNull(app.violation(raised, tag, "0.66.0"))
        assertNull(NativeMacBuildAudience.consumer.violation(empty, tag, "0.1")) // Pure consumer never admits this tag.
    }

    @Test fun authenticNamespaceAndCurrentOwnerAreRequiredBeforeObservationOrVersionChecks() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == owner }, audience = app)
        for (ns in listOf(null, "legacy", "mac:com.cmuxterm.app.debug.other")) {
            MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
                client.connect()
                assertTrue(runCatching { gate.admit(owner, client, host(ns = ns)) }.exceptionOrNull() is MacBuildNotSupported)
                assertTrue(client.isClosed); assertTrue(gate.observations.value.isEmpty())
            }
        }
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            assertTrue(runCatching { gate.admit(owner.copy(login = "replacement"), client, host()) }.isFailure)
            assertTrue(gate.observations.value.isEmpty())
            gate.admit(owner, client, host()); assertFalse(client.isClosed)
            assertEquals("0.65.0", gate.observations.value.values.single())
        }
    }

    @Test fun companionWireIsRetiredByVersionRefreshAndEmptyPolicyCannotAdmitMissingVersion() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == owner }, NativeMacCompatibilityPolicy(emptyList()), app)
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            assertTrue(runCatching { gate.admit(owner, client, host(version = null)) }.exceptionOrNull() is MacUpdateRequired)
            assertTrue(client.isClosed)
        }
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect(); gate.admit(owner, client, host())
            gate.replace(NativeMacCompatibilityPolicy(listOf(NativeMacCompatibilityPolicy.Tier(
                checkNotNull(MacNumericVersion.parse("1.0.6")), null,
                MacVersionRequirement(checkNotNull(MacNumericVersion.parse("0.66.0"))), emptyMap()))))
            assertTrue(client.isClosed); assertTrue(gate.warnings.value.values.single().companion)
        }
    }
}
