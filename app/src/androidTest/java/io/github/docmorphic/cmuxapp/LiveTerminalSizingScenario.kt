package io.github.docmorphic.cmuxapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import kotlinx.coroutines.*
import org.json.JSONObject

/** Only called for the workspace created and receipted by LiveNativeUiCheck. */
internal class LiveTerminalSizingScenario(
    private val compose: ComposeTestRule,
    private val probe: MobileRpcClient,
    private val workspace: NativeWorkspace,
    private val stage: (String) -> Unit,
    private val screenshot: (String) -> Unit
) {
    private val surface = checkNotNull(workspace.terminals.firstOrNull()).id
    private var phoneId: String? = null

    private fun <T> network(block: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val result = scope.async { withTimeout(20_000) { block() } }
        try {
            // The real MainActivity effects use the Compose rule's dispatcher.
            compose.waitUntil(25_000) { result.isCompleted }
            return runBlocking { result.await() }
        } finally { scope.cancel(); runBlocking { result.join() } }
    }

    private suspend fun snapshot(): Pair<TerminalSizeState, SharedTerminalGrid> {
        // Deliberately no client/viewport fields: this observer must not become
        // another sizing participant or reattach the foreground terminal.
        val replay = probe.request("mobile.terminal.replay", JSONObject()
            .put("workspace_id", workspace.id).put("surface_id", surface)
            .put("anchor", "screen").put("max_scrollback_rows", 0))
        val state = TerminalSizeState.decode(checkNotNull(replay.optJSONObject("size_state")))
        val mobile = state.participants.filter { it.id.startsWith("mobile:") }
        check(mobile.size <= 1 && mobile.all { phoneId == null || it.id == phoneId }) {
            "Unexpected mobile participant in owned fixture"
        }
        val frame = replay.optJSONObject("render_grid") ?: replay
        return state to SharedTerminalGrid(frame.getInt("columns"), frame.getInt("rows"))
    }

    private fun awaitState(accept: (TerminalSizeState) -> Boolean): TerminalSizeState = network {
        while (true) {
            val (state, rendered) = snapshot()
            if (state.grid == rendered && accept(state)) return@network state
            delay(250)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun editable() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("terminal-size-mode") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openSheet() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("Terminal size,", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasContentDescription("Terminal size,", substring = true)).performClick()
        editable()
    }

    private fun choose(mode: TerminalSizeMode): TerminalSizeState {
        editable()
        compose.onNodeWithTag("terminal-size-mode").performClick()
        compose.onNodeWithText(mode.title, useUnmergedTree = true).performClick()
        val state = awaitState { it.policy.mode == mode }
        editable()
        return state
    }

    private fun self(state: TerminalSizeState) = state.participants.single { it.id == phoneId }

    fun run() {
        stage("shared sizing participant discovery")
        val initial = awaitState { it.participants.count { row -> row.id.startsWith("mobile:") } == 1 }
        phoneId = initial.participants.single { it.id.startsWith("mobile:") }.id
        check(phoneId != probe.terminalParticipantId) { "Observer became the foreground participant" }
        openSheet()
        stage("largest and smallest host negotiation")
        choose(TerminalSizeMode.LARGEST)
        awaitState { state ->
            val sizes = state.participants.filter { it.counts }.mapNotNull { it.viewport }
            sizes.isNotEmpty() && state.grid == SharedTerminalGrid(sizes.maxOf { it.columns }, sizes.maxOf { it.rows })
        }
        choose(TerminalSizeMode.SMALLEST)
        awaitState { state ->
            val sizes = state.participants.filter { it.counts }.mapNotNull { it.viewport }
            sizes.isNotEmpty() && state.grid == SharedTerminalGrid(sizes.minOf { it.columns }, sizes.minOf { it.rows })
        }
        stage("latest and fixed size policy controls")
        choose(TerminalSizeMode.LATEST)
        val fixed = choose(TerminalSizeMode.FIXED)
        if (fixed.policy.fixed != SharedTerminalGrid(80, 24)) {
            compose.onNodeWithTag("terminal-size-columns").performTextReplacement("80")
            compose.onNodeWithTag("terminal-size-rows").performTextReplacement("24")
            compose.onNodeWithText("Apply").performClick()
        }
        awaitState { it.policy.fixed == SharedTerminalGrid(80, 24) && it.grid == SharedTerminalGrid(80, 24) }
        editable()
        compose.onNodeWithTag("terminal-size-grid").assertTextEquals("80 × 24")
        screenshot("terminal-fixed-size.png")

        stage("phone counts override and priority order")
        val priority = choose(TerminalSizeMode.PRIORITY)
        check(self(priority).counts) { "Expected a counting phone before explicit override" }
        compose.onNodeWithTag("terminal-size-counts").performScrollTo().performClick()
        awaitState { self(it).countsOverride == false && !self(it).counts }
        editable()
        compose.onNodeWithText("Use automatic rule").performScrollTo().performClick()
        awaitState { self(it).countsOverride == null && self(it).counts }
        editable()
        val before = awaitState { it.policy.mode == TerminalSizeMode.PRIORITY }
        val presentation = TerminalSizingPresentation(before, phoneId)
        check(presentation.rows.size >= 2 && presentation.rows.first().id == phoneId)
        val expectedOrder = presentation.move(checkNotNull(phoneId), 2).priority
        compose.onNodeWithContentDescription("Options for This phone").performScrollTo().performClick()
        compose.onNodeWithText("Move down").performClick()
        awaitState { it.policy.priority == expectedOrder }
        editable()
        screenshot("terminal-priority-size.png")
        choose(TerminalSizeMode.SMALLEST)
        compose.onNodeWithText("Done").performClick()

        fun disconnect() {
            // Only the phone in this owned workspace is targeted. Never disconnect
            // the Mac or use the sheet's bulk-disconnect operation for this check.
            network { probe.changeTerminalSizing(workspace.id, surface,
                TerminalSizingAction.Disconnect(listOf(checkNotNull(phoneId))), null, 0) { true } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Terminal disconnected").fetchSemanticsNodes().isNotEmpty() }
            awaitState { it.participants.none { row -> row.id == phoneId } }
            compose.onNode(hasSetTextAction()).assertIsNotEnabled()
            compose.onNodeWithText("Send").assertIsNotEnabled()
        }
        stage("explicit detach persists through workspace reopen")
        disconnect()
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(workspace.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(workspace.title).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Terminal disconnected").fetchSemanticsNodes().isNotEmpty() }
        network { repeat(10) {
            check(snapshot().first.participants.none { row -> row.id == phoneId })
            delay(250)
        } }
        compose.onNode(hasSetTextAction()).assertIsNotEnabled()
        screenshot("terminal-explicit-detach.png")

        stage("explicit viewer reattachment")
        compose.onNodeWithText("Reattach as viewer").performClick()
        awaitState { it.participants.any { row -> row.id == phoneId && !row.counts && row.countsOverride == false } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Terminal disconnected").fetchSemanticsNodes().isEmpty() }
        openSheet()
        compose.onNodeWithTag("terminal-size-counts").assertIsOff()
        screenshot("terminal-viewer-reattach.png")
        compose.onNodeWithText("Done").performClick()

        stage("explicit normal reattachment")
        disconnect()
        compose.onNodeWithText("Reattach", useUnmergedTree = true).performClick()
        awaitState { it.participants.any { row -> row.id == phoneId && row.counts && row.countsOverride == null } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Terminal disconnected").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        screenshot("terminal-normal-reattach.png")
        println("CMUX_LIVE_UI_SHARED_SIZING " + JSONObject().put("allPolicyControls", true)
            .put("minMaxFixedHostGrid", true).put("countsAndPriority", true)
            .put("detachedAcrossReopen", true).put("detachedObservationMillis", 2500)
            .put("viewerAndNormalReattach", true))
    }
}
