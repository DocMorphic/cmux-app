package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeForegroundMacSettingsTest {
    private val owner = NativeTeamScope("login", "user", "original", 1)
    private val mac = NativeCredentialStore.PairedMac("shared", "device", "Original", "default",
        "user", "original", "original-origin", ticketRevision = "revision")

    private fun allowed(active: MobileRpcClient, client: MobileRpcClient? = active, login: String? = "login",
        requested: NativeCredentialStore.PairedMac = mac, admittedMac: NativeCredentialStore.PairedMac? = mac,
        saved: List<NativeCredentialStore.PairedMac> = listOf(mac),
        proof: NativeTeamScope? = owner, selected: Boolean = false, selectedAllowed: Boolean = false,
        ready: Boolean = true, code: String = mac.code, connectedCode: String? = mac.code) =
        nativeForegroundMacSettingsCurrent(login, owner, requested, active, client, admittedMac, ready, code, connectedCode,
            saved, { selected }, { selectedAllowed }, { row, wire -> proof.takeIf { row == mac && wire === active } })

    @Test fun originalOwnerSurvivesPickerChangeButSameLocatorSiblingCannotBorrowIt() = runBlocking<Unit> {
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            assertTrue(allowed(client))
            val sibling = mac.copy(accountTeamId = "selected", stableOrigin = "selected-origin")
            assertFalse(allowed(client, requested = sibling, saved = listOf(mac, sibling)))
            assertFalse(allowed(client, proof = owner.copy(teamId = "selected")))
            assertFalse(allowed(client, proof = null))
            assertTrue(allowed(client, proof = null, selected = true, selectedAllowed = true))
            assertFalse(allowed(client, proof = null, selected = true))
            assertFalse(allowed(client, admittedMac = null))
        }
    }

    @Test fun selectedSameLocatorSiblingCannotBorrowOriginalHandleAfterProofLoss() = runBlocking<Unit> {
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            val sibling = mac.copy(accountTeamId = "selected", stableOrigin = "selected-origin")
            val selected = owner.copy(teamId = "selected", generation = 2)
            assertFalse(nativeForegroundMacSettingsCurrent("login", selected, sibling, client, client, mac,
                true, sibling.code, sibling.code, listOf(mac, sibling), { true }, { true }, { _, _ -> null }))
        }
    }

    @Test fun logoutReplacementHandleAndDisconnectedPaneRetireSettings() = runBlocking<Unit> {
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            MobileRpcClient(PoolTestTransport(), { "fixture" }).use { replacement ->
                client.connect(); replacement.connect()
                assertFalse(allowed(client, login = null)); assertFalse(allowed(client, login = "other"))
                assertFalse(allowed(client, client = replacement)); assertFalse(allowed(client, client = null))
                assertFalse(allowed(client, ready = false)); assertFalse(allowed(client, code = "other"))
                assertFalse(allowed(client, connectedCode = null))
                client.close(); assertFalse(allowed(client))
            }
        }
    }

    @Test fun removedAmbiguousOrRevisedSavedRecordCannotWriteButRenameIsHarmless() = runBlocking<Unit> {
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            assertTrue(allowed(client, saved = listOf(mac.copy(name = "Renamed"))))
            assertFalse(allowed(client, saved = emptyList()))
            assertFalse(allowed(client, saved = listOf(mac, mac.copy(name = "Duplicate"))))
            for (changed in listOf(mac.copy(ticketRevision = "new"), mac.copy(code = "new"),
                mac.copy(deviceId = "new"), mac.copy(instanceTag = "new"), mac.copy(accountUserId = "new"),
                mac.copy(accountTeamId = "new"), mac.copy(nativeRouteCode = "new")))
                assertFalse(allowed(client, saved = listOf(changed)))
        }
    }
}
