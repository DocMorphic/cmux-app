package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal enum class RoutedAgentFeedVerb { DECIDE, REPLY, READ, REFRESH }
internal data class RoutedAgentFeedCommand(val key: String?, val verb: RoutedAgentFeedVerb,
    val decision: AgentFeedDecision? = null, val text: String? = null, val needsInput: Boolean? = null)

/** Display/intent data only; the main process resolves opaque keys against its live Feed. */
internal object RoutedAgentFeedWire {
    const val MAX_BYTES = 64 * 1024 * 1024
    fun token(value: String) = value.also { require(it.length in 1..128 && it.none(Char::isISOControl)) }
    private fun optional(value: JSONObject, key: String) = if (value.isNull(key)) null else value.getString(key)
    private fun decision(value: AgentFeedDecision) = JSONObject().put("kind", value.kind).put("mode", value.mode)
        .put("selections", JSONArray(value.selections)).put("feedback", value.feedback)
    private fun decision(value: JSONObject) = AgentFeedDecision(value.getString("kind"), optional(value, "mode"),
        value.getJSONArray("selections").let { a -> (0 until a.length()).map(a::getString) }, optional(value, "feedback"))
    fun command(value: RoutedAgentFeedCommand) = JSONObject().put("key", value.key?.let(::token)).put("verb", value.verb.name)
        .put("decision", value.decision?.let(::decision)).put("text", value.text).put("needs", value.needsInput).toString()
    fun command(raw: String): RoutedAgentFeedCommand {
        val v = JSONObject(raw)
        return RoutedAgentFeedCommand(optional(v, "key")?.let(::token), RoutedAgentFeedVerb.valueOf(v.getString("verb")),
            v.optJSONObject("decision")?.let(::decision), optional(v, "text"), if (v.isNull("needs")) null else v.getBoolean("needs")).also {
            require((it.verb == RoutedAgentFeedVerb.REFRESH) == (it.key == null))
            require((it.verb == RoutedAgentFeedVerb.DECIDE) == (it.decision != null))
            require((it.verb == RoutedAgentFeedVerb.REPLY) == (it.text != null))
            require(it.needsInput == null || it.verb == RoutedAgentFeedVerb.READ)
        }
    }
    private fun item(v: NativeAgentFeedItem) = JSONObject().put("id", v.id).put("workstream_id", v.workstream)
        .put("source", v.source).put("kind", v.kind.wire).put("status", v.status.name.lowercase(Locale.ROOT))
        .put("created_at", v.createdAt).put("updated_at", v.updatedAt).put("title", v.title).put("cwd", v.cwd)
        .put("request_id", v.requestId).put("tool_name", v.toolName).put("tool_input", v.toolInput).put("tool_result", v.toolResult)
        .put("tool_result_is_error", v.toolResultIsError).put("plan", v.plan).put("plan_summary", v.planSummary)
        .put("default_mode", v.defaultMode).put("questions", JSONArray(v.questions.map { q ->
            JSONObject().put("id", q.id).put("header", q.header).put("prompt", q.prompt).put("multi_select", q.multiSelect)
                .put("options", JSONArray(q.options.map { o -> JSONObject().put("id", o.id).put("label", o.label).put("description", o.description) }))
        })).put("text", v.text).put("reason", v.reason).put("decision", v.decision?.let(::decision))
        .put("workspace_id", v.workspaceId).put("surface_id", v.surfaceId).put("workspace_title", v.workspaceTitle)
        .put("surface_title", v.surfaceTitle).put("context", JSONObject(v.context)).put("full_text_preview", v.fullTextPreview)
        .put("full_text_truncated", v.fullTextTruncated).put("reply_text", v.replyText)
    // These are already decoded display values; RPC normalization would truncate local replies.
    private fun item(v: JSONObject): NativeAgentFeedItem {
        val questions = v.getJSONArray("questions")
        val context = v.getJSONObject("context")
        return NativeAgentFeedItem(v.getString("id"), v.getString("workstream_id"), v.getString("source"),
            AgentFeedKind.entries.single { it.wire == v.getString("kind") },
            AgentFeedStatus.valueOf(v.getString("status").uppercase(Locale.ROOT)),
            v.getDouble("created_at").also { require(it.isFinite()) }, v.getDouble("updated_at").also { require(it.isFinite()) },
            optional(v, "title"), optional(v, "cwd"), optional(v, "request_id"), optional(v, "tool_name"),
            optional(v, "tool_input"), optional(v, "tool_result"), v.getBoolean("tool_result_is_error"),
            optional(v, "plan"), optional(v, "plan_summary"), optional(v, "default_mode"),
            (0 until questions.length()).map { i -> val q = questions.getJSONObject(i); val options = q.getJSONArray("options")
                AgentFeedQuestion(q.getString("id"), optional(q, "header"), q.getString("prompt"), q.getBoolean("multi_select"),
                    (0 until options.length()).map { j -> val o = options.getJSONObject(j)
                        AgentFeedOption(o.getString("id"), o.getString("label"), optional(o, "description")) }) },
            optional(v, "text"), optional(v, "reason"), v.optJSONObject("decision")?.let(::decision),
            optional(v, "workspace_id"), optional(v, "surface_id"), optional(v, "workspace_title"), optional(v, "surface_title"),
            context.keys().asSequence().associateWith(context::getString), optional(v, "full_text_preview"),
            v.getBoolean("full_text_truncated"), optional(v, "reply_text"))
    }
    private fun owners(v: Set<AgentFeedUiOwner>) = JSONArray(v.map { owner ->
        require(owner.instanceTag == null); token(owner.deviceId)
    })
    private fun owners(v: JSONArray): Set<AgentFeedUiOwner> {
        require(v.length() <= 256)
        return (0 until v.length()).map { AgentFeedUiOwner(token(v.getString(it)), null) }.toSet().also { require(it.size == v.length()) }
    }
    fun snapshot(v: AgentFeedUiSnapshot): String {
        require(v.entries.size <= 400 && v.allowedOwners.size <= 256 && v.allowedOwners.containsAll(v.loadedOwners))
        require(v.entries.all { it.owner in v.allowedOwners } && v.entries.map { it.key }.distinct().size == v.entries.size)
        return JSONObject().put("allowed", owners(v.allowedOwners)).put("loaded", owners(v.loadedOwners))
            .put("connected", v.connected).put("updating", v.updating).put("unsupported", v.unsupported)
            .put("sources", v.hasSources).put("failed", v.refreshFailed).put("entries", JSONArray(v.entries.map { row ->
                JSONObject().put("key", token(row.key)).put("owner", token(row.owner.deviceId)).put("item", item(row.item))
                    .put("computer", row.computerName).put("connected", row.connected).put("pending", row.pending).put("needs", row.needsInput)
                    .put("failure", row.failure?.let { f -> JSONObject().put("message", f.message).put("delivery", f.delivery.name).put("draft", f.draft) })
            })).toString()
    }
    fun snapshot(raw: String): AgentFeedUiSnapshot {
        val v = JSONObject(raw); val rows = v.getJSONArray("entries"); require(rows.length() <= 400)
        val allowed = owners(v.getJSONArray("allowed")); val loaded = owners(v.getJSONArray("loaded")); require(allowed.containsAll(loaded))
        val entries = (0 until rows.length()).map { index -> val row = rows.getJSONObject(index)
            val item = item(row.getJSONObject("item"))
            AgentFeedUiEntry(token(row.getString("key")), AgentFeedUiOwner(token(row.getString("owner")), null), item,
                row.getString("computer"), row.getBoolean("connected"), row.getBoolean("pending"), row.optJSONObject("failure")?.let { f ->
                    AgentFeedFailure(f.getString("message"), AgentFeedDelivery.valueOf(f.getString("delivery")), optional(f, "draft"))
                }, row.getBoolean("needs"))
        }
        require(entries.map { it.key }.distinct().size == entries.size && entries.all { it.owner in allowed })
        return AgentFeedUiSnapshot(entries, allowed, loaded, v.getBoolean("connected"), v.getBoolean("updating"),
            v.getBoolean("unsupported"), v.getBoolean("sources"), v.getBoolean("failed"))
    }
}
