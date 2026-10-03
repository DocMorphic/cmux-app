package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal enum class TodoItemState(val wire: String, val title: String) {
    PENDING("pending", "pending"), WORKING("in_progress", "in progress"), COMPLETED("completed", "completed");
    val next get() = entries[(ordinal + 1) % entries.size]
}
internal enum class TodoStatus(val wire: String, val title: String) {
    TODO("todo", "Todo"), WORKING("working", "Working"), ATTENTION("needs-attention", "Needs Attention"),
    REVIEW("review", "Review"), DONE("done", "Done");
    val next get() = entries[(ordinal + 1) % entries.size]
}
internal data class TodoItem(val id: String, val text: String, val state: TodoItemState, val origin: String)
internal data class TodoSnapshot(val status: TodoStatus, val statusHidden: Boolean, val items: List<TodoItem>) {
    val completed get() = items.count { it.state == TodoItemState.COMPLETED }
    companion object {
        const val MAX_ITEMS = 50
        fun decode(json: String?): TodoSnapshot? = runCatching {
            val value = JSONObject(json ?: return null)
            val status = TodoStatus.entries.singleOrNull { it.wire == value.opt("status") } ?: return null
            val hidden = value.opt("status_hidden") as? Boolean ?: return null
            val array = value.getJSONArray("items")
            if (array.length() > MAX_ITEMS) return null
            val ids = mutableSetOf<String>()
            val items = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val id = (item.opt("id") as? String)?.takeIf { it.isNotBlank() && ids.add(it) } ?: return null
                val text = item.opt("text") as? String ?: return null
                val state = TodoItemState.entries.singleOrNull { it.wire == item.opt("state") } ?: return null
                val origin = (item.opt("origin") as? String)?.takeIf { it == "user" || it == "agent" } ?: return null
                TodoItem(id, text, state, origin)
            }
            TodoSnapshot(status, hidden, items)
        }.getOrNull()
    }
}

/** Swift String.prefix counts extended grapheme clusters, not UTF-16 code units. */
internal fun normalizedTodoText(raw: String): String? {
    val text = raw.trim().takeIf { it.isNotEmpty() } ?: return null
    if (text.length <= 500) return text
    if (text.all { it.code < 128 }) return text.take(500)
    val breaks = android.icu.text.BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    var end = breaks.first()
    repeat(500) {
        val next = breaks.next()
        if (next == android.icu.text.BreakIterator.DONE) return text
        end = next
    }
    return text.substring(0, end)
}

internal sealed interface TodoMutation {
    data class Add(val text: String) : TodoMutation
    data class Edit(val id: String, val text: String) : TodoMutation
    data class SetState(val id: String, val state: TodoItemState) : TodoMutation
    data class Move(val id: String, val index: Int) : TodoMutation
    data class Remove(val id: String) : TodoMutation
    data class SetStatus(val status: TodoStatus?) : TodoMutation
    data object CycleStatus : TodoMutation
    data object OpenOnMac : TodoMutation

    fun normalized(): TodoMutation? = when (this) {
        is Add -> normalizedTodoText(text)?.let(::Add)
        is Edit -> normalizedTodoText(text)?.let { copy(text = it) }
        else -> this
    }
    fun request(workspace: String): Pair<String, JSONObject> {
        require(workspace.isNotBlank())
        val params = JSONObject().put("workspace_id", workspace)
        val method = when (this) {
            is Add -> { params.put("text", requireNotNull(normalizedTodoText(text))); "mobile.todo.add" }
            is Edit -> { require(id.isNotBlank()); params.put("id", id).put("text", requireNotNull(normalizedTodoText(text))); "mobile.todo.edit" }
            is SetState -> { require(id.isNotBlank()); params.put("id", id).put("state", state.wire); "mobile.todo.set_state" }
            is Move -> { require(id.isNotBlank()); params.put("id", id).put("to_index", index); "mobile.todo.move" }
            is Remove -> { require(id.isNotBlank()); params.put("id", id); "mobile.todo.remove" }
            is SetStatus -> { params.put("status", status?.wire ?: "auto"); "mobile.status.set" }
            CycleStatus -> "mobile.status.cycle"
            OpenOnMac -> { params.put("focus", true); "mobile.todo.open" }
        }
        return method to params
    }
}

