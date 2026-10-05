package io.github.docmorphic.cmuxapp

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ArtifactMediaTracksTest {
    private val english = ArtifactMediaTrack(2, ArtifactTrackKind.TIMED_TEXT, "eng")
    private val japanese = ArtifactMediaTrack(3, ArtifactTrackKind.SUBTITLE, "jpn", default = true)
    private val forced = english.copy(index = 4, forced = true)

    @Test fun languageMetadataRejectsUnknownAndMalformedValues() {
        assertEquals("en-US", ArtifactMediaTracks.language(" en_US "))
        listOf(null, "und", "UND", "English", "en\nUS", "<en>", "").forEach {
            assertEquals("", ArtifactMediaTracks.language(it))
        }
    }
    @Test fun explicitChoiceOverridesSystemCaptionPreferences() {
        val tracks = listOf(english, japanese, forced)
        assertEquals(japanese, ArtifactMediaTracks.caption(tracks, japanese.key, false, Locale.US))
        assertNull(ArtifactMediaTracks.caption(tracks, ArtifactMediaTracks.OFF, true, Locale.US))
    }
    @Test fun autoUsesSystemLanguageIncludingThreeLetterCodes() {
        assertEquals(english, ArtifactMediaTracks.caption(listOf(english, japanese, forced), "", true, Locale.US))
        assertEquals(japanese, ArtifactMediaTracks.caption(listOf(english, japanese), "", true, Locale.JAPAN))
    }
    @Test fun disabledCaptionsOnlyAutoselectMatchingOrUnspecifiedForcedTracks() {
        assertNull(ArtifactMediaTracks.caption(listOf(english, japanese), "", false, Locale.US))
        assertEquals(forced, ArtifactMediaTracks.caption(listOf(english, forced), "", false, Locale.US))
        assertNull(ArtifactMediaTracks.caption(listOf(forced), "", false, Locale.JAPAN))
        val unspecified = forced.copy(language = "")
        assertEquals(unspecified, ArtifactMediaTracks.caption(listOf(unspecified), "", false, Locale.JAPAN))
    }
    @Test fun autoExcludesOptOutAndNonCaptionTracksAndHandlesMissingPreference() {
        val audio = english.copy(kind = ArtifactTrackKind.AUDIO)
        val optOut = english.copy(autoselect = false)
        assertNull(ArtifactMediaTracks.caption(listOf(audio, optOut), "", true, Locale.US))
        assertEquals(japanese, ArtifactMediaTracks.caption(listOf(japanese), "missing", true, Locale.US))
        assertNull(ArtifactMediaTracks.caption(emptyList(), "missing", true, Locale.US))
    }
    @Test fun duplicateLanguagesRemainDistinctAndForcedTracksAreLabeled() {
        val labels = ArtifactMediaTracks.labels(listOf(english, forced), Locale.US)
        assertNotEquals(labels[english.key], labels[forced.key])
        assertTrue(labels.getValue(forced.key).endsWith("(Forced)"))
        assertEquals("Subtitle 1", ArtifactMediaTracks.labels(listOf(english.copy(language = "")), Locale.US).values.single())
    }
}
