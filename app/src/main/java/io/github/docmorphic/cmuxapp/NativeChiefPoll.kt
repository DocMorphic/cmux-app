package io.github.docmorphic.cmuxapp

/** Read-only pending Chief projection; retained only by its in-memory transcript owner. */
internal data class NativeChiefPollParticipant(val id: String, val name: String)
internal data class NativeChiefPollContents(val question: String, val options: List<String>,
    val participants: List<NativeChiefPollParticipant>)
internal fun nativeChiefPollContents(message: NativeChiefMessage, messages: List<NativeChiefMessage>,
    humanID: String, you: String, answerQuestions: String): NativeChiefPollContents? {
    val part = NativeChiefPresentation.pendingPart(message) ?: return null
    // ChiefConversationBackend.info selects one human identity, preserves
    // first-seen author order, and supplies only missing fallback identities.
    // Another human author keeps their name rather than also becoming "You".
    val authors = messages.distinctBy { it.authorId }
    val participants = authors.map { author -> NativeChiefPollParticipant(author.authorId,
        if (author.authorId == humanID) you else author.author.ifEmpty { author.authorId }) }.toMutableList()
    if (authors.none { it.authorId == humanID }) participants += NativeChiefPollParticipant(humanID, you)
    val chiefID = messages.firstOrNull { it.authorKind == "chief" }?.authorId ?: "agent_chief"
    if (authors.none { it.authorId == chiefID } && chiefID != humanID)
        participants += NativeChiefPollParticipant(chiefID, "Chief")
    part.approval?.let { return NativeChiefPollContents(it.prompt,
        it.options.filterNot { choice -> choice.startsWith("always:") }, participants) }
    val question = part.question ?: return null
    val compact = NativeChiefPresentation.compactQuestion(question)
    return NativeChiefPollContents(compact?.prompt ?: NativeChiefPresentation.summary(question),
        compact?.options?.map { it.label } ?: listOf(answerQuestions), participants)
}


internal data class NativeChiefPollPresentation(val generation: Long, val messageID: String,
    val humanID: String, val contents: NativeChiefPollContents)

internal fun NativeChiefPollPresentation.refreshed(state: NativeChiefTranscriptState,
    you: String, answerQuestions: String): NativeChiefPollPresentation {
    val human = state.messages.firstOrNull { it.authorKind == "human" }?.authorId ?: humanID
    val next = state.messages.firstOrNull { it.id == messageID }?.let {
        nativeChiefPollContents(it, state.messages, human, you, answerQuestions)
    } ?: return this // Source reload preserves all sections after a receipt/removal.
    return copy(humanID = human, contents = next)
}
