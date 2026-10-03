package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSimulatorTest {
    private val panel = "abcdef01-2345-6789-abcd-ef0123456789"
    private fun descriptor() = JSONObject().put("panel_id", panel).put("workspace_id", "w").put("title", "iPhone Simulator")
        .put("status", "ready").put("is_ready", true).put("supports_touch", true).put("supports_keyboard", true)
        .put("supports_hardware_buttons", true).put("supports_rotation", true)

    @Test fun separateSimulatorInventoryMakesSimulatorOnlyWorkspacesNavigableAndRetargetable() {
        val json = JSONObject().put("workspaces", JSONArray().put(JSONObject().put("id", "w")
            .put("simulators", JSONArray().put(descriptor()).put(descriptor()))))
        val workspace = parseWorkspaces(json).single()
        assertTrue(workspace.hasPanes); assertTrue(workspace.surfaces.isEmpty()); assertTrue(workspace.terminals.isEmpty())
        assertEquals(1, workspace.simulators.size); assertEquals(panel, workspace.macSurfaces.single().id)
        assertEquals("Simulator", workspace.macSurfaces.single().label)
        assertEquals("w", workspace.macSurfaces.single().simulator!!.workspaceId)
        val notification = NativeNotification("n", "gone", panel, "Ready", "", false, retargetsToLiveSurfaceOwner = true)
        assertEquals("w", notification.destination(listOf(workspace))?.id)
        assertTrue(notification.searchFields(listOf(workspace), "Mac").contains("iPhone Simulator"))
    }

    @Test fun invalidOrWrongWorkspaceDescriptorsCannotOpenALane() {
        assertNotNull(NativeSimulator.read(descriptor(), "w"))
        assertNull(NativeSimulator.read(descriptor(), "other"))
        assertNull(NativeSimulator.read(descriptor().put("panel_id", "1-2-3-4-5"), "w"))
        assertNull(NativeSimulator.read(descriptor().put("is_ready", "true"), "w"))
        val missing = descriptor(); missing.remove("supports_touch")
        assertNull(NativeSimulator.read(missing, "w"))
    }

    @Test fun sharedUpdatesPreservePersonalizedOwnershipUntilTheOwnerIsRemoved() {
        val personalized = NativeSimulator.read(descriptor().put("owner_connection_id", "connection")
            .put("is_owned_by_current_connection", true), "w")!!
        val shared = NativeSimulator.read(descriptor().put("owner_connection_id", "connection"), "w")!!
        assertEquals(true, shared.preservingOwnership(personalized).ownedByCurrentConnection)
        assertEquals(false, shared.copy(ownedByCurrentConnection = false).preservingOwnership(personalized).ownedByCurrentConnection)
        assertEquals(false, shared.copy(ownerConnectionId = null).preservingOwnership(personalized).ownedByCurrentConnection)
        assertNull(shared.preservingOwnership(personalized.copy(panelId = "different")).ownedByCurrentConnection)
    }

    @Test fun deviceAndRecoverRpcUseExactScopeAndIndependentCapabilityGates() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val device = JSONObject().put("udid", "sim-device").put("name", "iPhone").put("runtime_name", "iOS 26")
            .put("family", "iPhone").put("state", "Shutdown").put("is_selected", false)
        val actions = SimulatorActions(true, true, "w", panel) { method, params ->
            assertEquals("w", params.getString("workspace_id")); assertEquals(panel, params.getString("panel_id"))
            assertFalse(params.has("surface_id")); calls += method
            if (method.endsWith(".select")) assertEquals("sim-device", params.getString("udid"))
            JSONObject().put("devices", JSONArray().put(device))
        }
        val item = actions.devices().single(); actions.select(item); actions.recover()
        assertEquals(listOf("mobile.simulator.devices.list", "mobile.simulator.device.select", "mobile.simulator.recover"), calls)
        assertTrue(runCatching { actions.select(item.copy(selected = true)) }.isFailure)
        val denied = SimulatorActions(false, false, "w", panel) { _, _ -> fail("Unsupported RPC sent"); JSONObject() }
        assertTrue(runCatching { denied.devices() }.isFailure)
        assertTrue(runCatching { denied.select(item) }.isFailure)
        assertTrue(runCatching { denied.recover() }.isFailure)
        assertEquals(3, calls.size)
    }

    @Test fun unknownHostDetailsNeverAppearAsUserFacingMessages() {
        assertEquals("Another device took over this Simulator stream.", simulatorUnavailableDetail("superseded"))
        assertEquals("The Mac closed this Simulator stream.", simulatorUnavailableDetail("sensitive host diagnostic"))
        assertTrue(SimHostStatus.WORKER_CRASHED.needsRecovery)
        assertFalse(SimHostStatus.CLOSED.needsRecovery)
    }

    @Test fun aspectFitRejectsLetterboxBeginsAndClampsDragsWithoutRotatingCoordinates() {
        val portrait = SimVideoRect.fit(100, 200, 400, 400)
        assertEquals(SimVideoRect(100f, 0f, 200f, 400f), portrait)
        assertNull(portrait.point(99f, 100f, false)); assertNull(portrait.point(300f, 100f, false))
        assertEquals(.5f to .25f, portrait.point(200f, 100f, false))
        assertEquals(0f to 1f, portrait.point(-100f, 500f, true))
        assertEquals(SimVideoRect(0f, 100f, 400f, 200f), SimVideoRect.fit(200, 100, 400, 400))
        assertNull(SimVideoRect.fit(0, 200, 400, 400).point(0f, 0f, true))
        assertNull(portrait.point(Float.NaN, 0f, true))
    }

    @Test fun singlePointerKeepsItsIdentityAndDiscardNeverEmitsIntoANewAttachment() {
        val rect = SimVideoRect.fit(100, 200, 400, 400); val tracker = SimTouchTracker()
        assertNull(tracker.begin(3, 0f, 0f, rect, 1u))
        assertEquals(SimInput.Touch(SimTouchPhase.BEGAN, 0, .5f, .5f, 2u), tracker.begin(3, 200f, 200f, rect, 2u))
        assertNull(tracker.begin(4, 200f, 200f, rect, 3u))
        assertNull(tracker.end(4, 200f, 200f, rect, 4u))
        assertEquals(SimInput.Touch(SimTouchPhase.MOVED, 0, 1f, 0f, 5u), tracker.move(3, 500f, -10f, rect, 5u))
        assertEquals(SimInput.Touch(SimTouchPhase.CANCELLED, 0, 1f, 0f, 6u), tracker.cancel(6u))
        assertNull(tracker.end(3, 200f, 200f, rect, 7u))
        tracker.begin(8, 200f, 200f, rect, 8u); tracker.discard()
        assertNull(tracker.move(8, 220f, 220f, rect, 9u)); assertNull(tracker.end(8, 220f, 220f, rect, 10u))
    }
}
