package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Chief's shared AgentQuestion wire shape uses camelCase, unlike legacy feed questions. */
internal data class NativeChiefQuestion(
    val id: String, val source: Source, val items: List<Item>, val state: State,
    val answer: Answer?, val submitLabel: String?
) {
    enum class Harness { CLAUDE, CODEX, ACP, CHIEF }
    enum class State { PENDING, ANSWERED, CANCELLED }
    data class Source(val harness: Harness, val session: String, val permission: String?, val toolCall: String?,
        val agentName: String?, val answerOption: String?, val rejectOption: String?)
    data class Item(val id: String, val header: String?, val prompt: String, val options: List<Option>,
        val multiSelect: Boolean, val allowsOther: Boolean)
    data class Option(val id: String, val label: String, val detail: String?, val preview: Preview?)
    data class Preview(val text: String, val format: Format) { enum class Format { MARKDOWN, MONOSPACE } }
    data class Selection(val optionIds: List<String> = emptyList(), val other: String? = null)
    data class Respondent(val participant: String?, val displayName: String?, val device: String?, val isRemote: Boolean)
    data class Answer(val selections: Map<String, Selection>, val respondent: Respondent?, val answeredAtMs: Long?)
    data class Problem(val kind: Kind, val item: String? = null, val option: String? = null) {
        enum class Kind { UNANSWERED, TOO_MANY_CHOICES, UNKNOWN_OPTION, OTHER_NOT_ALLOWED, UNKNOWN_ITEM, NOT_PENDING }
    }

    /** The Mac remains the authority that commits an answer and its respondent. */
    fun problems(selections: Map<String, Selection>): List<Problem> {
        if (state != State.PENDING) return listOf(Problem(Problem.Kind.NOT_PENDING))
        return buildList {
            selections.keys.filter { key -> items.none { it.id == key } }.sorted().forEach {
                add(Problem(Problem.Kind.UNKNOWN_ITEM, it))
            }
            items.forEach { item ->
                val selection = selections[item.id] ?: Selection()
                if (selection.optionIds.isEmpty() && selection.other == null) {
                    add(Problem(Problem.Kind.UNANSWERED, item.id)); return@forEach
                }
                selection.optionIds.filter { id -> item.options.none { it.id == id } }.forEach {
                    add(Problem(Problem.Kind.UNKNOWN_OPTION, item.id, it))
                }
                if (selection.other != null && !item.allowsOther) add(Problem(Problem.Kind.OTHER_NOT_ALLOWED, item.id))
                if (!item.multiSelect && selection.optionIds.size + (if (selection.other == null) 0 else 1) > 1)
                    add(Problem(Problem.Kind.TOO_MANY_CHOICES, item.id))
            }
        }
    }

    companion object {
        fun decode(row: JSONObject): NativeChiefQuestion = with(NativeChiefJson) {
            val source = objectValue(row, "source")
            val harness = when (string(source, "harness")) {
                "claude" -> Harness.CLAUDE; "codex" -> Harness.CODEX; "acp" -> Harness.ACP; "chief" -> Harness.CHIEF
                else -> error("Invalid question harness")
            }
            val state = objectValue(row, "state")
            val kind = when (string(state, "kind")) {
                "answered" -> State.ANSWERED; "cancelled" -> State.CANCELLED; else -> State.PENDING
            }
            NativeChiefQuestion(string(row, "id"), Source(harness, string(source, "session"), optionalString(source, "permission"),
                optionalString(source, "toolCall"), optionalString(source, "agentName"), optionalString(source, "answerOption"),
                optionalString(source, "rejectOption")), objects(row, "items").map(::item), kind,
                if (kind == State.ANSWERED) answer(objectValue(state, "answer")) else null, optionalString(row, "submitLabel"))
        }
        private fun item(row: JSONObject) = with(NativeChiefJson) {
            Item(string(row, "id"), optionalString(row, "header"), string(row, "prompt"), objects(row, "options", true).map { option ->
                Option(string(option, "id"), string(option, "label"), optionalString(option, "detail"), optionalObject(option, "preview")?.let {
                    Preview(string(it, "text"), when (string(it, "format")) {
                        "markdown" -> Preview.Format.MARKDOWN; "monospace" -> Preview.Format.MONOSPACE
                        else -> error("Invalid question preview format")
                    })
                })
            }, boolean(row, "multiSelect", false), boolean(row, "allowsOther", true))
        }
        private fun answer(row: JSONObject) = with(NativeChiefJson) {
            val selections = objectValue(row, "selections")
            Answer(selections.keys().asSequence().associateWith { key ->
                val value = objectValue(selections, key)
                Selection(strings(value, "optionIDs"), optionalString(value, "other"))
            }, optionalObject(row, "respondent")?.let {
                Respondent(optionalString(it, "participant"), optionalString(it, "displayName"), optionalString(it, "device"), boolean(it, "isRemote"))
            }, optionalLong(row, "answeredAtMs"))
        }
    }
}
