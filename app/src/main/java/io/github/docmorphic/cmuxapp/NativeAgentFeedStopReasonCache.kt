package io.github.docmorphic.cmuxapp

/** Owned by one Feed presentation; only reasons in its current snapshot are retained. */
internal class NativeAgentFeedStopReasonCache(
    private val normalize: (String) -> String = ::normalizeAgentFeedStopReason
) {
    private val reasons = mutableMapOf<String, String>()

    fun retain(retained: Set<String>) { reasons.keys.retainAll(retained) }

    fun matches(lhs: String?, rhs: String?): Boolean {
        if (lhs == null || rhs == null) return false
        val first = reasons.getOrPut(lhs) { normalize(lhs) }
        val second = reasons.getOrPut(rhs) { normalize(rhs) }
        if (first.isEmpty() || second.isEmpty()) return false
        if (first == second) return true
        val (shorter, longer) = if (first.length <= second.length) first to second else second to first
        // A complete response that happens to be a prefix is a distinct stop.
        return shorter.endsWith("…") && longer.startsWith(shorter.dropLast(1))
    }
}

/** Unicode White_Space, matching Swift's whitespace splitting rather than ASCII regex \s. */
internal fun normalizeAgentFeedStopReason(value: String): String = buildString(value.length) {
    var space = false
    for (character in value) {
        val whitespace = character in '\t'..'\r' || character == ' ' || character == '\u0085' ||
            character == '\u00a0' || character == '\u1680' || character in '\u2000'..'\u200a' ||
            character == '\u2028' || character == '\u2029' || character == '\u202f' ||
            character == '\u205f' || character == '\u3000'
        if (whitespace) space = isNotEmpty()
        else {
            if (space) append(' ')
            append(character); space = false
        }
    }
}
