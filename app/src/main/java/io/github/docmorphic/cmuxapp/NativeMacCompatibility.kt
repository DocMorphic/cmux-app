package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.io.IOException

/** Numeric grammar shared with upstream's Mac policy. Policy versions never accept metadata. */
internal data class MacNumericVersion private constructor(private val parts: List<Long>) : Comparable<MacNumericVersion> {
    override fun compareTo(other: MacNumericVersion): Int {
        for (index in 0..2) {
            val result = parts[index].compareTo(other.parts[index])
            if (result != 0) return result
        }
        return 0
    }
    override fun toString() = parts.joinToString(".")
    companion object {
        fun parse(value: String): MacNumericVersion? {
            val parts = value.split('.')
            if (parts.size !in 1..3 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
            val numbers = parts.map { it.toLongOrNull() ?: return null }
            return MacNumericVersion(numbers + List(3 - numbers.size) { 0L })
        }
    }
}

internal data class MacNightlyVersion(val base: MacNumericVersion, val build: ULong) : Comparable<MacNightlyVersion> {
    override fun compareTo(other: MacNightlyVersion) = base.compareTo(other.base).takeIf { it != 0 } ?: build.compareTo(other.build)
    override fun toString() = "$base-nightly.$build"
    companion object {
        fun counter(raw: String): ULong? = raw.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toULongOrNull()
        fun parse(raw: String): MacNightlyVersion? {
            val parts = raw.substringBefore('+').split("-nightly.")
            if (parts.size != 2) return null
            return MacNightlyVersion(MacNumericVersion.parse(parts[0]) ?: return null, counter(parts[1]) ?: return null)
        }
    }
}

internal data class MacVersionRequirement(val stable: MacNumericVersion, val nightly: MacNightlyVersion? = null)
internal data class MacCompatibilityViolation(val nightly: Boolean, val reported: String?, val required: String?) {
    val message: String get() = "Update cmux${if (nightly) " Nightly" else ""} on your Mac" +
        (required?.let { " to $it or later" } ?: " to a current version") +
        ". ${reported?.let { "The Mac reports $it." } ?: "The Mac did not report a supported version."} Then reconnect."
    // Remote policy download fields are never used as navigation authority.
    val downloadUrl: String get() = if (nightly) "https://github.com/manaflow-ai/cmux/releases/tag/nightly"
        else "https://github.com/manaflow-ai/cmux/releases/latest"
}
internal class MacUpdateRequired(val violation: MacCompatibilityViolation) : IOException(violation.message)

/** A deliberate Android protocol profile, not Android's marketing version or iOS build identity. */
internal object AndroidMacProtocolProfile {
    const val ID = "iroh-v2-ios-1.0.6-prod-v1"
    const val REFERENCE_VERSION = "1.0.6"
    const val BUILD_KIND = "prod"
}

internal data class NativeMacCompatibilityPolicy(val tiers: List<Tier>) {
    data class Tier(val min: MacNumericVersion, val max: MacNumericVersion?, val legacy: MacVersionRequirement,
                    val kinds: Map<String, MacVersionRequirement>)

    fun requirement(referenceVersion: String = AndroidMacProtocolProfile.REFERENCE_VERSION,
                    kind: String = AndroidMacProtocolProfile.BUILD_KIND): MacVersionRequirement? {
        val version = MacNumericVersion.parse(referenceVersion) ?: return null
        val winner = tiers.lastOrNull { it.min <= version } ?: return null
        if (winner.max != null && version > winner.max) return null
        return winner.kinds[kind] ?: winner.legacy
    }

    fun violation(tag: String?, version: String?, referenceVersion: String = AndroidMacProtocolProfile.REFERENCE_VERSION,
                  kind: String = AndroidMacProtocolProfile.BUILD_KIND): MacCompatibilityViolation? {
        val channel = tag?.trim()?.lowercase().orEmpty()
        if (channel !in setOf("", "default", "nightly")) return null
        val minimum = requirement(referenceVersion, kind) ?: return null
        val reported = version?.trim()?.takeIf { it.isNotEmpty() }
        val nightly = channel == "nightly"
        val required = if (nightly) minimum.nightly?.toString() else minimum.stable.toString()
        val outdated = if (nightly) {
            val installed = reported?.let(MacNightlyVersion::parse)
            installed == null || (minimum.nightly != null && installed < minimum.nightly)
        } else {
            val installed = reported?.substringBefore('+')?.let(MacNumericVersion::parse)
            installed == null || installed < minimum.stable
        }
        return if (outdated) MacCompatibilityViolation(nightly, reported?.take(128), required) else null
    }

