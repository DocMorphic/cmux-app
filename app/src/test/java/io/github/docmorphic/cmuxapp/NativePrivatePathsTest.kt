package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePrivatePathsTest {
    @Test fun canonicalNumericCoordinatesAndPorts() {
        assertEquals(listOf("192.168.1.5:58470", "[fd00::5]:58470", "100.64.1.2:1", "8.8.8.8:65535"),
            NativePrivateAddress.lines(" 192.168.1.5:058470\n\n[FD00:0:0:0:0:0:0:5]:58470\n100.64.1.2:1\n8.8.8.8:65535 "))
    }
    @Test fun rejectsNonNumericAndForbiddenCoordinates() {
        listOf("host.example:123", "localhost:123", "192.168.01.5:123", "127.0.0.1:1", "0.1.2.3:1",
            "224.1.1.1:1", "255.255.255.255:1", "169.254.1.2:1", "[::]:1", "[::1]:1", "[fe80::1]:1",
            "[ff02::1]:1", "[fd00::1%wlan0]:1", "[fd00:ec2::254]:1", "[::ffff:127.0.0.1]:1",
            "[::ffff:169.254.0.1]:1", "1.2.3.4", "fd00::1:123", "[fd00::1]", "1.2.3.4:0", "1.2.3.4:65536",
            "1.2.3.4:+123", "1.2.3.4:１２３", "1.2.3.4:12/", "[fd00::1]:12#", "1.2.3.4: 12",
            "user@1.2.3.4:12", "1.2.3.4:12:34").forEach { raw ->
            assertTrue(raw, runCatching { NativePrivateAddress.parse(raw) }.isFailure)
        }
    }
    @Test fun enforcesAddressCount() {
        assertTrue(runCatching { NativePrivateAddress.lines("\n ") }.isFailure)
        assertEquals(8, NativePrivateAddress.lines(List(8) { "10.0.0.1:1" }.joinToString("\n")).size)
        assertTrue(runCatching { NativePrivateAddress.lines(List(9) { "10.0.0.1:1" }.joinToString("\n")) }.isFailure)
    }
    private val path = NativePrivatePath("mac", "default", "Test Mac", listOf("10.0.0.2:58470"), true)
    private val mac = IrohV2Computer("record", "a".repeat(64), "mac", "default", "Mac", emptyList())
    @Test fun persistedPathsMatchExactMacAndBuildAndRespectEnabled() {
        var disk: String? = null
        val store = NativePrivatePathStore({ disk }, { disk = it })
        store.upsert(path)
        val reopened = NativePrivatePathStore({ disk }, { disk = it })
        assertEquals(path.addresses, reopened.addresses(mac))
        assertTrue(reopened.addresses(mac.copy(deviceId = "other")).isEmpty())
        assertTrue(reopened.addresses(mac.copy(buildTag = "debug")).isEmpty())
        reopened.upsert(path.copy(enabled = false))
        assertTrue(reopened.addresses(mac).isEmpty())
        assertEquals(1, reopened.load().size)
    }
    @Test fun resetPreservesAddressesAndRemovePreservesOtherMacBuild() {
        var disk: String? = null
        val store = NativePrivatePathStore({ disk }, { disk = it })
        store.upsert(path); store.upsert(path.copy(buildTag = "debug"))
        store.reset()
        assertTrue(store.load().all { !it.enabled && it.addresses == path.addresses })
        store.remove("mac", "default")
        assertEquals(listOf("debug"), store.load().map { it.buildTag })
    }
    @Test fun rejectsCorruptStorageWithoutOverwritingIt() {
        var writes = 0
        val store = NativePrivatePathStore({ "[{\"addresses\":[]}]" }, { writes++ })
        assertTrue(runCatching { store.upsert(path) }.isFailure)
        assertEquals(0, writes)
        assertTrue(store.addresses(mac).isEmpty())
    }
    @Test fun rejectsInvalidSaveBeforePersistingAndLimitsTo64Macs() {
        var disk: String? = null
        val store = NativePrivatePathStore({ disk }, { disk = it })
        assertTrue(runCatching { store.upsert(path.copy(addresses = listOf("localhost:123"))) }.isFailure)
        assertNull(disk)
        repeat(64) { store.upsert(path.copy(deviceId = "mac$it")) }
        assertTrue(runCatching { store.upsert(path) }.isFailure)
        store.upsert(path.copy(deviceId = "mac1", enabled = false))
        assertEquals(64, store.load().size)
    }
    @Test fun scopeIncludesEveryIdentityFieldAndHasUnambiguousEncoding() {
        val fields = listOf("environment", "projectId", "teamId", "userId", "deviceId", "appNamespace", "buildTag")
        val identity = JSONObject(fields.associateWith { it })
        val original = NativePrivatePathStore.identityFile(identity)
        fields.forEach { field ->
            assertNotEquals(field, original, NativePrivatePathStore.identityFile(JSONObject(identity.toString()).put(field, "other")))
        }
        assertEquals(original, NativePrivatePathStore.identityFile(JSONObject(fields.reversed().associateWith { it })))
        val first = JSONObject(identity.toString()).put("teamId", "a/b").put("userId", "c")
        val second = JSONObject(identity.toString()).put("teamId", "a").put("userId", "b/c")
        assertNotEquals(NativePrivatePathStore.identityFile(first), NativePrivatePathStore.identityFile(second))
        assertFalse(path.toString().contains("10.0.0.2"))
    }
}
