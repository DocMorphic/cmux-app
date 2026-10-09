/* Question answer behavior follows cmux AgentFeedQuestionAnswerBuilder at
 * f4b1509054949eaad5d695569ad443c4c18ed68d. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

internal data class AgentFeedQuestionDrafts(
    val selected: Map<String, Set<String>> = emptyMap(),
    val custom: Map<String, String> = emptyMap(),
    val customSelected: Set<String> = custom.filterValues { it.isNotBlank() }.keys
) {
    fun choose(question: AgentFeedQuestion, option: String): AgentFeedQuestionDrafts {
        if (question.options.none { it.id == option }) return this
        val previous = selected[question.id].orEmpty()
        val next = when {
            !question.multiSelect -> setOf(option)
            option in previous -> previous - option
            else -> previous + option
        }
        return copy(selected = selected + (question.id to next), customSelected = customSelected - question.id)
    }
    fun selectCustom(question: AgentFeedQuestion) = copy(
        selected = selected - question.id, customSelected = customSelected + question.id)
    fun write(question: AgentFeedQuestion, text: String) =
        if (custom[question.id].orEmpty() == text) this
        else selectCustom(question).copy(custom = custom + (question.id to text))
    fun answered(question: AgentFeedQuestion): String? = if (question.id in customSelected)
        custom[question.id]?.trim()?.takeIf(String::isNotEmpty)
    else question.answer(selected[question.id].orEmpty(), "")
    fun answers(questions: List<AgentFeedQuestion>): List<String>? {
        if (questions.isEmpty()) return null
        return questions.map { answered(it) ?: return null }
    }
    fun encode() = JSONObject().put("selected", JSONObject().apply {
        selected.forEach { (key, value) -> put(key, JSONArray(value.toList())) }
    }).put("custom", JSONObject(custom)).put("customSelected", JSONArray(customSelected.toList())).toString()
    companion object {
        fun decode(raw: String): AgentFeedQuestionDrafts? = runCatching {
            val json = JSONObject(raw); val options = json.getJSONObject("selected"); val text = json.getJSONObject("custom")
            val custom = text.keys().asSequence().associateWith { text.getString(it) }
            val mode = json.optJSONArray("customSelected")
            AgentFeedQuestionDrafts(options.keys().asSequence().associateWith { key ->
                val values = options.getJSONArray(key)
                (0 until values.length()).map { values.getString(it) }.toSet()
            }, custom, if (mode == null) custom.filterValues { it.isNotBlank() }.keys
                else (0 until mode.length()).map { mode.getString(it) }.toSet())
        }.getOrNull()
    }
}