    companion object {
        const val MAX_BYTES = 128 * 1024
        val baked = NativeMacCompatibilityPolicy(listOf(Tier(checkNotNull(MacNumericVersion.parse("1.0.6")), null,
            MacVersionRequirement(checkNotNull(MacNumericVersion.parse("0.64.25")),
                checkNotNull(MacNightlyVersion.parse("0.64.25-nightly.3522337919701"))), emptyMap())))

        /** All entries and build kinds must decode; malformed responses retain the last good policy. */
        fun decode(raw: String): NativeMacCompatibilityPolicy? = runCatching {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            MacPolicyJsonSyntax(raw).validate()
            val entries = JSONObject(raw).getJSONArray("entries")
            require(entries.length() <= 256)
            var previous: MacNumericVersion? = null
            val tiers = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val min = checkNotNull(MacNumericVersion.parse(entry.get("minIOSVersion") as String))
                val max = entry.optionalText("maxIOSVersion")?.let { checkNotNull(MacNumericVersion.parse(it)) }
                require(previous == null || min > checkNotNull(previous))
                require(max == null || max >= min)
                previous = min
                val kindsObject = if (entry.isNull("buildKinds")) null else entry.getJSONObject("buildKinds")
                val kinds = kindsObject?.let { values ->
                    require(values.length() <= 64)
                    values.keys().asSequence().associateWith { key ->
                        val value = values.getJSONObject(key)
                        MacVersionRequirement(checkNotNull(MacNumericVersion.parse(value.get("stableMinVersion") as String)), value.nightly())
                    }
                }.orEmpty()
                val prod = kinds["prod"]
                if (kindsObject != null) require(prod != null)
                // Upstream permits a syntactically invalid legacy string when valid prod is supplied.
                val legacyStable = entry.optionalText("stableMinVersion")?.let(MacNumericVersion::parse)
                if (kindsObject != null && legacyStable != null) require(legacyStable == prod?.stable)
                val stable = checkNotNull(legacyStable ?: prod?.stable)
                val nightly = entry.nightly()
                if (kindsObject != null && nightly != null) require(nightly == prod?.nightly)
                Tier(min, max, MacVersionRequirement(stable, nightly), kinds)
            }
            NativeMacCompatibilityPolicy(tiers)
        }.getOrNull()

        private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else get(key) as String
        private fun JSONObject.nightly(): MacNightlyVersion? {
            if (isNull("nightly")) return null
            val value = getJSONObject("nightly")
            return MacNightlyVersion(checkNotNull(MacNumericVersion.parse(value.get("minBaseVersion") as String)),
                checkNotNull(MacNightlyVersion.counter(value.get("minBuild") as String)))
        }
    }
}

/** Android's JSONTokener accepts comments, bare keys and trailing commas. The policy API does not. */
private class MacPolicyJsonSyntax(private val text: String) {
    private var index = 0
    private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
    private fun consume(c: Char): Boolean {
        whitespace()
        if (index >= text.length || text[index] != c) return false
        index++; return true
    }
    fun validate() { value(0); whitespace(); require(index == text.length) }
    private fun string(): String {
        whitespace(); val start = index
        require(consume('"'))
        while (index < text.length) {
            val c = text[index++]
            if (c == '"') return org.json.JSONTokener(text.substring(start, index)).nextValue() as String
            require(c.code >= 32)
            if (c == '\\') {
                require(index < text.length)
                val escaped = text[index++]
                require(escaped in "\"\\/bfnrtu")
                if (escaped == 'u') {
                    require(index + 4 <= text.length && text.substring(index, index + 4).all { it in "0123456789abcdefABCDEF" })
                    index += 4
                }
            }
        }
        error("Incomplete JSON string")
    }
    private fun value(depth: Int) {
        require(depth <= 32); whitespace(); require(index < text.length)
        when (text[index]) {
            '{' -> {
                index++; val names = hashSetOf<String>()
                if (consume('}')) return
                do { require(names.add(string())); require(consume(':')); value(depth + 1) } while (consume(','))
                require(consume('}'))
            }
            '[' -> {
                index++
                if (consume(']')) return
                do { value(depth + 1) } while (consume(','))
                require(consume(']'))
            }
            '"' -> string()
            't', 'f', 'n' -> {
                val literal = when (text[index]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                require(text.startsWith(literal, index)); index += literal.length
            }
            else -> {
                val number = NUMBER.find(text, index)
                require(number != null && number.range.first == index)
                index = number.range.last + 1
            }
        }
    }
    companion object { private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
}
