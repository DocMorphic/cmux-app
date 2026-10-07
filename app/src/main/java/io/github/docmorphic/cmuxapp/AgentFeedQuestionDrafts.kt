/* Question answer behavior follows cmux AgentFeedQuestionControls at
 * 186cec79781256867ad4516f0802118738bd2393. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

internal data class AgentFeedQuestionDrafts(
    val selected: Map<String, Set<String>> = emptyMap(),
    val custom: Map<String, String> = emptyMap()
) {
    fun choose(question: AgentFeedQuestion, option: String): AgentFeedQuestionDrafts {
        if (question.options.none { it.id == option }) return this
        val previous = selected[question.id].orEmpty()
        val next = when {
            !question.multiSelect -> setOf(option)
            option in previous -> previous - option
            else -> previous + option
        }
        return copy(selected = selected + (question.id to next), custom = custom - question.id)
    }
    fun write(question: AgentFeedQuestion, text: String) = copy(
        selected = selected - question.id, custom = custom + (question.id to text))
    fun answered(question: AgentFeedQuestion) = question.answer(selected[question.id].orEmpty(), custom[question.id].orEmpty())
    fun answers(questions: List<AgentFeedQuestion>): List<String>? {
        if (questions.isEmpty()) return null
        return questions.map { answered(it) ?: return null }
    }
    fun encode() = JSONObject().put("selected", JSONObject().apply {
        selected.forEach { (key, value) -> put(key, JSONArray(value.toList())) }
    }).put("custom", JSONObject(custom)).toString()
    companion object {
        fun decode(raw: String): AgentFeedQuestionDrafts? = runCatching {
            val json = JSONObject(raw); val options = json.getJSONObject("selected"); val text = json.getJSONObject("custom")
            AgentFeedQuestionDrafts(options.keys().asSequence().associateWith { key ->
                val values = options.getJSONArray(key)
                (0 until values.length()).map { values.getString(it) }.toSet()
            }, text.keys().asSequence().associateWith { text.getString(it) })
        }.getOrNull()
    }
}
