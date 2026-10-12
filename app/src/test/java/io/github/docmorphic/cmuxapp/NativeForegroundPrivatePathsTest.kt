package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class NativeForegroundPrivatePathsTest {
    private val original = NativeTeamScope("fixture", "user", "original", 1)
    private val selected = original.copy(teamId = "picker", generation = 2)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
        "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user", "device", "Original", "default"), original)
    private val path = NativePrivatePath(mac.deviceId, "default", mac.name, listOf("10.0.0.7:58470"), true)
    private class Wire : MobileRpcTransport {
        val input = Channel<ByteArray>(1)
        override suspend fun connect() {}
        override suspend fun read() = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) = error("Local private addresses must not use any RPC")
        override fun close() { input.close() }
    }
    private class Settings(val owner: NativeTeamScope, override val privatePaths: NativePrivatePathStore?) : IrohAccountBackend {
        override val state = MutableStateFlow(IrohV2ControlState())
        var starts = 0; var managementStarts = 0; var closed = false; var awaited = false
        override suspend fun start() { starts++ }
        override suspend fun startManagement() { managementStarts++ }
        override suspend fun refresh() = error("Local private addresses must not refresh account control")
        override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Must not dial")
        override fun close() { closed = true }
        override suspend fun awaitClosed() { awaited = true }
    }
    private fun details(client: MobileRpcClient, permits: () -> Boolean = { true }): NativeForegroundComputerDetailAccess =
        NativeForegroundComputerDetailAccess(original, mac, client,
            NativeForegroundComputerManagement(original, mac, permits, permits), permits)

    @Test fun originalOwnerSettingsPreservePickerAndSiblingWithoutControlLoginOrMacDial() = runBlocking<Unit> {
        var originalJson: String? = null; var pickerJson: String? = null
        val originalStore = NativePrivatePathStore({ originalJson }, { originalJson = it })
        val pickerStore = NativePrivatePathStore({ pickerJson }, { pickerJson = it })
        val sibling = path.copy(buildTag = "nightly")
        originalStore.upsert(sibling); pickerStore.upsert(path.copy(name = "Picker", addresses = listOf("10.0.0.9:58470")))
        val pickerBefore = pickerJson
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected))
        val created = CopyOnWriteArrayList<Settings>()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { error("Local settings must not fetch a token") }, { owner, _ ->
            Settings(owner, if (owner == original) originalStore else pickerStore).also { created += it }
        }).use { runtime -> MobileRpcClient(Wire(), { error("Local settings must not fetch a token") }).use { client ->
            client.connect(); val access = details(client)
            assertTrue(runtime.foregroundPrivatePaths(access).isEmpty())
            assertEquals(listOf(path), runtime.foregroundPrivatePaths(access) { it.upsert(path) })
            assertTrue(runCatching { runtime.foregroundPrivatePaths(access) { it.upsert(sibling) } }.isFailure)
            runtime.foregroundPrivatePaths(access) { it.remove(path.deviceId, path.buildTag) }
            assertEquals(listOf(sibling), originalStore.load())
            assertEquals(pickerBefore, pickerJson); assertEquals(selected, teams.value.scope)
            assertFalse(client.isClosed)
            val originals = created.filter { it.owner == original }
            assertEquals(4, originals.size)
            assertTrue(originals.all { it.starts == 0 && it.managementStarts == 0 && it.closed && it.awaited })
        } }
    }

    @Test fun ownerLossDuringBackendCreationClosesSettingsBeforeAnyChange() = runBlocking<Unit> {
        val permitted = AtomicBoolean(true); val created = CopyOnWriteArrayList<Settings>()
        var disk: String? = null
        val store = NativePrivatePathStore({ disk }, { disk = it })
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected))
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { owner, _ ->
            Settings(owner, store).also { created += it; if (owner == original) permitted.set(false) }
        }).use { runtime -> MobileRpcClient(Wire(), { "fixture" }).use { client ->
            client.connect(); var changed = false
            assertTrue(runCatching { runtime.foregroundPrivatePaths(details(client, permitted::get)) {
                changed = true; it.upsert(path)
            } }.isFailure)
            assertFalse(changed); assertNull(disk)
            assertTrue(created.single { it.owner == original }.let { it.closed && it.awaited })
            assertFalse(client.isClosed)
        } }
    }

    @Test fun guardLossInsideEditorRejectsWriteAndClosesSettings() = runBlocking<Unit> {
        val permitted = AtomicBoolean(true); val created = CopyOnWriteArrayList<Settings>()
        var disk: String? = null
        val store = NativePrivatePathStore({ disk }, { disk = it }); store.upsert(path)
        val previous = disk
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected))
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { owner, _ ->
            Settings(owner, store).also { created += it }
        }).use { runtime -> MobileRpcClient(Wire(), { "fixture" }).use { client ->
            client.connect()
            assertTrue(runCatching { runtime.foregroundPrivatePaths(details(client, permitted::get)) {
                permitted.set(false); it.upsert(path.copy(enabled = false))
            } }.isFailure)
            assertEquals(previous, disk)
            assertTrue(created.single { it.owner == original }.let { it.closed && it.awaited })
        } }
    }

    @Test fun closedRuntimeAndMissingStoreFailWithoutWritingOrKeepingSettingsOpen() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected)); val created = CopyOnWriteArrayList<Settings>()
        val runtime = NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { owner, _ ->
            Settings(owner, null).also { created += it }
        })
        MobileRpcClient(Wire(), { "fixture" }).use { client ->
            client.connect(); val access = details(client)
            assertTrue(runCatching { runtime.foregroundPrivatePaths(access) }.isFailure)
            assertTrue(created.single { it.owner == original }.let { it.closed && it.awaited })
            runtime.close(); val before = created.size
            assertTrue(runCatching { runtime.foregroundPrivatePaths(access) }.isFailure)
            assertEquals(before, created.size)
            assertFalse(client.isClosed)
        }
    }
}
