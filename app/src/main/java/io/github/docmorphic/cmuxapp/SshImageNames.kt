package io.github.docmorphic.cmuxapp

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Matches the iOS SSH image basename; SFTP publication also avoids collisions. */
internal object SshImageNames {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT).withZone(ZoneOffset.UTC)
    fun fileName(format: String, date: Instant): String {
        val lowered = format.lowercase(Locale.ROOT)
        val extension = lowered.takeIf { it.length in 1..5 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } } ?: "png"
        return "${dateFormat.format(date)}.$extension"
    }
}
