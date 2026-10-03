package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** ChatArtifactSyntaxHighlightPolicy at the pinned cmux iOS revision. */
internal object ArtifactSyntaxPolicy {
    const val MAX_HIGHLIGHT_BYTES = 1_500_000L
    const val MAX_AUTOMATIC_BYTES = 256_000L
    private val groups = mapOf(
        "bash" to "bash sh zsh", "c" to "c h", "cpp" to "cc cpp cxx hh hpp", "javascript" to "cjs js jsx mjs",
        "clojure" to "clj cljs", "csharp" to "cs", "css" to "css", "dart" to "dart", "elixir" to "ex exs",
        "fsharp" to "fs fsx", "go" to "go", "gradle" to "gradle", "groovy" to "groovy", "haskell" to "hs purs",
        "html" to "htm html", "java" to "java", "json" to "json", "kotlin" to "kt kts", "less" to "less",
        "lua" to "lua", "objectivec" to "m mm", "markdown" to "markdown md", "php" to "php", "perl" to "pl pm",
        "python" to "py", "r" to "r", "ruby" to "rb", "rust" to "rs", "scss" to "sass scss", "scala" to "scala",
        "sql" to "sql", "swift" to "swift", "typescript" to "ts tsx", "xml" to "xml", "yaml" to "yaml yml")
    private val languages = groups.flatMap { (language, extensions) -> extensions.split(' ').map { it to language } }.toMap()
    fun language(path: String): String? = languages[path.substringAfterLast('.', "").lowercase()]
    fun decision(path: String, bytes: Long): Decision {
        if (bytes !in 0..MAX_HIGHLIGHT_BYTES) return Decision(false)
        val language = language(path)
        return Decision(language != null || bytes < MAX_AUTOMATIC_BYTES, language)
    }
    data class Decision(val enabled: Boolean, val language: String? = null)
}

internal data class ArtifactSyntaxRun(val start: Int, val end: Int, val color: Int, val style: Int)
internal data class ArtifactSyntaxResult(val text: String, val language: String?, val runs: List<ArtifactSyntaxRun>) {
    companion object {
        /** Never apply attributes to a changed buffer or accept incomplete/overlapping ranges. */
        fun decode(raw: String, expected: String): ArtifactSyntaxResult? = runCatching {
            val value = JSONObject(raw)
            if (value.getString("text") != expected) return null
            val rows = value.getJSONArray("runs")
            if (rows.length() > expected.length) return null
            var cursor = 0
            val runs = ArrayList<ArtifactSyntaxRun>(rows.length())
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                if (row.length() != 4) return null
                val start = row.getInt(0); val end = row.getInt(1); val color = row.getInt(2); val style = row.getInt(3)
                if (start != cursor || end <= start || end > expected.length || style !in 0..3 || color ushr 24 != 255) return null
                runs.add(ArtifactSyntaxRun(start, end, color, style)); cursor = end
            }
            if (cursor != expected.length) return null
            ArtifactSyntaxResult(expected, value.optString("language").takeIf { it.isNotEmpty() && it != "null" }, runs)
        }.getOrNull()
    }
}
