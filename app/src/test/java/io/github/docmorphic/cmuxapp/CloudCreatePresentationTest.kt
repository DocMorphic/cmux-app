package io.github.docmorphic.cmuxapp

import org.json.JSONObject
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
    @Test fun lockedSizesAreInformationalAndCannotBeSubmitted() {
        val model = presentation("""{"memoryOptionsMb":[8192],"lockedMemoryOptionsMb":[32768,65536],
          "memoryUpgradePlanId":"pro","memoryUpgradePlansByMb":{"65536":"max"}}""")
        assertEquals(listOf(32768, 65536), model.lockedSizes)
        assertEquals("pro", model.upgradePlan(32768)); assertEquals("max", model.upgradePlan(65536))
        assertEquals("64 GB RAM · 128 GB disk", model.label(65536))
        assertTrue(runCatching { model.options(32768) }.isFailure)
    }
    @Test fun usageComesFromServerLimitsAndPool() {
        val model = presentation("""{"maxActiveVms":5,"activeVmCount":2,"poolVcpus":20,"poolMemoryMb":40960,
            "usedVcpus":16,"usedMemoryMb":32768}""")
        assertEquals("2 of 5 machines in use", model.machineUsage)
        assertEquals("16 of 20 vCPUs · 32 of 40 GB RAM in use", model.poolUsage)
    }
}
