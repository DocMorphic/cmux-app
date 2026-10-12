package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeForegroundPaneContextTest {
    private val first = NativeTeamScope("login", "user", "one", 1)
    private val second = first.copy(teamId = "two", generation = 2)
    private val mac = NativeCredentialStore.PairedMac("shared-public-route", "Mac", "One", "default",
        accountUserId = "user", accountTeamId = "one", stableOrigin = "one-origin")
    private val other = mac.copy(name = "Two", accountTeamId = "two", stableOrigin = "two-origin")

    @Test fun admittedMacKeepsPaneAndTabIdentityWhilePickerTeamChangesEvenWithSamePublicLocator() = runBlocking<Unit> {
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            val current = listOf(other)
            val context = nativeForegroundPaneContext("login", second, current, listOf(mac, other), mac.code, client) { row, _ ->
                first.takeIf { row == mac }
            }
            assertTrue(context.foreground); assertSame(mac, context.mac); assertEquals(first, context.team)
            assertEquals(listOf(mac), context.macs); assertEquals(listOf(other), current)
            val navigation = NativePaneNavigation()
            val old = navigation.select("login", mac, first); old.terminal.value = NativeTerminal("terminal", "Shell")
            assertSame(old, navigation.select("login", context.mac, context.team))
            assertEquals("one", workspaceTabKey("login", context.team, context.mac!!, "workspace")!!.teamId)
        }
    }

    @Test fun absentProofClosedClientAndDifferentLoginCannotPreserveSavedOldTeamPresentation() = runBlocking<Unit> {
        val client = MobileRpcClient(PoolTestTransport(), { "fixture" }); client.connect()
        fun context(login: String? = "login", proof: NativeTeamScope? = first) =
            nativeForegroundPaneContext(login, second, listOf(other), listOf(mac), mac.code, client) { _, _ -> proof }
        assertFalse(context(proof = null).foreground)
        assertFalse(context(login = "replacement").foreground)
        assertFalse(context(login = null).foreground)
        client.close(); assertFalse(context().foreground)
        assertEquals(second, context().team); assertSame(other, context().mac)
    }

    @Test fun mismatchedRecordScopeAndAmbiguousSavedRecordsCannotSupplyContinuation() = runBlocking<Unit> {
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            val invalid = nativeForegroundPaneContext("login", second, listOf(other), listOf(mac), mac.code, client) { _, _ -> second }
            assertFalse(invalid.foreground)
            val ambiguous = nativeForegroundPaneContext("login", second, listOf(other), listOf(mac, mac.copy(name = "duplicate")), mac.code, client) { _, _ -> first }
            assertFalse(ambiguous.foreground)
        }
    }

    @Test fun workspaceEventCannotReadUnderAnotherCodeReplacementHandleOrClosedWire() = runBlocking<Unit> {
        val firstClient = MobileRpcClient(PoolTestTransport(), { "fixture" }); firstClient.connect()
        val next = MobileRpcClient(PoolTestTransport(), { "fixture" }); next.connect()
        try {
            assertEquals("old", nativeForegroundEventCode(firstClient, firstClient, "old", "old"))
            assertNull(nativeForegroundEventCode(firstClient, firstClient, "new", "old"))
            assertNull(nativeForegroundEventCode(firstClient, next, "old", "old"))
            assertNull(nativeForegroundEventCode(firstClient, firstClient, "old", null))
            firstClient.close(); assertNull(nativeForegroundEventCode(firstClient, firstClient, "old", "old"))
        } finally { firstClient.close(); next.close() }
    }
}
