package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeMacConnectionSettingsTest {
    private val mac = IrohV2Computer("record", "ab".repeat(32), "mac", "default", "Mac", emptyList())
    private val target = NativeComputerTarget.from(mac)
    private val entry = NativeDirectAddress("192.168.1.20:58470", "Desk")
    private var disk: String? = null
    private var fail = false
    private val store = NativeMacConnectionStore({ disk }, { if (fail) error("disk full"); disk = it })

    @Test fun persistsMethodOrderedAddressesLabelsAndIndividualEnabledState() {
        val entries = listOf(entry, NativeDirectAddress("[fd00::2]:58470", "  VPN  ", false))
        store.update(target, { true }) { NativeMacConnectionPreference(NativeMacConnectionMethod.DIRECT, entries) }
        val restored = NativeMacConnectionStore({ disk }, {})
        assertEquals(NativeMacConnectionMethod.DIRECT, restored.state.value.get(target).method)
        assertEquals("VPN", restored.state.value.get(target).addresses[1].label)
        assertEquals(listOf(entry.address), restored.state.value.intent(mac).addresses)
        assertFalse(restored.state.value.get(target).addresses[1].enabled)
    }

    @Test fun directWithoutEnabledAddressesCannotDialAndAutomaticNeverAdoptsDirectCoordinates() {
        store.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT) }
        assertFalse(store.state.value.intent(mac).dialable)
        store.update(target, { true }) { it.copy(addresses = listOf(entry.copy(enabled = false))) }
        assertFalse(store.state.value.intent(mac).dialable)
        store.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.IROH, addresses = listOf(entry)) }
        assertEquals(emptyList<String>(), store.state.value.intent(mac).addresses)
        assertTrue(store.state.value.intent(mac).dialable)
    }

    @Test fun onlyEffectiveRoutingChangesRetireIntentIncludingRapidRoundTrip() {
        val automatic = store.state.value.intent(mac)
        store.update(target, { true }) { it.copy(addresses = listOf(entry)) }
        assertEquals(automatic, store.state.value.intent(mac))
        store.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT) }
        val direct = store.state.value.intent(mac)
        store.update(target, { true }) { it.copy(addresses = listOf(entry.copy(label = "Other name"))) }
        assertEquals(direct, store.state.value.intent(mac))
        store.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.IROH) }
        assertNotEquals(automatic, store.state.value.intent(mac))
        store.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT) }
        assertNotEquals(direct, store.state.value.intent(mac))
    }

    @Test fun settingAnotherMacOrBuildDoesNotRetireThisMac() {
        val before = store.state.value.intent(mac)
        for (other in listOf(target.copy(deviceId = "other"), target.copy(buildTag = "debug")))
            store.update(other, { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT, addresses = listOf(entry)) }
        assertEquals(before, store.state.value.intent(mac))
        store.removeComputer(target.copy(buildTag = "debug")) { true }
        assertEquals(before, store.state.value.intent(mac))
        assertEquals(1, store.state.value.values.size)
    }

    @Test fun savedWriteFailureAndRetiredOwnerCannotPublishNewRouting() {
        store.update(target, { true }) { it.copy(addresses = listOf(entry)) }
        val before = store.state.value; val bytes = disk
        fail = true
        assertTrue(runCatching { store.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT) } }.isFailure)
        assertEquals(before, store.state.value); assertEquals(bytes, disk)
        fail = false
        var permitted = true
        assertTrue(runCatching { store.update(target, { permitted }) { permitted = false; it.copy(method = NativeMacConnectionMethod.DIRECT) } }.isFailure)
        assertEquals(before, store.state.value)
    }

    @Test fun unreadableAndUnknownSettingsFailClosedAndRecoveryCannotReviveOldIntent() {
        val original = store.state.value.intent(mac)
        for (bad in listOf("not json", "[{\"deviceId\":\"mac\",\"buildTag\":\"default\",\"method\":\"FUTURE\",\"addresses\":[]}]")) {
            disk = bad; store.reload()
            assertTrue(store.state.value.error)
            assertTrue(runCatching { store.state.value.intent(mac) }.isFailure)
            assertTrue(runCatching { store.update(target, { true }) { it } }.isFailure)
        }
        disk = "[]"; store.reload()
        assertFalse(store.state.value.error)
        assertNotEquals(original, store.state.value.intent(mac))
    }

    @Test fun invalidAddressesDuplicatesLabelsAndLimitsCannotChangeDisk() {
        val invalid = listOf(
            listOf(entry.copy(address = "example.com:443")), listOf(entry.copy(address = "127.0.0.1:9")),
            listOf(entry, entry), listOf(entry.copy(label = "bad\nlabel")), listOf(entry.copy(label = "x".repeat(81))),
            (1..17).map { entry.copy(address = "192.168.1.$it:58470") })
        for (entries in invalid) {
            assertTrue(runCatching { store.update(target, { true }) { it.copy(addresses = entries) } }.isFailure)
            assertNull(disk); assertTrue(store.state.value.values.isEmpty())
        }
        store.update(target, { true }) { it.copy(addresses = (1..16).map { entry.copy(address = "192.168.1.$it:58470") }) }
        assertEquals(16, store.state.value.get(target).addresses.size)
    }

    @Test fun uuidAliasesShareSettingsButOpaqueIdentifiersKeepCase() {
        val uuid = "ABCDEF01-2345-6789-ABCD-0123456789AB"
        store.update(target.copy(deviceId = uuid), { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT) }
        assertEquals(NativeMacConnectionMethod.DIRECT, store.state.value.intent(mac.copy(deviceId = uuid.lowercase())).method)
        store.update(target.copy(deviceId = "MAC"), { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT) }
        assertEquals(NativeMacConnectionMethod.IROH, store.state.value.intent(mac).method)
        store.removeComputer(target.copy(deviceId = uuid.lowercase())) { true }
        assertEquals(NativeMacConnectionMethod.IROH, store.state.value.intent(mac.copy(deviceId = uuid)).method)
    }
}
