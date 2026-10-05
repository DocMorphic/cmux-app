package io.github.docmorphic.cmuxapp

import java.util.Locale

internal enum class ArtifactTrackKind { AUDIO, SUBTITLE, TIMED_TEXT }
internal data class ArtifactMediaTrack(val index: Int, val kind: ArtifactTrackKind, val language: String,
    val mime: String = "", val default: Boolean = false, val forced: Boolean = false, val autoselect: Boolean = true) {
    val key get() = "${kind.name}:$index:$language:$mime"
    val caption get() = kind != ArtifactTrackKind.AUDIO
}

internal object ArtifactMediaTracks {
    const val AUTO = ""
    const val OFF = "off"
    fun language(value: String?): String = value.orEmpty().trim().replace('_', '-').take(64)
        .takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) && !it.equals("und", ignoreCase = true) }.orEmpty()
    private fun languageCode(value: String): String = runCatching {
        Locale.forLanguageTag(value).isO3Language
    }.getOrDefault("")
    fun labels(tracks: List<ArtifactMediaTrack>, locale: Locale = Locale.getDefault()): Map<String, String> {
        val names = tracks.associate { track -> track.key to track.language.takeIf { it.isNotEmpty() }
            ?.let { Locale.forLanguageTag(it).getDisplayName(locale).takeIf(String::isNotBlank) }
            .orEmpty() }
        return tracks.mapIndexed { index, track ->
            val name = names.getValue(track.key)
            val base = name.ifEmpty { if (track.caption) "Subtitle ${index + 1}" else "Audio ${index + 1}" }
            val duplicate = name.isNotEmpty() && names.values.count { it == name } > 1
            track.key to (base + (if (duplicate) " · ${index + 1}" else "") + if (track.forced) " (Forced)" else "")
        }.toMap()
    }
    fun caption(tracks: List<ArtifactMediaTrack>, preference: String, enabled: Boolean, locale: Locale): ArtifactMediaTrack? {
        val captions = tracks.filter { it.caption }
        if (preference == OFF) return null
        if (preference != AUTO) captions.firstOrNull { it.key == preference }?.let { return it }
        val preferred = languageCode(locale.toLanguageTag())
        fun matches(track: ArtifactMediaTrack) = preferred.isNotEmpty() && languageCode(track.language) == preferred
        return captions.filter { if (enabled) it.autoselect || it.default || it.forced else it.forced && (matches(it) || it.language.isEmpty()) }
            .maxByOrNull { (if (matches(it)) 8 else 0) + (if (it.default) 4 else 0) + (if (!it.forced && enabled) 2 else 0) }
    }
}
