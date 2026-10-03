package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMacCompatibilityTest {
    private fun entry(min: String = "1.0.6", stable: String = "0.64.25") =
        JSONObject().put("minIOSVersion", min).put("stableMinVersion", stable)
    private fun decode(vararg entries: JSONObject) = NativeMacCompatibilityPolicy.decode(
        JSONObject().put("entries", JSONArray(entries.toList())).toString())
    private fun nightly(build: String = "3522337919701") = JSONObject().put("minBaseVersion", "0.64.25").put("minBuild", build)
    private val baked = NativeMacCompatibilityPolicy.baked

    @Test fun numericVersionsPadZerosAndEnforceSigned64BitAsciiGrammar() {
        assertEquals(MacNumericVersion.parse("1"), MacNumericVersion.parse("1.0.0"))
        assertEquals(MacNumericVersion.parse("01.2"), MacNumericVersion.parse("1.2.0"))
        assertTrue(checkNotNull(MacNumericVersion.parse("0.10")) > checkNotNull(MacNumericVersion.parse("0.9.999")))
        listOf("", "1.", ".1", "1..0", "1.2.3.4", " 1", "+1", "-1", "1+abc", "١", "9223372036854775808").forEach {
            assertNull(it, MacNumericVersion.parse(it))
        }
        assertNotNull(MacNumericVersion.parse("9223372036854775807"))
    }
    @Test fun nightlyUsesUnsigned64BitAndNumericBaseBeforeCounter() {
        assertNotNull(MacNightlyVersion.parse("0.65-nightly.18446744073709551615+sha"))
        assertNull(MacNightlyVersion.parse("0.65-nightly.18446744073709551616"))
        listOf("-1", "+1", "1.0", "", "１２").forEach { assertNull(MacNightlyVersion.parse("0.65-nightly.$it")) }
        assertTrue(checkNotNull(MacNightlyVersion.parse("0.66-nightly.0")) >
            checkNotNull(MacNightlyVersion.parse("0.65-nightly.18446744073709551615")))
    }
    @Test fun stableBoundaryWhitespaceAndMetadata() {
        assertNotNull(baked.violation("default", "0.64.24"))
        assertNull(baked.violation("default", " \n0.64.25+build \t"))
        assertNull(baked.violation("default", "0.65"))
        listOf(null, "", "garbage", "0.64.25-nightly.9999999999999").forEach { assertNotNull(baked.violation("default", it)) }
    }
    @Test fun nightlyBoundaryAndChannelIsolation() {
        assertNotNull(baked.violation("nightly", "0.64.25-nightly.3522337919700"))
        assertNull(baked.violation(" NIGHTLY ", "0.64.25-nightly.3522337919701+abc"))
        assertNull(baked.violation("nightly", "0.65-nightly.0"))
        assertNotNull(baked.violation("nightly", "0.65.0"))
        assertNotNull(baked.violation(null, null))
        listOf("rc", "staging", "dev").forEach { assertNull(baked.violation(it, null)) }
    }
    @Test fun stableOnlyStillRequiresWellFormedNightly() {
        val policy = checkNotNull(decode(entry()))
        assertNotNull(policy.violation("nightly", null))
        assertNull(policy.violation("nightly", "0.1-nightly.0"))
    }
    @Test fun greatestTierMaxExclusionDoesNotFallBackToOlderTier() {
        val policy = checkNotNull(decode(entry("1"), entry("1.0.5", "0.65").put("maxIOSVersion", "1.0.5")))
        assertNull(policy.requirement("0.9"))
        assertEquals("0.65.0", policy.requirement("1.0.5")?.stable.toString())
        assertNull(policy.requirement("1.0.6"))
        assertNull(policy.requirement("not-a-version"))
    }
    @Test fun explicitAndroidProfileSelectsV2ProdWhileAndroidMarketingVersionWouldBeUncovered() {
        val policy = checkNotNull(decode(entry().put("buildKinds", JSONObject()
            .put("prod", JSONObject().put("stableMinVersion", "0.64.25"))
            .put("dev", JSONObject().put("stableMinVersion", "0.1")))))
        assertEquals("0.64.25", policy.requirement()?.stable.toString())
        assertNull(policy.requirement("0.2.0"))
        assertEquals("0.1.0", policy.requirement(kind = "dev")?.stable.toString())
    }
    @Test fun payloadRejectsAnyMalformedEntryAndNonAscendingOrReversedTiers() {
        assertNull(decode(entry(), entry("nope")))
        assertNull(decode(entry(), entry("1.0.6")))
        assertNull(decode(entry("1.0.6"), entry("1.0.5")))
        assertNull(decode(entry().put("maxIOSVersion", "1.0.5")))
        assertNull(decode(entry().put("maxIOSVersion", 17)))
        assertNull(decode(entry().put("nightly", nightly("18446744073709551616"))))
        assertNull(NativeMacCompatibilityPolicy.decode("{}"))
    }
    @Test fun allBuildKindsMustParseAndProdMustExistAndAgreeWithLegacy() {
        val prod = JSONObject().put("stableMinVersion", "0.64.25").put("nightly", nightly())
        val kinds = JSONObject().put("prod", prod)
        assertNotNull(decode(entry().put("nightly", nightly()).put("buildKinds", kinds)))
        assertNull(decode(entry(stable = "0.64.24").put("buildKinds", kinds)))
        assertNull(decode(entry().put("nightly", nightly("1")).put("buildKinds", kinds)))
        assertNull(decode(entry().put("buildKinds", JSONObject().put("beta", prod))))
        assertNull(decode(entry().put("buildKinds", JSONObject().put("prod", prod)
            .put("future-kind", JSONObject().put("stableMinVersion", "invalid")))))
        assertNotNull(decode(entry(stable = "invalid").put("buildKinds", kinds)))
    }
    @Test fun wrongJsonScalarTypesAreNotCoercedAndPayloadIsBounded() {
        assertNull(decode(entry().put("stableMinVersion", 1)))
        assertNull(decode(entry().put("nightly", nightly().put("minBuild", 123))))
        assertNull(NativeMacCompatibilityPolicy.decode(" ".repeat(NativeMacCompatibilityPolicy.MAX_BYTES) + "{\"entries\":[]}"))
    }
    @Test fun androidLenientJsonExtensionsDuplicatesTrailingGarbageAndDeepNestingAreRejected() {
        listOf("{entries:[]}", "{'entries':[]}", "{\"entries\":[],}", "{\"entries\":[]} garbage",
            "{\"entries\":[],\"entries\":[]}", "{/*comment*/\"entries\":[]}",
            "{\"entries\":[],\"unused\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}").forEach {
            assertNull(it, NativeMacCompatibilityPolicy.decode(it))
        }
        assertNotNull(NativeMacCompatibilityPolicy.decode("{\"entries\":[],\"unused\":{\"text\":\"quotes \\\" { }\",\"number\":1.2e3}}"))
    }
    @Test fun validEmptyPolicyLiftsConstraintsIncludingMissingVersions() {
        val policy = checkNotNull(decode())
        assertNull(policy.violation("default", null)); assertNull(policy.violation("nightly", null))
    }
    @Test fun corruptOrOfflineCacheUsesBakedAndInvalidRefreshRetainsGoodPolicy() {
        var disk = "bad-cache"
        val cache = NativeMacPolicyCache({ disk }, { disk = it })
        assertEquals(baked, cache.policy)
        val empty = "{\"entries\":[]}"
        assertNotNull(cache.accept(empty)); assertEquals(empty, disk)
        assertNull(cache.accept("{\"entries\":[{}]}")); assertEquals(empty, disk)
        assertNull(cache.policy.violation("default", null))
        assertNull(NativeMacPolicyCache({ disk }, {}).policy.violation("default", null))
        assertEquals(baked, NativeMacPolicyCache({ error("unreadable") }, {}).policy)
    }
    @Test fun cacheOriginProfileAndAppBuildAreIsolated() {
        val key = NativeMacCompatibilityRuntime.cacheKey("https://cmux.com", "build1")
        assertNotEquals(key, NativeMacCompatibilityRuntime.cacheKey("https://staging.cmux.com", "build1"))
        assertNotEquals(key, NativeMacCompatibilityRuntime.cacheKey("https://cmux.com", "build2"))
        assertNotEquals(key, NativeMacCompatibilityRuntime.cacheKey("https://cmux.com", "build1", "future-profile"))
    }
}