/** Same completion-partition ordering and optimistic edits as TodoSurfaceModel.swift. */
internal fun applyTodoMutation(snapshot: TodoSnapshot, mutation: TodoMutation, addedId: String = UUID.randomUUID().toString()): TodoSnapshot? {
    val items = snapshot.items.toMutableList()
    fun index(id: String) = items.indexOfFirst { it.id == id }
    when (mutation) {
        is TodoMutation.Add -> {
            if (items.size >= TodoSnapshot.MAX_ITEMS) return null
            items += TodoItem(addedId, normalizedTodoText(mutation.text) ?: return null, TodoItemState.PENDING, "user")
        }
        is TodoMutation.Edit -> {
            val at = index(mutation.id); if (at < 0) return null
            items[at] = items[at].copy(text = normalizedTodoText(mutation.text) ?: return null)
        }
        is TodoMutation.SetState -> {
            val at = index(mutation.id); if (at < 0) return null
            val old = items[at]; val next = old.copy(state = mutation.state)
            items[at] = next
            if ((old.state == TodoItemState.COMPLETED) != (next.state == TodoItemState.COMPLETED)) {
                items.removeAt(at)
                if (next.state == TodoItemState.COMPLETED) items += next
                else items.add(items.indexOfFirst { it.state == TodoItemState.COMPLETED }.let { if (it < 0) items.size else it }, next)
            }
        }
        is TodoMutation.Move -> {
            val at = index(mutation.id); if (at < 0) return null
            val item = items.removeAt(at)
            val incomplete = items.filter { it.state != TodoItemState.COMPLETED }.toMutableList()
            val complete = items.filter { it.state == TodoItemState.COMPLETED }.toMutableList()
            if (item.state == TodoItemState.COMPLETED) complete.add((mutation.index.toLong() - incomplete.size).coerceIn(0, complete.size.toLong()).toInt(), item)
            else incomplete.add(mutation.index.coerceIn(0, incomplete.size), item)
            return snapshot.copy(items = incomplete + complete)
        }
        is TodoMutation.Remove -> { val at = index(mutation.id); if (at < 0) return null; items.removeAt(at) }
        is TodoMutation.SetStatus -> return snapshot.copy(status = mutation.status ?: snapshot.status, statusHidden = false)
        TodoMutation.CycleStatus -> return snapshot.copy(status = snapshot.status.next, statusHidden = false)
        TodoMutation.OpenOnMac -> Unit
    }
    return snapshot.copy(items = items.toList())
}

/** Main-thread owner; never retries a mutation or applies another view's reply. */
internal class NativeTodoModel(initial: TodoSnapshot) {
    var snapshot by mutableStateOf(initial); private set
    var pending by mutableStateOf(false); private set
    var failure by mutableStateOf(false); private set
    private var deferred: TodoSnapshot? = null
    fun reconcile(authoritative: TodoSnapshot) {
        if (pending) deferred = authoritative else snapshot = authoritative
    }
    fun dismissFailure() { failure = false }
    suspend fun perform(raw: TodoMutation, send: suspend (TodoMutation) -> TodoSnapshot?): Boolean {
        if (pending) return false
        val mutation = raw.normalized() ?: return false
        val optimistic = applyTodoMutation(snapshot, mutation) ?: return false
        val previous = snapshot
        pending = true; failure = false; deferred = null; snapshot = optimistic
        return try {
            val authoritative = send(mutation)
            snapshot = authoritative ?: deferred ?: snapshot
            true
        } catch (error: Exception) {
            snapshot = deferred ?: previous
            if (error is CancellationException) throw error
            failure = true
            false
        } finally { pending = false; deferred = null }
    }
}
