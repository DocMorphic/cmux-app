/* Feed presentation follows cmux AgentFeedRowModel at 186cec79781256867ad4516f0802118738bd2393.
 * GPL-3.0-or-later; see NOTICE.md. */
package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class NativeAgentFeedPresentation(
    val author: String, val headline: String?, val workspace: String?, val tab: String?,
    val quote: String?, val output: String?, val tool: String?, val resolution: String?,
    val replyReference: String?, val visible: Boolean
) {
    fun location(showsTab: Boolean) = listOfNotNull(workspace, tab.takeIf { showsTab }).joinToString(" › ").takeIf(String::isNotEmpty)
    companion object {
        fun from(item: NativeAgentFeedItem): NativeAgentFeedPresentation {
            val author = when (item.source.lowercase(Locale.ROOT)) {
                "claude" -> "Claude"; "codex" -> "Codex"; "opencode" -> "OpenCode"
                else -> item.source.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            val headline = when (item.kind) {
                AgentFeedKind.PERMISSION -> "asked to use ${item.toolName ?: "a tool"}"
                AgentFeedKind.PLAN -> "proposed a plan"
                AgentFeedKind.QUESTION -> "asked a question"
                AgentFeedKind.STOP -> null
                AgentFeedKind.USER_PROMPT -> "prompted $author"
                AgentFeedKind.MESSAGE -> "said"
                AgentFeedKind.TOOL_USE -> "ran ${item.toolName ?: "a tool"}"
                AgentFeedKind.TOOL_RESULT -> if (item.toolResultIsError) "${item.toolName ?: "a tool"} failed" else "finished ${item.toolName ?: "a tool"}"
                AgentFeedKind.TODOS -> "updated the plan checklist"
                AgentFeedKind.UNSUPPORTED -> "sent an update"
            }
            val pendingQuestion = item.kind == AgentFeedKind.QUESTION && item.status == AgentFeedStatus.PENDING && item.questions.isNotEmpty()
            val output = when {
                pendingQuestion -> null
                item.kind != AgentFeedKind.TOOL_RESULT && normalized(item.fullTextPreview) != null -> normalized(item.fullTextPreview)
                else -> when (item.kind) {
                    AgentFeedKind.PERMISSION -> normalized(item.context["assistant_preamble"])
                    AgentFeedKind.PLAN -> planText(item.plan) ?: normalized(item.planSummary)
                    AgentFeedKind.QUESTION -> item.questions.firstOrNull()?.let { question ->
                        listOfNotNull(normalized(question.header), normalized(question.prompt)).joinToString("\n").takeIf(String::isNotEmpty)
                    } ?: normalized(item.context["assistant_preamble"])
                    AgentFeedKind.STOP -> normalized(item.reason) ?: normalized(item.context["assistant_preamble"])
                    AgentFeedKind.USER_PROMPT, AgentFeedKind.MESSAGE -> normalized(item.text)
                    AgentFeedKind.TOOL_USE -> normalized(item.context["tool_summary"])
                    else -> null
                }
            }
            val quote = if (!pendingQuestion && item.kind in setOf(AgentFeedKind.PERMISSION, AgentFeedKind.PLAN, AgentFeedKind.QUESTION, AgentFeedKind.STOP))
                normalized(item.context["last_user_message"]) else null
            val tool = when (item.kind) {
                AgentFeedKind.PERMISSION, AgentFeedKind.TOOL_USE -> humanizedToolText(item.toolInput)
                AgentFeedKind.TOOL_RESULT -> humanizedToolText(item.toolResult)
                else -> null
            }
            val resolution = resolution(item)
            val visible = item.kind == AgentFeedKind.TODOS || pendingQuestion || quote != null || output != null || tool != null ||
                resolution != null || item.needsInput && (item.kind != AgentFeedKind.QUESTION || item.questions.isNotEmpty()) || item.supportsTerminalReply
            val reference = if (item.replyText == null) null else (output ?: headline ?: "finished a turn").lineSequence()
                .map(String::trim).firstOrNull(String::isNotEmpty)?.let { truncated(it, 90) }
            return NativeAgentFeedPresentation(if (item.kind == AgentFeedKind.USER_PROMPT) "You" else author, headline,
                normalized(item.workspaceTitle) ?: normalized(item.cwd)?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotEmpty),
                normalized(item.surfaceTitle), quote, output, tool, resolution, reference, visible)
        }
        private fun resolution(item: NativeAgentFeedItem): String? = when (item.status) {
            AgentFeedStatus.EXPIRED -> "Expired unanswered"
            AgentFeedStatus.PENDING, AgentFeedStatus.TELEMETRY -> null
            AgentFeedStatus.RESOLVED -> when (item.decision?.kind) {
                "permission" -> when (item.decision.mode) {
                    "once" -> "Allowed once"; "always" -> "Always allowed"; "all" -> "Allowed all"
                    "bypass" -> "Permissions bypassed"; "deny" -> "Denied"; else -> "Resolved"
                }
                "exit_plan" -> if (item.decision.mode == "deny") "Denied" else normalized(item.decision.feedback)?.let { "Revision requested: $it" } ?: "Plan approved"
                "question" -> item.decision.selections.map { selection ->
                    item.questions.flatMap { it.options }.lastOrNull { it.id == selection }?.label ?: selection
                }.joinToString(", ").let { if (it.isEmpty()) "Answered" else "Answered: $it" }
                else -> "Resolved"
            }
        }
        private fun normalized(value: String?) = value?.trim()?.takeIf(String::isNotEmpty)
        private fun looksLikeJson(value: String) = value.startsWith('{') || value.startsWith('[') || value.startsWith("\"{") || value.startsWith("\"[")
        private fun parse(value: String): Any? = runCatching {
            val parser = JSONTokener(value); val parsed = parser.nextValue()
            check(parser.nextClean() == '\u0000'); parsed
        }.getOrNull()
        fun planText(raw: String?): String? {
            var candidate = normalized(raw)
            repeat(3) {
                val current = candidate ?: return null
                if (!looksLikeJson(current)) return current
                when (val parsed = parse(current)) {
                    is JSONObject -> return listOf("plan", "planText", "text").firstOrNull { parsed.opt(it) is String }
                        ?.let { normalized(parsed.getString(it)) }
                    is String -> candidate = normalized(parsed)
                    else -> return null
                }
            }
            return null
        }
        fun humanizedToolText(raw: String?): String? {
            var value: Any = normalized(raw) ?: return null
            repeat(2) { if (value is String) parse(value as String)?.let { value = it } }
            when (val parsed = value) {
                is String -> return if (looksLikeJson(parsed)) null else compact(parsed)
                is JSONObject -> {
                    listOf("command", "file_path", "path", "prompt", "text", "message", "url", "pattern").forEach { key ->
                        compact(parsed.opt(key) as? String)?.let { return it }
                    }
                    return compact(parsed.keys().asSequence().sorted().mapNotNull { key -> when (val field = parsed.opt(key)) {
                        is String -> normalized(field)?.let { "$key: $it" }
                        is Number -> "$key: $field"
                        is Boolean -> "$key: ${if (field) 1 else 0}"
                        else -> null
                    } }.take(3).joinToString(" · "))
                }
                else -> return null
            }
        }
        private fun compact(value: String?) = normalized(value)?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)
            ?.joinToString(" ")?.takeIf(String::isNotEmpty)?.let { truncated(it, 200) }
        private fun truncated(value: String, count: Int): String = taskGraphemes(value).let {
            if (it.size <= count) value else it.take(count).joinToString("") + "…"
        }
    }
}

internal fun agentFeedTimeLabel(createdAt: Double, now: Double, locale: Locale = Locale.getDefault()): String {
    if (!createdAt.isFinite() || createdAt <= 1 || !now.isFinite()) return ""
    val seconds = (now - createdAt).coerceAtLeast(0.0)
    return when {
        seconds < 60 -> "now"
        seconds < 3600 -> "${(seconds / 60).toInt()}m"
        seconds < 86400 -> "${(seconds / 3600).toInt()}h"
        seconds < 604800 -> "${(seconds / 86400).toInt()}d"
        else -> runCatching { DateTimeFormatter.ofPattern("MMM d", locale).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli((createdAt * 1000).toLong())) }.getOrDefault("")
    }
}
