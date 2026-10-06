package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CloudComposerDraftsTest {
    private val owner = NativeTeamScope("login", "user", "team", 1)
    private fun terminal(id: String) = CloudAddress("vm_a", id).identifier
    private fun pool(drafts: TerminalDrafts, scope: NativeTeamScope = owner, current: () -> Boolean = { true }, persist: suspend () -> Unit = {}) =
        SshComposerPool(drafts, { cloudDraftTarget(scope, "vm_a", it) }, persist, current)
    @Test fun textSurvivesHostCloseAndRepositoryReloadWithoutLeakingAcrossTerminalAccountOrTeam() {
        val drafts = TerminalDrafts(); val first = pool(drafts)
        first.open(terminal("a")).edit("unfinished 日本語"); first.open(terminal("b")).edit("another")
        first.close()
        val restored = TerminalDrafts(JSONArray(drafts.saved().toString()))
        pool(restored).use {
            assertEquals("unfinished 日本語", it.open(terminal("a")).current.text)
            assertEquals("another", it.open(terminal("b")).current.text)
        }
        for (other in listOf(owner.copy(login = "new"), owner.copy(userId = "other"), owner.copy(teamId = "other")))
            pool(restored, other).use { assertEquals("", it.open(terminal("a")).current.text) }
        pool(restored, owner.copy(generation = 9)).use { assertEquals("unfinished 日本語", it.open(terminal("a")).current.text) }
    }
    @Test fun pendingSendIsDurableBeforeTransmissionAndProcessRecoveryNeverAutomaticallyResubmits() = runTest {
        val drafts = TerminalDrafts(); var saved: JSONArray? = null
        val pool = pool(drafts, persist = { saved = JSONArray(drafts.saved().toString()) })
        try {
            val draft = pool.open(terminal("a")); draft.edit("echo once")
            val send = checkNotNull(draft.begin()); assertNull(draft.begin())
            draft.persistPending(send)
            val recovered = TerminalDrafts(saved)
            val pending = recovered.state.value.getValue(draft.target)
            assertEquals("echo once", pending.text); assertNull(pending.operation)
            assertEquals(TerminalDrafts.DELIVERY_UNCONFIRMED, pending.error)
            draft.edit("new typing"); draft.finish(send)
            assertEquals("new typing", draft.current.text)
        } finally { pool.close() }
    }
    @Test fun failedWriteAndRetiredOwnerCannotPassTheSendBarrierOrClearTheDraft() = runTest {
        val drafts = TerminalDrafts(); var current = true; var failWrite = true
        val pool = pool(drafts, current = { current }, persist = {
            if (failWrite) throw IOException("disk full")
            current = false
        })
        try {
            val draft = pool.open(terminal("a")); draft.edit("keep me"); val send = draft.begin()!!
            assertTrue(runCatching { draft.persistPending(send) }.exceptionOrNull() is IOException)
            failWrite = false; assertTrue(runCatching { draft.persistPending(send) }.isFailure)
            draft.finish(send); assertEquals("keep me", draft.current.text)
            assertFalse(draft.edit("late old user edit"))
        } finally { pool.close() }
        val state = drafts.state.value.values.single()
        assertEquals("keep me", state.text); assertNull(state.operation); assertEquals(TerminalDrafts.DELIVERY_UNCONFIRMED, state.error)
    }
    @Test fun closingHostSettlesItsPendingDraftWithoutWipingOtherHostsOrAllowingLateClear() {
        val drafts = TerminalDrafts(); val first = pool(drafts); val second = pool(drafts, owner.copy(teamId = "another"))
        val old = first.open(terminal("a")); old.edit("old send"); val send = old.begin()!!
        second.open(terminal("a")).edit("other team's text")
        first.close(); old.finish(send)
        assertEquals("other team's text", second.open(terminal("a")).current.text)
        pool(drafts).use { reopened ->
            val restored = reopened.open(terminal("a"))
            assertEquals("old send", restored.current.text); assertNull(restored.current.operation)
            assertEquals(TerminalDrafts.DELIVERY_UNCONFIRMED, restored.current.error)
        }
        second.close()
    }
    @Test fun repositoryClearRevokesOldBindingsAndOldCloseCannotEraseNewGenerationText() {
        val drafts = TerminalDrafts(); val first = pool(drafts); val old = first.open(terminal("a"))
        old.edit("discarded"); drafts.clear()
        assertFalse(old.isActive()); assertFalse(old.edit("revive"))
        val replacement = pool(drafts); replacement.open(terminal("a")).edit("fresh")
        old.close(); first.close()
        assertEquals("fresh", replacement.open(terminal("a")).current.text)
        replacement.discardWhere { it == terminal("a") }
        assertTrue(drafts.state.value.isEmpty()); replacement.close()
    }
    @Test fun matchingMachineIsRequiredAndLegacySshTargetsCannotAliasCloudTargets() {
        val target = cloudDraftTarget(owner, "vm_a", terminal("a"))
        assertNotEquals(TerminalDrafts.Target("ssh", "ssh", terminal("a")), target)
        assertThrows(IllegalArgumentException::class.java) { cloudDraftTarget(owner, "vm_b", terminal("a")) }
    }
}
