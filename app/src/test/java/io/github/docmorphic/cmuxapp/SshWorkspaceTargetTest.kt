package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshWorkspaceTargetTest {
    @Test fun savedTargetsRetainExactDurableIdentitiesAndNullableLegacyFields() {
        val targets = listOf(SshWorkspaceTarget.Shell("local-shell"), SshWorkspaceTarget.Tmux("tmux-host", 0, 9),
            SshWorkspaceTarget.Cmux(SshCmuxSelection("owner λ", "registry", "generation", 1, "key", "ws_1", 4, "tab_1", "term_1", "host-term")),
            SshWorkspaceTarget.Cmux(SshCmuxSelection("owner", null, "generation", 1, null, null, 4, null, null, null)))
        targets.forEach { assertEquals(it, SshWorkspaceTarget.decode(it.encode())) }
    }
    @Test fun malformedTargetsDoNotCoerceIdentityOrNumericIds() {
        val base = SshWorkspaceTarget.Tmux("workspace", 1, 2).encode()
        for (bad in listOf(-1, 1.5, "1", 4294967296L, JSONObject.NULL)) {
            assertNull(SshWorkspaceTarget.decode(JSONObject(base).put("pane", bad).toString()))
        }
        assertNull(SshWorkspaceTarget.decode(JSONObject(base).put("workspace", "").toString()))
        assertNull(SshWorkspaceTarget.decode(JSONObject(base).put("kind", "new-provider").toString()))
        assertNull(SshWorkspaceTarget.decode("not json"))
        assertNull(SshWorkspaceTarget.decode(" ".repeat(16385)))
    }
}
