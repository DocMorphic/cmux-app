package io.github.docmorphic.cmuxapp

/** Source backend errors keep their identity until the current UI resolves copy. */
internal enum class NativeChiefQuestionProblem(val sourceKey: String, val fallback: String) {
    NOT_WAITING("mobile.chief.error.questionNotWaiting", "That Chief question is no longer waiting"),
    ANSWER_REQUIRED("mobile.chief.error.answerRequired", "Choose an answer before submitting"),
    ANSWER_MISSING("mobile.chief.error.answeredQuestionMissing", "Chief did not return the answered question")
}

internal class NativeChiefQuestionException(val problem: NativeChiefQuestionProblem) : IllegalStateException(problem.fallback)

internal sealed interface NativeChiefQuestionError {
    data class Source(val problem: NativeChiefQuestionProblem) : NativeChiefQuestionError
    data class Message(val value: String) : NativeChiefQuestionError

    companion object {
        fun from(error: Exception): NativeChiefQuestionError = when (error) {
            is NativeChiefQuestionException -> Source(error.problem)
            else -> Message(error.message ?: "Chief did not confirm this question")
        }
    }
}
