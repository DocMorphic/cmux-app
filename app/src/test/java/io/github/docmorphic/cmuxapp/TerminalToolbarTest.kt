package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalToolbarTest {
    private val action = TerminalToolbarAction("11111111-1111-1111-1111-111111111111", "Status", "pwd\n")

    @Test fun defaultOrderIncludesAllButtonsAndOnlyFilesIsHidden() {
        val layout = TerminalToolbarLayout.defaults()
        assertEquals(TerminalToolbarButton.entries.map { it.id }, layout.order)
        assertEquals(listOf("Ctrl", "Alt", "Cmd", "Shift", "Paste", "Tab", "Esc", "↵"),
            layout.visible.take(8).map { layout.button(it)!!.label })
        assertEquals(setOf(TerminalToolbarButton.FILES.id), layout.order.toSet() - layout.enabled)
    }

    @Test fun editingRetainsIdentityOrderAndHiddenStateThenDeletePrunesReferences() {
        val layout = TerminalToolbarLayout.defaults().save(action).move(action.itemId, 1).toggle(action.itemId, false)
        val edited = layout.save(action.copy(title = "Where", text = "whoami"))
        assertEquals(layout.order, edited.order)
        assertEquals(layout.enabled, edited.enabled)
        assertEquals("whoami", edited.action(action.itemId)!!.output)
        assertFalse(action.itemId in edited.remove(action.itemId).order)
        assertTrue(edited.remove(action.itemId).actions.isEmpty())
        assertEquals(edited, edited.remove(TerminalToolbarButton.CONTROL.id))
    }

    @Test fun resetsRetainCustomCommandsAndRestoreVisibilityAndOrder() {
        val edited = TerminalToolbarLayout.defaults().save(action).move(action.itemId, 0).let { it.copy(enabled = emptySet()) }
        val reset = edited.reset()
        assertEquals(action.itemId, reset.order.last())
        assertTrue(action.itemId in reset.enabled)
        assertFalse(TerminalToolbarButton.FILES.id in reset.enabled)
        assertEquals(listOf(action), reset.actions)
    }

    @Test fun storageRoundTripKeepsHiddenAllAndNormalizesUnknownAndDuplicateIds() {
        val layout = TerminalToolbarLayout.defaults().save(action).copy(enabled = emptySet())
        val json = JSONObject(layout.encode()).put("order", JSONArray(listOf(action.itemId, "unknown", action.itemId)))
        val loaded = TerminalToolbarLayout.decode(json.toString())
        assertEquals(action.itemId, loaded.order.first())
        assertEquals(layout.order.size, loaded.order.size)
        assertTrue(loaded.enabled.isEmpty())
        assertEquals(loaded, TerminalToolbarLayout.decode(loaded.encode()))
    }

    @Test fun moveUsesInsertionBoundaryForBothDirectionsAndKeepsVisibility() {
        val layout = TerminalToolbarLayout.defaults()
        val first = layout.order.first()
        assertEquals(layout, layout.move(first, 1))
        assertEquals(first, layout.move(first, layout.order.size).order.last())
        assertEquals(layout.order.last(), layout.move(layout.order.last(), 0).order.first())
        assertEquals(layout, layout.move(first, -1))
        assertEquals(layout.enabled, layout.move(first, 4).enabled)
    }

    @Test fun macrosKeepUnicodeAndExactNewlineSemanticsAndNeverArmModifiers() {
        assertEquals("pwd\r", action.output)
        assertEquals("你好\r\r🙂\r", action.copy(text = "你好\r\n🙂\n").output)
        assertEquals("ollama run ", TerminalToolbarButton.OLLAMA.key)
        assertEquals("claude --dangerously-skip-permissions\r", TerminalToolbarButton.CLAUDE.key)
        assertEquals("codex --yolo -c model_reasoning_effort=xhigh --search\r", TerminalToolbarButton.CODEX.key)
    }

    @Test fun invalidStorageAndUnsendableActionsAreRejectedBeforeSaving() {
        listOf(action.copy(id = "invalid"), action.copy(title = "\n"), action.copy(text = ""),
            action.copy(text = "界".repeat(6000))).forEach { assertTrue(runCatching { it.validate() }.isFailure) }
        val json = JSONObject(TerminalToolbarLayout.defaults().save(action).encode())
        val actions = json.getJSONArray("actions"); actions.put(actions.getJSONObject(0))
        assertTrue(runCatching { TerminalToolbarLayout.decode(json.toString()) }.isFailure)
        assertTrue(runCatching { TerminalToolbarLayout.decode("{}") }.isFailure)
    }
}
