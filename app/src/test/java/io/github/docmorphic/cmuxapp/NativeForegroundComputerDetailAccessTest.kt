package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeForegroundComputerDetailAccessTest {
    private val original = NativeTeamScope("fixture-login", "user", "original", 1)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
        "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user", "device", "Original", "default"), original)
    private class Wire : MobileRpcTransport {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        val methods = mutableListOf<String>()
        var afterRequest: (String) -> Unit = {}
        var awake = false
        override suspend fun connect() {}
        override suspend fun read() = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            val method = request.getString("method")
            methods += method
            val result = when (method) {
                "mobile.host.status" -> JSONObject().put("mac_device_id", "device").put("mac_instance_tag", "default")
                    .put("capabilities", JSONArray(listOf("caffeine.control.v1")))
                "caffeine.set" -> JSONObject().put("enabled", request.getJSONObject("params").getBoolean("enabled").also { awake = it })
                "caffeine.status" -> JSONObject().put("enabled", awake)
                else -> JSONObject()
            }
            afterRequest(method)
            input.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", result).toString().toByteArray()))
        }
        override fun close() { input.close() }
    }
    private fun access(client: MobileRpcClient, current: () -> Boolean = { true }): NativeForegroundComputerDetailAccess {
        val management = NativeForegroundComputerManagement(original, mac, current, current)
        return NativeForegroundComputerDetailAccess(original, mac, client, management, current)
    }

    @Test fun checksOriginalMacOnExistingWireWithoutChangingSelectedTeamOrClosingRoot() = runBlocking<Unit> {
        val selected = original.copy(teamId = "picker", generation = 2)
        val wire = Wire()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            val details = access(client)
            val report = details.check()
            assertEquals(original, details.team)
            assertEquals("picker", selected.teamId)
            assertTrue(report.identity); assertTrue(report.accountAccess)
            assertEquals(listOf("mobile.host.status", "mobile.workspace.list"), wire.methods)
            assertFalse(client.isClosed)
        }
    }

    @Test fun presentationRejectsAnotherTeamOrTargetEvenWhenTheyShareTheLocator() {
        MobileRpcClient(Wire(), { "fixture" }).use { client ->
            val details = access(client)
            NativeComputerDetailsPresentation(original, details.target, foreground = details)
            NativeComputerDetailsPresentation(original, details.target.copy(name = "Renamed"), foreground = details)
            assertTrue(runCatching { NativeComputerDetailsPresentation(original.copy(teamId = "picker"),
                details.target, foreground = details) }.isFailure)
            assertTrue(runCatching { NativeComputerDetailsPresentation(original,
                details.target.copy(buildTag = "nightly"), foreground = details) }.isFailure)
        }
    }

    @Test fun permissionLossWhileTokenLoadsPreventsAnyDetailRpc() = runBlocking<Unit> {
        val tokenStarted = CompletableDeferred<Unit>(); val token = CompletableDeferred<String>()
        var current = true
        val wire = Wire()
        MobileRpcClient(wire, { tokenStarted.complete(Unit); token.await() }).use { client ->
            client.connect()
            val result = async { runCatching { access(client) { current }.check() } }
            withTimeout(2000) { tokenStarted.await() }
            current = false; token.complete("fixture")
            assertEquals(NativeConnectionReport.Failure.CONNECTION, result.await().getOrThrow().failure)
            assertTrue(wire.methods.isEmpty()); assertFalse(client.isClosed)
        }
    }

    @Test fun lostOwnerAfterHostReplyPreventsWorkspaceReadAndAcceptedReport() = runBlocking<Unit> {
        var current = true
        val wire = Wire().apply { afterRequest = { current = false } }
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            val details = access(client) { current }
            val report = details.check()
            assertFalse(report.identity); assertFalse(report.accountAccess)
            assertEquals(NativeConnectionReport.Failure.CONNECTION, report.failure)
            assertEquals(listOf("mobile.host.status"), wire.methods)
            assertNull(details.power())
            assertTrue(runCatching { details.check() }.isFailure)
        }
    }

    @Test fun powerObserversRestartAfterLifecycleStopWithoutClosingOrReplayingOnRoot() = runBlocking<Unit> {
        val wire = Wire()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            val details = access(client)
            repeat(2) { index ->
                val session = checkNotNull(details.power())
                val job = launch { session.run() }
                withTimeout(2000) { session.state.first { it.enabled == (index == 1) && !it.busy } }
                if (index == 0) {
                    session.setEnabled(true)
                    withTimeout(2000) { session.state.first { it.enabled == true && !it.busy } }
                }
                job.cancelAndJoin()
                assertEquals(NativeMacPowerState(), session.state.value)
                assertFalse(client.isClosed)
            }
            assertEquals(1, wire.methods.count { it == "caffeine.set" })
            assertEquals(2, wire.methods.count { it == "mobile.events.subscribe" })
            assertEquals(2, wire.methods.count { it == "mobile.events.unsubscribe" })
            assertTrue(details.check().accountAccess)
        }
    }

    @Test fun powerSubscriptionRechecksOwnerAfterDelayedTokenAndCannotCallArbitraryMethods() = runBlocking<Unit> {
        val tokenStarted = CompletableDeferred<Unit>(); val token = CompletableDeferred<String>()
        var current = true
        val wire = Wire()
        MobileRpcClient(wire, { tokenStarted.complete(Unit); token.await() }).use { client ->
            client.connect()
            assertTrue(runCatching { client.computerDetailRequest("mobile.terminal.paste", isCurrent = { true }) }.isFailure)
            val subscribe = async { runCatching { client.subscribeComputerPower("fixture-stream") { current } } }
            withTimeout(2000) { tokenStarted.await() }
            current = false; token.complete("fixture")
            assertTrue(subscribe.await().isFailure)
            assertTrue(wire.methods.isEmpty())
            assertFalse(client.isClosed)
        }
    }
}
