package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserDialogStateTest {
    private fun dialog(id: String) = JSONObject().put("panel_id", "panel").put("dialog_id", id)

    @Test fun basicAuthenticationUsesSourceNulBoundaryAndCancellationOmitsAllText() {
        val value = dialog("auth").put("kind", "http_basic_authentication").put("text_field", JSONObject().put("initial", "cached-user"))
        val accept = JSONObject().put("role", "default")
        val cancel = JSONObject().put("role", "cancel")
        val response = browserDialogResponseText(value, accept, "ignored", "用户", "fixture-password")
        assertEquals("用户\u0000fixture-password", response)
        val wire = JSONObject().put("text", response).toString()
        assertFalse(wire.contains('\u0000'))
        assertEquals(response, JSONObject(wire).getString("text"))
        assertNull(browserDialogResponseText(value, cancel, "secret", "user", "secret"))
        value.put("kind", "javascript_prompt")
        assertNull(browserDialogResponseText(value, cancel, "secret", "", ""))
        assertEquals("literal response", browserDialogResponseText(value, accept, "literal response", "", ""))
    }

    @Test fun claimAndRestoreCannotReplaceANewerOrAlreadyResolvedPrompt() {
        val state = BrowserDialogState("panel")
        val first = dialog("first").put("title", "Original")
        state.install(first); first.put("title", "Mutated outside the owner")
        val captured = checkNotNull(state.claim("first"))
        assertEquals("Original", captured.getString("title"))
        assertNull(state.pending); assertNull(state.claim("first"))
        state.install(dialog("second"))
        assertFalse(state.restore(captured)); state.resolve("first")
        assertEquals("second", state.pending?.getString("dialog_id"))
        state.resolve("second"); state.install(dialog("second"))
        assertNull(state.pending)
        assertFalse(state.restore(dialog("second")))
    }

    @Test fun ownerClosureAndPanelChecksFenceDelayedRestorationAndNewInstallations() {
        val state = BrowserDialogState("panel")
        state.install(dialog("wrong").put("panel_id", "other")); assertNull(state.pending)
        state.install(dialog("current")); state.install(null)
        val captured = checkNotNull(state.claim("current"))
        state.close()
        assertFalse(state.restore(captured)); state.install(dialog("new")); assertNull(state.pending)
    }
}
