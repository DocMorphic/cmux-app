package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTodoTest {
    private fun item(id: String, completed: Boolean = false) = TodoItem(id, "Item $id",
        if (completed) TodoItemState.COMPLETED else TodoItemState.PENDING, "agent")
    private val initial = TodoSnapshot(TodoStatus.TODO, false, listOf(item("a"), item("b"), item("c", true), item("d", true)))

    @Test fun strictSnapshotDecodePreservesHostOrderOriginAndHiddenStatus() {
        val json = """{"status":"needs-attention","status_hidden":true,"items":[{"id":"a","text":"Ship","state":"in_progress","origin":"agent"}]}"""
        val snapshot = TodoSnapshot.decode(json)!!
        assertEquals(TodoStatus.ATTENTION, snapshot.status); assertTrue(snapshot.statusHidden)
        assertEquals(TodoItem("a", "Ship", TodoItemState.WORKING, "agent"), snapshot.items.single())
        assertNull(TodoSnapshot.decode(json.replace("in_progress", "future-state")))
        assertNull(TodoSnapshot.decode(json.replace("\"status_hidden\":true", "\"status_hidden\":\"true\"")))
        val value = JSONObject(json)
        value.getJSONArray("items").put(value.getJSONArray("items").getJSONObject(0))
        assertNull(TodoSnapshot.decode(value.toString()))
        assertNull(TodoSnapshot.decode(null))
    }

    @Test fun stateTransitionsMoveBetweenCompletionPartitionsWithoutLosingOrigin() {
        val completed = applyTodoMutation(initial, TodoMutation.SetState("a", TodoItemState.COMPLETED))!!
        assertEquals(listOf("b", "c", "d", "a"), completed.items.map { it.id })
        val reopened = applyTodoMutation(completed, TodoMutation.SetState("d", TodoItemState.WORKING))!!
        assertEquals(listOf("b", "d", "c", "a"), reopened.items.map { it.id })
        assertEquals("agent", reopened.items[1].origin)
        assertEquals(TodoItemState.PENDING, TodoItemState.COMPLETED.next)
    }

    @Test fun dragIndicesAreFullListIndicesBoundedWithinTheirCompletionPartition() {
        assertEquals(listOf("b", "a", "c", "d"), applyTodoMutation(initial, TodoMutation.Move("a", 3))!!.items.map { it.id })
        assertEquals(listOf("a", "b", "d", "c"), applyTodoMutation(initial, TodoMutation.Move("d", Int.MIN_VALUE))!!.items.map { it.id })
        assertEquals(listOf("a", "b", "d", "c"), applyTodoMutation(initial, TodoMutation.Move("c", Int.MAX_VALUE))!!.items.map { it.id })
    }

    @Test fun addEditRemoveAndStatusRespectBoundsAndRejectMissingIds() {
        val added = applyTodoMutation(initial, TodoMutation.Add("  " + "a".repeat(700) + "  "), "new")!!
        assertEquals(500, added.items.last().text.length); assertEquals("user", added.items.last().origin)
        assertNull(applyTodoMutation(initial.copy(items = (0 until 50).map { item("$it") }), TodoMutation.Add("new")))
        assertNull(applyTodoMutation(initial, TodoMutation.Edit("missing", "text")))
        assertNull(applyTodoMutation(initial, TodoMutation.Add(" \n ")))
        assertEquals("你好", applyTodoMutation(initial, TodoMutation.Edit("a", " 你好 "))!!.items.first().text)
        assertEquals(3, applyTodoMutation(initial, TodoMutation.Remove("a"))!!.items.size)
        assertEquals(TodoStatus.DONE, applyTodoMutation(initial, TodoMutation.SetStatus(TodoStatus.DONE))!!.status)
        assertFalse(applyTodoMutation(initial.copy(statusHidden = true), TodoMutation.SetStatus(null))!!.statusHidden)
        assertEquals(TodoStatus.TODO, TodoStatus.DONE.next)
    }

    @Test fun allMutationRequestsUseExactWorkspaceAndAreNeverControlStreamResent() {
        val mutations = listOf(TodoMutation.Add("a"), TodoMutation.Edit("id", "b"), TodoMutation.SetState("id", TodoItemState.WORKING),
            TodoMutation.Move("id", 3), TodoMutation.Remove("id"), TodoMutation.SetStatus(null), TodoMutation.CycleStatus, TodoMutation.OpenOnMac)
        val expected = listOf("mobile.todo.add", "mobile.todo.edit", "mobile.todo.set_state", "mobile.todo.move", "mobile.todo.remove", "mobile.status.set", "mobile.status.cycle", "mobile.todo.open")
        mutations.forEachIndexed { index, mutation ->
            val (method, params) = mutation.request("workspace")
            assertEquals(expected[index], method); assertEquals("workspace", params.getString("workspace_id"))
            assertFalse(params.has("surface_id")); assertFalse(MobileControlResendPolicy.allows(method, params))
        }
        assertEquals("auto", TodoMutation.SetStatus(null).request("w").second.getString("status"))
        assertEquals("in_progress", TodoMutation.SetState("a", TodoItemState.WORKING).request("w").second.getString("state"))
        assertTrue(TodoMutation.OpenOnMac.request("w").second.getBoolean("focus"))
    }

    @Test fun pendingEditDefersHostSnapshotAndRejectsConcurrentMutation() = runBlocking<Unit> {
        val model = NativeTodoModel(initial)
        val gate = CompletableDeferred<Unit>()
        val work = async(start = CoroutineStart.UNDISPATCHED) { model.perform(TodoMutation.Remove("a")) { gate.await(); null } }
        assertTrue(model.pending); assertEquals(3, model.snapshot.items.size)
        var extra = 0
        assertFalse(model.perform(TodoMutation.Add("duplicate")) { extra++; null }); assertEquals(0, extra)
        val authoritative = initial.copy(status = TodoStatus.REVIEW)
        model.reconcile(authoritative)
        assertEquals(3, model.snapshot.items.size)
        gate.complete(Unit); assertTrue(work.await()); assertEquals(authoritative, model.snapshot)
        assertFalse(model.pending)
    }

    @Test fun rejectedAndUnknownMutationsRollBackOrAdoptDeferredTruthWithoutRetry() = runBlocking<Unit> {
        for (error in listOf(MobileRpcException("rejected", "No"), MobileRpcOutcomeUnknown())) {
            val model = NativeTodoModel(initial); var calls = 0
            val authoritative = initial.copy(status = TodoStatus.WORKING)
            assertFalse(model.perform(TodoMutation.Remove("a")) { calls++; model.reconcile(authoritative); throw error })
            assertEquals(1, calls); assertEquals(authoritative, model.snapshot); assertTrue(model.failure)
            model.dismissFailure(); assertFalse(model.failure)
        }
        val model = NativeTodoModel(initial)
        assertFalse(model.perform(TodoMutation.Remove("a")) { throw IllegalStateException("rejected") })
        assertEquals(initial, model.snapshot)
    }

    @Test fun cancellationRollsBackAndPropagatesWithoutDisplayingAStaleFailure() = runBlocking<Unit> {
        val model = NativeTodoModel(initial)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { model.perform(TodoMutation.Remove("a")) { awaitCancellation() } }
        assertTrue(model.pending); job.cancelAndJoin()
        assertFalse(model.pending); assertFalse(model.failure); assertEquals(initial, model.snapshot)
    }
}
