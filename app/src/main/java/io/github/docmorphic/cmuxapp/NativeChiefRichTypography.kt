package io.github.docmorphic.cmuxapp

/** ComposerTextView keeps its original/emoji base separately from applyScaledFonts' display
 * reset. The original global-category trace returns to that retained base on later typing. */
internal data class NativeChiefRichTypography(val bodyPoints: Double = 17.0, val basePoints: Double = 17.0,
    val textPoints: Double = 17.0, val typingPoints: Double = 17.0, val emojiSize: Int? = null) {
    fun initialize(body: Double, text: String, emojiFont: (String) -> Int?): NativeChiefRichTypography {
        require(body.isFinite() && body > 0)
        val emoji = emojiFont(text); val base = emoji?.toDouble() ?: body
        return NativeChiefRichTypography(body, base, base, base, emoji)
    }
    fun metrics(body: Double): NativeChiefRichTypography {
        require(body.isFinite() && body > 0)
        return copy(bodyPoints = body, textPoints = body, typingPoints = body)
    }
    fun changed(text: String, marked: Boolean, restyle: Boolean, rebaseTyping: Boolean,
        emojiFont: (String) -> Int?): NativeChiefRichTypography {
        if (marked) return this
        var next = copy(textPoints = if (restyle) basePoints else textPoints,
            typingPoints = if (rebaseTyping) basePoints else typingPoints)
        val emoji = emojiFont(text)
        if (emoji != emojiSize) {
            val base = emoji?.toDouble() ?: bodyPoints
            next = next.copy(basePoints = base, textPoints = base, typingPoints = base, emojiSize = emoji)
        }
        return next
    }
}
