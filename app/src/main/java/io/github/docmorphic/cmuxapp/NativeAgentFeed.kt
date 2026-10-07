package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal const val AGENT_FEED_CAPABILITY = "feed.v1"
internal enum class AgentFeedKind(val wire: String) {
    PERMISSION("permissionRequest"), PLAN("exitPlan"), QUESTION("question"), TOOL_USE("toolUse"),
    TOOL_RESULT("toolResult"), USER_PROMPT("userPrompt"), MESSAGE("assistantMessage"), STOP("stop"), TODOS("todos"), UNSUPPORTED("")
}
internal enum class AgentFeedStatus { PENDING, RESOLVED, EXPIRED, TELEMETRY }
internal data class AgentFeedOption(val id: String, val label: String, val description: String?)
internal data class AgentFeedQuestion(val id: String, val header: String?, val prompt: String,
    val multiSelect: Boolean, val options: List<AgentFeedOption>) {
    /** Claude's answers map expects labels, in presentation order, not option identifiers. */
    fun answer(selected: Set<String>, custom: String): String? = custom.trim().takeIf(String::isNotEmpty)
        ?: options.filter { it.id in selected }.let { if (multiSelect) it else it.take(1) }
            .joinToString(", ") { it.label }.takeIf(String::isNotEmpty)
}
internal data class AgentFeedDecision(val kind: String, val mode: String? = null,
    val selections: List<String> = emptyList(), val feedback: String? = null)
internal data class NativeAgentFeedItem(
    val id: String, val workstream: String, val source: String, val kind: AgentFeedKind,
    val status: AgentFeedStatus, val createdAt: Double, val updatedAt: Double,
    val title: String? = null, val cwd: String? = null, val requestId: String? = null,
    val toolName: String? = null, val toolInput: String? = null, val toolResult: String? = null,
    val toolResultIsError: Boolean = false, val plan: String? = null, val planSummary: String? = null,
    val defaultMode: String? = null, val questions: List<AgentFeedQuestion> = emptyList(),
    val text: String? = null, val reason: String? = null, val decision: AgentFeedDecision? = null,
    val workspaceId: String? = null, val surfaceId: String? = null,
    val workspaceTitle: String? = null, val surfaceTitle: String? = null,
    val context: Map<String, String> = emptyMap(), val fullTextPreview: String? = null,
    val fullTextTruncated: Boolean = false, val replyText: String? = null
) {
    val needsInput get() = status == AgentFeedStatus.PENDING && requestId != null &&
        kind in setOf(AgentFeedKind.PERMISSION, AgentFeedKind.PLAN, AgentFeedKind.QUESTION)
    val supportsTerminalReply get() = kind == AgentFeedKind.STOP && workspaceId != null && surfaceId != null
    val notable get() = kind !in setOf(AgentFeedKind.TOOL_USE, AgentFeedKind.USER_PROMPT) &&
        (kind != AgentFeedKind.TOOL_RESULT || toolResultIsError)
    fun searchFields(computer: String): List<String?> = listOf(source, computer, title, workspaceTitle,
        surfaceTitle, text, reason, fullTextPreview, plan, planSummary, toolName, toolInput, toolResult,
        context["last_user_message"], context["assistant_preamble"], replyText) + questions.flatMap { listOf(it.prompt, it.header) }
}
internal data class NativeAgentFeedSnapshot(val revision: Long, val items: List<NativeAgentFeedItem>)

