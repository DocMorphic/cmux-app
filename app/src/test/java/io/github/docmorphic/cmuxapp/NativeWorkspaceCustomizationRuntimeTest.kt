package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceCustomizationRuntimeTest {
    private class Wire : MobileRpcTransport {
        val mac = NativeCredentialStore.PairedMac("fixture-route", "fixture-device", "Fixture Mac")
        val incoming = Channel<ByteArray>(32)
        val writes = mutableListOf<JSONObject>()
        var capabilities = listOf("workspace.actions.v1", WORKSPACE_METADATA_CAPABILITY)
        var row = JSONObject("""{"id":"workspace","window_id":"window","title":"Original","description":"Baseline","custom_color":"#123456","is_pinned":false}""")
        var rejectAction: String? = null
        var afterMutation: (() -> Unit)? = null
        override suspend fun connect() = Unit
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            val params = request.optJSONObject("params") ?: JSONObject()
            var rejected = false
            val result = when (request.getString("method")) {
                "mobile.host.status" -> JSONObject().put("mac_device_id", mac.deviceId).put("capabilities", JSONArray(capabilities))
                "notification.feed.list" -> JSONObject().put("revision", 1).put("notifications", JSONArray())
                "mobile.workspace.list" -> JSONObject().put("workspaces", JSONArray().put(JSONObject(row.toString())))
                "workspace.action" -> {
                    writes += JSONObject(params.toString())
                    assertEquals("workspace", params.getString("workspace_id")); assertEquals("window", params.getString("window_id"))
                    val action = params.getString("action")
                    rejected = rejectAction == action
                    if (!rejected) when (action) {
                        "rename" -> row.put("title", params.getString("title"))
                        "set_description" -> row.put("description", params.getString("description"))
                        "clear_description" -> row.remove("description")
                        "set_color" -> row.put("custom_color", params.getString("color"))
                        "clear_color" -> row.remove("custom_color")
                        "pin" -> row.put("is_pinned", true)
                        "unpin" -> row.put("is_pinned", false)
                        else -> error("Unexpected action $action")
                    }
                    afterMutation?.invoke()
                    JSONObject()
                }
                else -> JSONObject()
            }
            val response = JSONObject().put("id", request.getString("id")).put("ok", !rejected)
            if (rejected) response.put("error", JSONObject().put("code", "rejected").put("message", "Description rejected"))
            else response.put("result", result)
            incoming.send(MobileFrameCodec.encode(response.toString().toByteArray()))
        }
        override fun close() { incoming.close() }
    }
    private suspend fun fixture(wire: Wire = Wire(), action: suspend (Wire, NativeFeedCoordinator) -> Unit) = coroutineScope {
        val job = SupervisorJob(coroutineContext[Job]); val scope = CoroutineScope(coroutineContext + job)
        val coordinator = NativeFeedCoordinator(scope, { MobileRpcClient(wire, { "fixture" }).also { it.connect() } }, { true })
        try {
            coordinator.updateMacs(listOf(wire.mac))
            withTimeout(2000) { coordinator.sources.first { it[wire.mac.origin]?.hasWorkspaceSnapshot == true } }
            action(wire, coordinator)
        } finally { coordinator.close(); job.cancelAndJoin() }
    }
    private fun baseline(coordinator: NativeFeedCoordinator, wire: Wire) =
        WorkspaceCustomizationDraft.from(coordinator.sources.value.getValue(wire.mac.origin).workspaces.single())

    @Test fun offlineSnapshotKeepsDiscoveryButCannotAuthorizeAMutation() = runBlocking {
        fixture { wire, coordinator ->
            val initial = baseline(coordinator, wire)
            coordinator.pause()
            val retained = coordinator.sources.value.getValue(wire.mac.origin)
            assertEquals(NativeFeedAvailability.OFFLINE, retained.availability)
            assertTrue(retained.canCustomizeWorkspace())
            assertEquals(initial, WorkspaceCustomizationDraft.from(retained.workspaces.single()))
            assertTrue(runCatching { coordinator.customizeWorkspace(wire.mac, "workspace", initial, initial.copy(name = "Offline edit")) }.isFailure)
            assertTrue(wire.writes.isEmpty())
            coordinator.retainMacs(emptyList())
            assertTrue(coordinator.sources.value.isEmpty())
        }
    }
    @Test fun customizationDiscoveryRequiresBothOwnerCapabilitiesAcrossConnectionStates() {
        val mac = Wire().mac
        NativeFeedAvailability.entries.forEach { availability ->
            val source = NativeFeedSource(mac, availability = availability,
                capabilities = setOf("workspace.actions.v1", WORKSPACE_METADATA_CAPABILITY))
            assertTrue(source.canCustomizeWorkspace())
            assertFalse(source.copy(capabilities = setOf(WORKSPACE_METADATA_CAPABILITY)).canCustomizeWorkspace())
            assertFalse(source.copy(capabilities = setOf("workspace.actions.v1")).canCustomizeWorkspace())
            assertFalse(source.copy(capabilities = emptySet()).canCustomizeWorkspace())
        }
    }
    @Test fun metadataMutationsUseVerifiedOwnerAndPublishFreshList() = runBlocking {
        fixture { wire, coordinator ->
            val initial = baseline(coordinator, wire)
            val result = coordinator.customizeWorkspace(wire.mac, "workspace", initial,
                initial.copy(name = "Updated", description = null, color = "#ABCDEF", pinned = true))
            assertTrue(result.succeeded)
            assertEquals(listOf("rename", "clear_description", "set_color", "pin"), wire.writes.map { it.getString("action") })
            val row = coordinator.sources.value.getValue(wire.mac.origin).workspaces.single()
            assertEquals("Updated", row.title); assertNull(row.description); assertEquals("#ABCDEF", row.color); assertTrue(row.isPinned)
        }
    }
    @Test fun partialRejectionRefreshesAndSubsequentRetryOmitsLandedRename() = runBlocking {
        fixture { wire, coordinator ->
            val initial = baseline(coordinator, wire); wire.rejectAction = "set_description"
            val result = coordinator.customizeWorkspace(wire.mac, "workspace", initial,
                initial.copy(name = "Updated", description = "New", color = null))
            assertFalse(result.succeeded); assertEquals("Updated", result.baseline?.name)
            wire.rejectAction = null; wire.writes.clear()
            assertTrue(coordinator.customizeWorkspace(wire.mac, "workspace", result.baseline!!, result.display!!).succeeded)
            assertEquals(listOf("set_description", "clear_color"), wire.writes.map { it.getString("action") })
        }
    }
    @Test fun interveningMacEditPreventsClobberAndOwnerRevocationStopsRemainingFields() = runBlocking {
        fixture { wire, coordinator ->
            val initial = baseline(coordinator, wire)
            wire.afterMutation = { wire.row.put("description", "Mac edit") }
            val result = coordinator.customizeWorkspace(wire.mac, "workspace", initial, initial.copy(name = "Updated", description = "Phone edit"))
            assertFalse(result.succeeded); assertEquals("Mac edit", result.baseline?.description)
            assertEquals(listOf("rename"), wire.writes.map { it.getString("action") })
            wire.writes.clear(); wire.afterMutation = { coordinator.retainMacs(emptyList()) }
            runCatching { coordinator.customizeWorkspace(wire.mac, "workspace", result.baseline!!,
                result.baseline.copy(name = "Changed again", pinned = true)) }
            assertEquals(listOf("rename"), wire.writes.map { it.getString("action") })
        }
    }
    @Test fun missingActionsCapabilityCannotWriteEvenIfMetadataIsAdvertised() = runBlocking {
        fixture(Wire().apply { capabilities = listOf(WORKSPACE_METADATA_CAPABILITY) }) { wire, coordinator ->
            val initial = baseline(coordinator, wire)
            assertTrue(runCatching { coordinator.customizeWorkspace(wire.mac, "workspace", initial, initial.copy(color = null)) }.isFailure)
            assertTrue(wire.writes.isEmpty())
        }
    }
    @Test fun callerWithdrawalBeforeSaveOrBetweenFieldsPreventsFurtherWrites() = runBlocking {
        fixture { wire, coordinator ->
            val initial = baseline(coordinator, wire)
            assertTrue(runCatching { coordinator.customizeWorkspace(wire.mac, "workspace", initial,
                initial.copy(name = "No write")) { false } }.isFailure)
            assertTrue(wire.writes.isEmpty())
            var visible = true
            wire.afterMutation = { visible = false }
            val result = coordinator.customizeWorkspace(wire.mac, "workspace", initial,
                initial.copy(name = "Landed", description = "Never sent", pinned = true)) { visible }
            assertFalse(result.succeeded)
            assertEquals(listOf("rename"), wire.writes.map { it.getString("action") })
            assertEquals("Landed", wire.row.getString("title")); assertEquals("Baseline", wire.row.getString("description"))
        }
    }
}
