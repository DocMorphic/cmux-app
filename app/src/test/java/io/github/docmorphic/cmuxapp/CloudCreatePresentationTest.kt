package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class CloudCreatePresentationTest {
    private fun presentation(limits: String = "{}") = CloudCreatePresentation(CloudResponseDecoding.catalog(
        JSONObject("""{"vms":[],"limits":$limits}""")))
    @Test fun unknownCapabilitiesUseIosDesktopAndEightGbDefaults() {
        val model = presentation()
        assertEquals(CloudMachineKind.DESKTOP, model.kind)
        assertEquals(listOf(8192), model.sizes); assertEquals(8192, model.defaultMemory)
        assertEquals(CloudMachineCreateOptions(CloudMachineKind.DESKTOP, memoryMb = 8192), model.options(8192))
        assertEquals("8 GB RAM · 32 GB disk", model.label(8192))
    }
    @Test fun serverKindsSizesAndDefaultSelectionAreHonored() {
        val model = presentation("""{"imageKinds":[{"kind":"base"}],"memoryOptionsMb":[16384,123,4096,4096]}""")
        assertEquals(CloudMachineKind.BASE, model.kind)
        assertEquals(listOf(4096, 16384), model.sizes); assertEquals(4096, model.defaultMemory)
        assertTrue(runCatching { model.options(8192) }.isFailure)
    }
    @Test fun lockedSizesOfferPlansButCannotBeSubmitted() {
        val model = presentation("""{"memoryOptionsMb":[8192],"lockedMemoryOptionsMb":[32768,65536],
          "memoryUpgradePlanId":"pro","memoryUpgradePlansByMb":{"65536":"max"}}""")
        assertEquals(listOf(32768, 65536), model.lockedSizes)
        assertEquals("pro", model.upgradePlan(32768)); assertEquals("max", model.upgradePlan(65536))
        assertEquals("32 GB and 64 GB machines need cmux Pro and Max.", model.lockedSizesNote)
        assertEquals("Upgrade to Pro and Max", model.upgradeActionTitle)
        assertEquals("32 GB RAM · 128 GB disk · Requires Pro", model.lockedLabel(32768))
        assertEquals("64 GB RAM · 128 GB disk", model.label(65536))
        assertTrue(runCatching { model.options(32768) }.isFailure)
    }
    @Test fun usageComesFromServerLimitsAndPool() {
        val model = presentation("""{"maxActiveVms":5,"activeVmCount":2,"poolVcpus":20,"poolMemoryMb":40960,
            "usedVcpus":16,"usedMemoryMb":32768}""")
        assertEquals("2 of 5 machines in use", model.machineUsage)
        assertEquals("16 of 20 vCPUs · 32 of 40 GB RAM in use", model.poolUsage)
    }

    @Test fun upgradePriorityNormalizesPlanIdsAndKeepsSizeSpecificPlans() {
        val model = presentation("""{"memoryOptionsMb":[8192],"lockedMemoryOptionsMb":[16384,32768,65536],
          "memoryUpgradePlanId":" Pro ","memoryUpgradePlansByMb":{"65536":" MAX "}}""")
        assertEquals("pro", model.upgradePlan(16384)); assertEquals("Pro", model.planLabel(32768))
        assertEquals("max", model.preferredUpgradePlan)
        assertEquals("Max", model.planLabel(65536))
        assertEquals("Upgrade to Pro and Max", model.upgradeActionTitle)
        assertEquals("max", cloudPlansUrl(model.preferredUpgradePlan).toHttpUrl().queryParameter("plan"))
        assertNull(presentation().preferredUpgradePlan)
        assertNull(presentation().lockedSizesNote)
        assertNull(presentation().upgradeActionTitle)
        val unknownPlan = presentation("""{"lockedMemoryOptionsMb":[16384]}""")
        assertEquals("16 GB RAM · 64 GB disk", unknownPlan.lockedLabel(16384))
        assertNull(unknownPlan.lockedSizesNote)
    }
    @Test fun planDestinationCannotChangeOriginOrInjectMoreQueryParameters() {
        val plan = "pro&return_to=https://example.test/#fragment"
        val url = cloudPlansUrl(plan).toHttpUrl()
        assertEquals("https", url.scheme); assertEquals("cmux.com", url.host); assertEquals("/pricing", url.encodedPath)
        assertEquals(setOf("plan"), url.queryParameterNames); assertEquals(plan, url.queryParameter("plan"))
        assertNull(url.fragment)
        assertEquals("https://cmux.com/pricing", cloudPlansUrl(null))
        assertEquals("https://cmux.com/pricing", cloudPlansUrl("  "))
    }
}
