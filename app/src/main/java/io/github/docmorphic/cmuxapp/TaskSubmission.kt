package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** Mirrors MobileTaskSubmissionIdentity: compare effective requests, not UI edits. */
internal class TaskSubmissionIdentity {
    private data class Request(val origin: String, val parameters: JSONObject, val id: UUID) {
        fun matches(origin: String, parameters: JSONObject) = this.origin == origin && equivalent(this.parameters, parameters)
    }
    private var baseline: Request? = null
    private var divergent: Request? = null

    fun resolve(origin: String, parameters: JSONObject): JSONObject {
        // Never retain mutable caller JSON or include the caller's provisional ID in equivalence.
        val effective = JSONObject(parameters.toString()).apply { remove("operation_id") }
        val match = baseline?.takeIf { it.matches(origin, effective) }
            ?: divergent?.takeIf { it.matches(origin, effective) }
        val request = match ?: Request(origin, effective, UUID.randomUUID()).also {
            if (baseline == null) baseline = it else divergent = it
        }
        return JSONObject(request.parameters.toString()).put("operation_id", request.id.toString())
    }

    /** A transmitted request becomes the retry baseline, including after a timeout. */
    fun submitted(origin: String, parameters: JSONObject) {
        val effective = JSONObject(parameters.toString()).apply { remove("operation_id") }
        baseline = Request(origin, effective, UUID.fromString(parameters.getString("operation_id")))
        divergent = null
    }

    /** A completed host operation must never become the normal retry baseline again. */
    fun retire(origin: String, parameters: JSONObject): JSONObject =
        JSONObject(parameters.toString()).put("operation_id", UUID.randomUUID().toString()).also { submitted(origin, it) }

    companion object {
        fun sameRequest(a: JSONObject, b: JSONObject): Boolean = equivalent(
            JSONObject(a.toString()).apply { remove("operation_id") },
            JSONObject(b.toString()).apply { remove("operation_id") })
        // Android's org.json does not provide JSON-java's JSONObject.similar.
        private fun equivalent(a: Any?, b: Any?): Boolean = when {
            a is JSONObject && b is JSONObject -> a.length() == b.length() &&
                a.keys().asSequence().all { b.has(it) && equivalent(a.opt(it), b.opt(it)) }
            a is JSONArray && b is JSONArray -> a.length() == b.length() &&
                (0 until a.length()).all { equivalent(a.opt(it), b.opt(it)) }
            else -> a == b
        }
    }
}

/** Immutable recovery anchor; its old ID is used only by explicit reconciliation. */
internal class TaskCompletedRecovery(val origin: String, request: String) {
    private val snapshot = JSONObject(request).toString()
    init { UUID.fromString(parameters().getString("operation_id")) }
    fun parameters() = JSONObject(snapshot)
    fun appliesTo(origin: String, request: JSONObject?) = this.origin == origin && request != null &&
        TaskSubmissionIdentity.sameRequest(parameters(), request)
    companion object {
        const val REFRESH_MESSAGE = "The Mac already accepted this task. Refresh workspaces before trying again."
        const val MISSING_MESSAGE = "The task is still missing. Refresh again or start it as a new task."
    }
}

/** Task creates require an identified workspace in the same response, as on iOS. */
internal data class TaskCreationResult(val created: NativeWorkspace, val workspaces: List<NativeWorkspace>) {
    fun merge(existing: List<NativeWorkspace>): List<NativeWorkspace> {
        val updates = workspaces.associateBy { it.id }
        val known = existing.mapTo(hashSetOf()) { it.id }
        return existing.map { updates[it.id] ?: it } + workspaces.filter { it.id !in known }
    }

    companion object {
        fun parse(response: JSONObject): TaskCreationResult {
            val id = response.opt("created_workspace_id") as? String
            val raw = response.optJSONArray("workspaces")
            require(!id.isNullOrBlank() && raw != null) { "Mac did not return the created task workspace" }
            val ids = hashSetOf<String>()
            for (index in 0 until raw.length()) {
                val itemId = raw.optJSONObject(index)?.opt("id") as? String
                require(!itemId.isNullOrBlank() && ids.add(itemId)) { "Mac returned an invalid task workspace list" }
            }
            val workspaces = parseWorkspaces(response)
            val created = workspaces.singleOrNull { it.id == id }
            require(created != null) { "Mac did not return the created task workspace" }
            return TaskCreationResult(created, workspaces)
        }
    }
}
