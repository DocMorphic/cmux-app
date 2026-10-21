package io.github.docmorphic.cmuxapp

/** ChiefConversationBackend / ChiefQuestionSheet at the scoped manifest revision. */
internal object NativeChiefPresentation {
    fun pendingPart(message: NativeChiefMessage): NativeChiefPart? = message.partPayloads.firstOrNull { part ->
        when (part.kind) {
            "approval" -> part.approval?.let { it.decided == null && it.options.any { option -> !option.startsWith("always:") } } == true
            "question" -> part.question?.let { it.state == NativeChiefQuestion.State.PENDING && it.items.isNotEmpty() } == true
            else -> false
        }
    }

    fun compactQuestion(question: NativeChiefQuestion) = question.items.singleOrNull()?.takeIf {
        question.state == NativeChiefQuestion.State.PENDING && !it.multiSelect && !it.allowsOther && it.options.isNotEmpty()
    }

    fun summary(question: NativeChiefQuestion) = question.items.firstOrNull()?.prompt.orEmpty()

    fun receipt(message: NativeChiefMessage): String? {
        message.partPayloads.forEach { part ->
            if (part.kind == "approval") part.approval?.let { approval ->
                approval.decided?.let { return "${approval.prompt}\n✓ $it" }
            }
            if (part.kind == "question") part.question?.let { question ->
                when (question.state) {
                    NativeChiefQuestion.State.CANCELLED -> return if (question.source.harness == NativeChiefQuestion.Harness.CHIEF)
                        "Answered in chat" else "Cancelled"
                    NativeChiefQuestion.State.ANSWERED -> return question.items.joinToString("\n\n") { item ->
                        val answer = question.answer?.selections?.get(item.id)
                        val chosen = item.options.filter { it.id in answer?.optionIds.orEmpty() }.map { it.label } + listOfNotNull(answer?.other)
                        "${item.prompt}\n✓ ${chosen.joinToString(", ")}"
                    }
                    NativeChiefQuestion.State.PENDING -> Unit
                }
            }
        }
        return null
    }

    fun displayText(message: NativeChiefMessage): String {
        val work = message.partPayloads.mapNotNull { it.work }
        if (work.isNotEmpty()) {
            var text = message.text
            work.forEach {
                val title = NativeChiefTextPolicy.trim(it.title)
                val status = NativeChiefTextPolicy.trim(it.status)
                val preview = NativeChiefTextPolicy.trim(it.preview.orEmpty())
                val detail = listOf(status, preview).filter(String::isNotEmpty).joinToString(" · ")
                val rendered = if (detail.isEmpty()) title else "$title · $detail"
                val legacy = listOf(preview, title).firstOrNull { candidate -> candidate.isNotEmpty() && text.contains(candidate) }
                text = when {
                    legacy != null -> text.replaceFirst(legacy, rendered)
                    text.isEmpty() -> rendered
                    else -> "$text · $rendered"
                }
            }
            return text
        }
        if (message.text.isNotEmpty()) return message.text
        return if (message.partPayloads.isEmpty()) message.parts.joinToString(" · ", transform = ::kindTitle)
        else message.partPayloads.joinToString(" · ") {
            it.approval?.prompt ?: it.question?.let(::summary) ?: kindTitle(it.kind)
        }
    }

    private fun kindTitle(kind: String) = kind.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
}

/** Immutable form state: duplicate option ids in separate items remain qualified. */
internal data class NativeChiefQuestionForm(
    val question: NativeChiefQuestion,
    val selections: Map<String, Set<String>> = question.items.associate { item ->
        item.id to if (item.multiSelect) emptySet() else item.options.firstOrNull()?.let { setOf(it.id) }.orEmpty()
    },
    val otherText: String = ""
) {
    val otherTarget get() = question.items.firstOrNull { it.allowsOther && selections[it.id].isNullOrEmpty() }
        ?: question.items.firstOrNull { it.allowsOther }
    val text get() = NativeChiefTextPolicy.trim(otherText).takeIf(String::isNotEmpty)
    val canSubmit get() = question.items.isNotEmpty() && question.problems(
        (question.items.map { it.id } + selections.keys).distinct().associateWith { id ->
            NativeChiefQuestion.Selection(selections[id].orEmpty().toList(), text.takeIf { id == otherTarget?.id })
        }).isEmpty()
    val choices get() = question.items.flatMap { item -> item.options.filter { it.id in selections[item.id].orEmpty() }
        .map { item.id + '\u001f' + it.id } }

    fun toggle(itemId: String, optionId: String): NativeChiefQuestionForm {
        val item = question.items.single { it.id == itemId }
        require(item.options.any { it.id == optionId })
        val previous = selections[itemId].orEmpty()
        val selected = if (item.multiSelect) if (optionId in previous) previous - optionId else previous + optionId else setOf(optionId)
        return copy(selections = selections + (itemId to selected),
            otherText = if (!item.multiSelect && itemId == otherTarget?.id) "" else otherText)
    }

    fun editOther(text: String): NativeChiefQuestionForm {
        val target = otherTarget
        return copy(otherText = text, selections = if (NativeChiefTextPolicy.hasContent(text) && target != null && !target.multiSelect)
            selections + (target.id to emptySet()) else selections)
    }
}
