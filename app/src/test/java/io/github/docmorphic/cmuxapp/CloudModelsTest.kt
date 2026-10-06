package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudModelsTest {
    @Test fun catalogPreservesServerCapabilitiesNamesLimitsAndResourcePool() {
        val catalog = CloudResponseDecoding.catalog(JSONObject("""{
          "vms":[{"id":"vm-123456789012","provider":"fixture","status":" Running ","slug":"cobalt",
            "resources":{"vcpus":2,"memoryMb":4096}},{"id":"second","provider":"fixture","displayName":"My server"}],
          "limits":{"imageKinds":[{"kind":"desktop"},{"kind":"future"},{"kind":"base"}],
            "maxActiveVms":5,"activeVmCount":2,"planId":"fixture-plan","memoryOptionsMb":[1024,2048,0,-1,"8192"],
            "lockedMemoryOptionsMb":[8192],"memoryUpgradePlanId":"larger","memoryUpgradePlansByMb":{"8192":"larger"},
            "poolVcpus":8,"poolMemoryMb":16384,"usedVcpus":2,"usedMemoryMb":4096}}
        """))
        assertEquals(listOf("cobalt", "My server"), catalog.machines.map { it.preferredName })
        assertEquals(CloudMachineLifecycle.RUNNING, catalog.machines[0].lifecycle)
        assertEquals(CloudMachineLifecycle.UNKNOWN, catalog.machines[1].lifecycle)
        assertEquals(setOf(CloudMachineKind.BASE, CloudMachineKind.DESKTOP), catalog.availableKinds)
        val limits = catalog.limits!!
        assertEquals(listOf(1024, 2048), limits.memoryOptionsMb)
        assertEquals(listOf(8192), limits.lockedMemoryOptionsMb)
        assertEquals(mapOf("8192" to "larger"), limits.memoryUpgradePlansByMb)
        assertTrue(limits.pool!!.fits(6, 12288)); assertFalse(limits.pool.fits(7, 12288))
        assertEquals(CloudMachineResources(2, 4096), catalog.machines[0].resources)
    }
    @Test fun absentCapabilitiesAreDifferentFromAnExplicitlyEmptySupportedSet() {
        val absent = CloudResponseDecoding.catalog(JSONObject("""{"vms":[]} """))
        assertNull(absent.availableKinds); assertNull(absent.limits)
        val empty = CloudResponseDecoding.catalog(JSONObject("""{"vms":[],"limits":{"imageKinds":[],"lockedMemoryOptionsMb":[]}}"""))
        assertEquals(emptySet<CloudMachineKind>(), empty.availableKinds)
        assertEquals(emptyList<Int>(), empty.limits!!.lockedMemoryOptionsMb)
        assertNull(empty.limits.pool)
    }
    @Test fun malformedCatalogDoesNotSilentlyHideMachinesAndUnknownStatusDisablesMutations() {
        for (json in listOf("{}", "{\"vms\":[{}]}", """{"vms":[{"id":"a","provider":"p"},{"id":"a","provider":"p"}]}"""))
            assertTrue(runCatching { CloudResponseDecoding.catalog(JSONObject(json)) }.isFailure)
        val machine = CloudResponseDecoding.machine(JSONObject("""{"id":"vm-123456789012","provider":"p","status":"new-state"}"""))
        assertEquals("vm-12345678", machine.preferredName)
        assertFalse(machine.lifecycle.canDelete); assertFalse(machine.lifecycle.canPause); assertFalse(machine.lifecycle.canResume)
        assertTrue(CloudMachineLifecycle.RUNNING.canPause); assertTrue(CloudMachineLifecycle.PAUSED.canResume)
    }
    @Test fun absentInvitationNeverImpliesTrustAndStringBooleansAreRejected() {
        val value = JSONObject("""{"transport":"cmux-remote","route":"private-route","session":"s"}""")
        assertFalse(CloudResponseDecoding.attach(value).trustedCarrier)
        value.put("trustedCarrier", "true")
        assertTrue(runCatching { CloudResponseDecoding.attach(value) }.isFailure)
        value.put("trustedCarrier", true).put("invitation", JSONObject().put("uri", "secret-uri").put("invitationId", "claim"))
        val endpoint = CloudResponseDecoding.attach(value)
        assertTrue(endpoint.trustedCarrier); assertEquals("claim", endpoint.invitation!!.id)
        assertFalse(endpoint.toString().contains("private-route")); assertFalse(endpoint.invitation.toString().contains("secret-uri"))
        assertFalse(CloudResponseDecoding.approved(JSONObject().put("approved", "true")))
        assertTrue(CloudResponseDecoding.approved(JSONObject().put("approved", true)))
    }
    @Test fun enrollmentKeepsRoleConfigurationPrivateAndRejectsInvalidPort() {
        val value = JSONObject("""{"tunnelId":"t","provider":"p","deviceFingerprint":"f","clientConfig":"private-key-config",
            "serverPublicKey":"server-key","endpointHost":"fixture","endpointPort":51820,"routes":["10.0.0.0/24"],
            "address":{"ipv4":"10.0.0.2"},"created":true,"rotated":false}""")
        val tunnel = CloudResponseDecoding.enrollment(value)
        assertEquals(listOf("10.0.0.0/24"), tunnel.routes); assertEquals("10.0.0.2", tunnel.addressV4)
        assertTrue(tunnel.created); assertFalse(tunnel.rotated)
        assertFalse(tunnel.toString().contains("private-key-config"))
        value.put("endpointPort", 65536)
        assertTrue(runCatching { CloudResponseDecoding.enrollment(value) }.isFailure)
    }
}
