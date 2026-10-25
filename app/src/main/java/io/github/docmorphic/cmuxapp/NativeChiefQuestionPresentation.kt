package io.github.docmorphic.cmuxapp

/** A source UIHostingController captures one question and one dismiss target.
 * Retaining that identity also keeps an Android recreation from canceling its request. */
internal data class NativeChiefQuestionPresentation(val generation: Long, val messageID: String,
    val question: NativeChiefQuestion, val submitting: Boolean = false, val error: String? = null)