/** RPC decoding is row-tolerant, but never invents identity or timestamps for a malformed row. */
internal object NativeAgentFeedWire {
    fun decode(value: JSONObject): NativeAgentFeedSnapshot {
        val revision = value.opt("revision") as? Number ?: error("Feed revision missing")
        require(revision.toDouble().isFinite() && revision.toDouble() >= 0 && revision.toDouble() == revision.toLong().toDouble())
        val rows = value.optJSONArray("items") ?: error("Feed items missing")
        return NativeAgentFeedSnapshot(revision.toLong(), (0 until minOf(rows.length(), 400)).mapNotNull { i ->
            runCatching { item(rows.getJSONObject(i)) }.getOrNull()
        }.distinctBy { it.id }.sortedWith(compareByDescending<NativeAgentFeedItem> { it.createdAt }.thenBy { it.id }))
    }
    private fun item(row: JSONObject): NativeAgentFeedItem? {
        val id = identifier(row, "id"); val workstream = identifier(row, "workstream_id")
        val source = required(row, "source", 512)
        if (source.trim().equals("notification", ignoreCase = true)) return null
        val kind = row.opt("kind") as? String ?: error("Feed kind missing")
        val status = row.opt("status") as? String ?: error("Feed status missing")
        val questions = array(row, "questions").map { raw ->
            val question = raw as? JSONObject ?: error("Invalid question")
            AgentFeedQuestion(identifier(question, "id"), optional(question, "header", 2048),
                required(question, "prompt", 2048), boolean(question, "multi_select"),
                array(question, "options").map {
                    val option = it as? JSONObject ?: error("Invalid option")
                    AgentFeedOption(identifier(option, "id"), required(option, "label", 512), optional(option, "description", 2048))
                })
        }
        val decision = objectOrNull(row, "decision")?.let { value ->
            AgentFeedDecision(required(value, "kind", 512), optional(value, "mode", 512),
                array(value, "selections").map { bound(it as? String ?: error("Invalid selection"), 8192) },
                optional(value, "feedback", 8192))
        }
        val context = objectOrNull(row, "context")?.let { value ->
            listOf("last_user_message", "assistant_preamble", "plan_summary", "tool_summary", "permission_mode")
                .mapNotNull { key -> optional(value, key, if (key == "permission_mode") 512 else 2048)?.let { key to it } }.toMap()
        }.orEmpty()
        val preview = row.opt("full_text_preview")
        return NativeAgentFeedItem(id, workstream, source, AgentFeedKind.entries.firstOrNull { it.wire == kind } ?: AgentFeedKind.UNSUPPORTED,
            when (status) { "pending" -> AgentFeedStatus.PENDING; "resolved" -> AgentFeedStatus.RESOLVED;
                "expired" -> AgentFeedStatus.EXPIRED; else -> AgentFeedStatus.TELEMETRY },
            date(row, "created_at"), date(row, "updated_at"), optional(row, "title", 512), optional(row, "cwd", 512),
            optional(row, "request_id", 512), optional(row, "tool_name", 512), optional(row, "tool_input", 8192),
            optional(row, "tool_result", 8192), boolean(row, "tool_result_is_error"), optional(row, "plan", 8192),
            optional(row, "plan_summary", 2048), optional(row, "default_mode", 512), questions, optional(row, "text", 8192),
            optional(row, "reason", 2048), decision, optional(row, "workspace_id", 512), optional(row, "surface_id", 512),
            optional(row, "workspace_title", 512), optional(row, "surface_title", 512), context,
            optional(row, "full_text_preview", 8192), boolean(row, "full_text_truncated") ||
                (preview is String && preview.toByteArray(Charsets.UTF_8).size > 8192), optional(row, "reply_text", 8192))
    }
    private fun date(row: JSONObject, key: String): Double = when (val raw = row.get(key)) {
        is Number -> raw.toDouble()
        is String -> Instant.parse(raw).let { it.epochSecond + it.nano / 1e9 }
        else -> error("Invalid feed date")
    }.also { require(it.isFinite()) }
    private fun identifier(row: JSONObject, key: String): String = (row.opt(key) as? String)?.trim()?.takeIf {
        it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 512
    } ?: error("Invalid feed identity")
    private fun required(row: JSONObject, key: String, limit: Int) = bound(row.opt(key) as? String ?: error("Missing feed field"), limit)
    private fun optional(row: JSONObject, key: String, limit: Int): String? =
        if (!row.has(key) || row.isNull(key)) null else required(row, key, limit).trim().takeIf(String::isNotEmpty)
    private fun boolean(row: JSONObject, key: String): Boolean =
        if (!row.has(key) || row.isNull(key)) false else row.opt(key) as? Boolean ?: error("Invalid feed flag")
    private fun objectOrNull(row: JSONObject, key: String): JSONObject? =
        if (!row.has(key) || row.isNull(key)) null else row.opt(key) as? JSONObject ?: error("Invalid feed object")
    private fun array(row: JSONObject, key: String): List<Any> = if (!row.has(key) || row.isNull(key)) emptyList() else
        (row.opt(key) as? JSONArray ?: error("Invalid feed array")).let { list -> (0 until list.length()).map(list::get) }
    fun bound(value: String, limit: Int): String = buildString {
        var index = 0; var bytes = 0
        while (index < value.length) {
            val raw = value.codePointAt(index)
            val cp = if (raw in 0xd800..0xdfff) 0xfffd else raw
            val size = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
            if (bytes + size > limit) break
            appendCodePoint(cp); bytes += size; index += Character.charCount(raw)
        }
    }
}
