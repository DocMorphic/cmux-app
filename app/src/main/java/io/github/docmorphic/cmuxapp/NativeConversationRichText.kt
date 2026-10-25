package io.github.docmorphic.cmuxapp

/** Semantic formatting from CmuxConversationCore. Offsets are UTF-16, as in String/TextRange. */
internal enum class NativeConversationTextStyle(val mask: Long, val wireName: String) {
    BOLD(1, "bold"), ITALIC(2, "italic"), UNDERLINE(4, "underline"), STRIKETHROUGH(8, "strikethrough");

    companion object {
        fun fromWire(names: List<String>) = entries.fold(0L) { value, style ->
            if (style.wireName in names) value or style.mask else value
        }
        fun wireNames(mask: Long) = entries.filter { mask and it.mask == it.mask }.map { it.wireName }
    }
}

internal enum class NativeConversationTextEffect {
    BIG, SMALL, SHAKE, NOD, EXPLODE, RIPPLE, BLOOM, JITTER;
    val wireName get() = name.lowercase(java.util.Locale.ROOT)
}

internal data class NativeConversationTextRun(val location: Int, val length: Int, val style: Long = 0,
    val effect: NativeConversationTextEffect? = null) {
    val isPlain get() = style == 0L && effect == null
}

/** Ports the original run operations; plain replacement text intentionally has no formatting.
 * This is separate from an editor's typing attributes and from any backend wire contract. */
internal object NativeConversationRichText {
    private data class UnitStyle(val style: Long = 0, val effect: NativeConversationTextEffect? = null)
    private fun units(runs: List<NativeConversationTextRun>, count: Int): Array<UnitStyle> {
        val result = Array(count.coerceAtLeast(0)) { UnitStyle() }
        for (run in runs) {
            val end = (run.location.toLong() + run.length.coerceAtLeast(0)).coerceAtMost(result.size.toLong()).toInt()
            for (i in run.location.coerceAtLeast(0) until end) result[i] = UnitStyle(run.style, run.effect)
        }
        return result
    }
    private fun compress(units: Array<UnitStyle>): List<NativeConversationTextRun> {
        if (units.isEmpty()) return emptyList()
        val result = mutableListOf<NativeConversationTextRun>()
        var start = 0
        for (i in 1..units.size) {
            if (i < units.size && units[i] == units[start]) continue
            val run = NativeConversationTextRun(start, i - start, units[start].style, units[start].effect)
            if (!run.isPlain) result += run
            start = i
        }
        return result
    }
    fun normalized(runs: List<NativeConversationTextRun>, utf16Count: Int): List<NativeConversationTextRun> =
        if (utf16Count <= 0 || runs.isEmpty()) emptyList() else compress(units(runs, utf16Count))

    fun carried(runs: List<NativeConversationTextRun>, old: String, new: String): List<NativeConversationTextRun> {
        if (runs.isEmpty() || new.isEmpty()) return emptyList()
        var prefix = 0
        while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < new.length - prefix &&
            old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
        val delta = new.length - old.length
        val carried = mutableListOf<NativeConversationTextRun>()
        for (run in normalized(runs, old.length)) {
            val headEnd = minOf(run.location + run.length, prefix)
            if (headEnd > run.location) carried += run.copy(length = headEnd - run.location)
            val tailStart = maxOf(run.location, old.length - suffix)
            val tailEnd = run.location + run.length
            if (tailEnd > tailStart) carried += run.copy(location = tailStart + delta, length = tailEnd - tailStart)
        }
        return normalized(carried, new.length)
    }

    data class Trimmed(val text: String, val runs: List<NativeConversationTextRun>)
    fun trimmed(text: String, runs: List<NativeConversationTextRun>): Trimmed {
        val trimmed = NativeChiefTextPolicy.trim(text)
        if (runs.isEmpty() || trimmed.isEmpty()) return Trimmed(trimmed, emptyList())
        val offset = text.indexOf(trimmed)
        return Trimmed(trimmed, normalized(runs.map { it.copy(location = it.location - offset) }, trimmed.length))
    }

    fun styleAt(index: Int, runs: List<NativeConversationTextRun>, count: Int): Long =
        if (index !in 0 until count) 0 else runs.lastOrNull {
            index.toLong() >= it.location && index.toLong() < it.location.toLong() + it.length.coerceAtLeast(0)
        }?.style ?: 0
    fun effectAt(index: Int, runs: List<NativeConversationTextRun>, count: Int): NativeConversationTextEffect? =
        if (index !in 0 until count) null else runs.lastOrNull {
            index.toLong() >= it.location && index.toLong() < it.location.toLong() + it.length.coerceAtLeast(0)
        }?.effect

    fun hasAll(style: Long, start: Int, length: Int, runs: List<NativeConversationTextRun>, count: Int) =
        length > 0 && (start until start + length).all { styleAt(it, runs, count) and style == style }
    fun commonEffect(start: Int, length: Int, runs: List<NativeConversationTextRun>, count: Int): NativeConversationTextEffect? {
        if (length <= 0) return null
        val first = effectAt(start, runs, count) ?: return null
        return first.takeIf { (start until start + length).all { effectAt(it, runs, count) == first } }
    }
    data class StyleToggle(val runs: List<NativeConversationTextRun>, val enabled: Boolean)
    fun toggleStyle(style: Long, start: Int, length: Int, runs: List<NativeConversationTextRun>, count: Int): StyleToggle {
        val lower = start.coerceAtLeast(0)
        val upper = (start.toLong() + length.coerceAtLeast(0)).coerceAtMost(count.toLong()).toInt()
        if (lower >= upper) return StyleToggle(normalized(runs, count), false)
        val units = units(runs, count)
        val enabled = (lower until upper).any { units[it].style and style != style }
        for (i in lower until upper) units[i] = units[i].copy(style = if (enabled) units[i].style or style else units[i].style and style.inv())
        return StyleToggle(compress(units), enabled)
    }
    data class EffectToggle(val runs: List<NativeConversationTextRun>, val effect: NativeConversationTextEffect?)
    fun toggleEffect(effect: NativeConversationTextEffect, start: Int, length: Int,
        runs: List<NativeConversationTextRun>, count: Int): EffectToggle {
        val lower = start.coerceAtLeast(0)
        val upper = (start.toLong() + length.coerceAtLeast(0)).coerceAtMost(count.toLong()).toInt()
        if (lower >= upper) return EffectToggle(normalized(runs, count), null)
        val units = units(runs, count)
        val value = if ((lower until upper).all { units[it].effect == effect }) null else effect
        for (i in lower until upper) units[i] = units[i].copy(effect = value)
        return EffectToggle(compress(units), value)
    }
}
