package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Physical RPC acceptance only: no Activity, keyboard or user workspace mutations. */
class LiveNativeTodoCheck {
    @Test fun disposableChecklistMutationsSurviveReconnect() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cmux_live_todo_fixture") == "true")
        check(!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receipt = File(context.filesDir, "live-todo-fixture.json")
        check(!receipt.exists()) { "Inspect the previous Todo fixture receipt before rerunning" }
        var stage = "existing account"
        var creationAttempted = false
        var closed = false
        var failure: Throwable? = null
        NativeAppConnections.acquire(context).use { handle ->
            val connections = handle.connections
            val probe = Any()
            connections.setProbeActive(probe, true)
            var client: MobileRpcClient? = null
            var owned: NativeWorkspace? = null
            try { withTimeout(120_000) {
                check(connections.account.isSignedIn())
                val login = connections.store.taskSession()
                val team = checkNotNull(connections.teams.refresh().scope)
                val mac = connections.store.pairedMacs().filter {
                    connections.connector.allowsSaved(it) &&
                        PairingCodeParser.parse(it.code).getOrNull() is PairingCode.Iroh
                }.single()
                suspend fun connect(): MobileRpcClient {
                    check(connections.teams.isCurrent(team) && connections.connector.allowsSaved(mac))
                    val next = connections.connector.connectSaved(mac, connections.account)
                    try {
                        val status = next.hostStatus()
                        mac.requireMatchingHost(status)
                        val caps = status.optJSONArray("capabilities")
                        check(caps != null && (0 until caps.length()).any { caps.optString(it) == "todo.v1" })
                        return next
                    } catch (error: Throwable) { next.close(); throw error }
                }
                stage = "verified native connection and Todo capability"
                client = connect()
                val first = checkNotNull(client)
                val existing = parseAuthoritativeWorkspaces(first.workspaces()).map { it.id }.toSet()
                val title = "Android Todo check " + UUID.randomUUID().toString().take(8)
                stage = "create disposable workspace"
                creationAttempted = true
                val fixture = TaskCreationResult.parse(first.request("workspace.create",
                    JSONObject().put("title", title), timeoutMillis = 30_000)).created
                check(fixture.id !in existing)
                owned = fixture
                receipt.writeText(JSONObject().put("id", fixture.id).put("windowId", fixture.windowId)
                    .put("title", title).toString())
                check(fixture.title == title)
                stage = "open fixture checklist without focus"
                first.request("mobile.todo.open", JSONObject().put("workspace_id", fixture.id).put("focus", false))
                suspend fun snapshot(): TodoSnapshot {
                    check(connections.teams.isCurrent(team) && connections.store.taskSession() == login)
                    val workspace = parseAuthoritativeWorkspaces(checkNotNull(client).workspaces()).single { it.id == fixture.id }
                    return checkNotNull(TodoSnapshot.decode(workspace.macSurfaces.single { it.kind == "todo" }.todoJson))
                }
                suspend fun mutate(value: TodoMutation): TodoSnapshot {
                    check(connections.teams.isCurrent(team) && connections.store.taskSession() == login)
                    val (method, params) = value.request(fixture.id)
                    checkNotNull(client).request(method, params) // No retry of an uncertain mutation.
                    return snapshot()
                }
                check(snapshot().items.isEmpty())
                stage = "add and edit authoritative Todo items"
                val a = mutate(TodoMutation.Add("  Android fixture α  ")).items.single()
                check(a.text == "Android fixture α" && a.origin == "user" && a.state == TodoItemState.PENDING)
                val b = mutate(TodoMutation.Add("Second fixture item")).items.single { it.id != a.id }
                var current = mutate(TodoMutation.Edit(a.id, "Edited 👩‍💻 e\u0301"))
                check(current.items.single { it.id == a.id }.text == "Edited 👩‍💻 e\u0301")
                stage = "state and order transitions"
                current = mutate(TodoMutation.SetState(a.id, TodoItemState.WORKING))
                check(current.items.single { it.id == a.id }.state == TodoItemState.WORKING)
                current = mutate(TodoMutation.Move(b.id, 0))
                check(current.items.map { it.id } == listOf(b.id, a.id))
                current = mutate(TodoMutation.SetState(b.id, TodoItemState.COMPLETED))
                check(current.items.map { it.id } == listOf(a.id, b.id) && current.completed == 1)
                current = mutate(TodoMutation.SetState(b.id, TodoItemState.PENDING))
                check(current.items.map { it.id } == listOf(a.id, b.id) && current.completed == 0)
                stage = "manual and automatic status"
                current = mutate(TodoMutation.SetStatus(TodoStatus.REVIEW))
                check(current.status == TodoStatus.REVIEW && !current.statusHidden)
                current = mutate(TodoMutation.CycleStatus)
                check(current.status == TodoStatus.DONE && !current.statusHidden)
                current = mutate(TodoMutation.SetStatus(null))
                check(!current.statusHidden)
                stage = "fresh native reconnect preserves exact checklist"
                first.close(); client = null
                client = connect()
                check(first.events !== checkNotNull(client).events)
                check(snapshot() == current)
                stage = "remove only fixture items"
                check(mutate(TodoMutation.Remove(a.id)).items.map { it.id } == listOf(b.id))
                check(mutate(TodoMutation.Remove(b.id)).items.isEmpty())
                println("CMUX_LIVE_TODO_REPORT " + JSONObject().put("mutationsVerified", true)
                    .put("freshReconnectVerified", true).put("loginPreserved", connections.store.taskSession() == login))
            } } catch (error: Throwable) {
                failure = AssertionError("Live Todo check failed at $stage (${error.javaClass.simpleName})")
            } finally {
                val fixture = owned
                if (fixture != null && client != null) withContext(NonCancellable) {
                    try { withTimeout(15_000) {
                        checkNotNull(client).closeWorkspace(fixture.id, fixture.windowId)
                        while (parseAuthoritativeWorkspaces(checkNotNull(client).workspaces()).any { it.id == fixture.id }) delay(250)
                        closed = true
                        check(receipt.delete())
                    } } catch (_: Exception) { /* Leave receipt for inspection; never repeat an uncertain close. */ }
                }
                client?.close()
                connections.setProbeActive(probe, false)
            }
        }
        println("CMUX_LIVE_TODO_CLEANUP " + JSONObject().put("creationAttempted", creationAttempted).put("fixtureClosed", closed))
        failure?.let { throw it }
        check(closed) { "Todo fixture cleanup was not verified" }
    }
}
