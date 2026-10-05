package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtifactMediaTrackMenus(state: ArtifactMediaState) {
    val audio = state.tracks.filter { it.kind == ArtifactTrackKind.AUDIO }
    val captions = state.tracks.filter { it.caption }
    val locale = LocalConfiguration.current.locales[0]
    var menu by remember { mutableStateOf<String?>(null) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        if (audio.size > 1) Box {
            TextButton(enabled = state.prepared, onClick = { menu = "audio" },
                modifier = Modifier.semantics { contentDescription = "Audio tracks" }) { Text("Audio") }
            DropdownMenu(menu == "audio", { menu = null }) {
                DropdownMenuItem(text = { Text("Default") }, trailingIcon = { if (state.audioPreference == ArtifactMediaTracks.AUTO) Text("✓") },
                    onClick = { menu = null; state.view?.selectAudio(ArtifactMediaTracks.AUTO) })
                val labels = ArtifactMediaTracks.labels(audio, locale)
                audio.forEach { track -> DropdownMenuItem(text = { Text(labels.getValue(track.key)) },
                    trailingIcon = { if (state.audioPreference != ArtifactMediaTracks.AUTO && state.selectedAudio == track.index) Text("✓") },
                    onClick = { menu = null; state.view?.selectAudio(track.key) }) }
            }
        }
        if (captions.isNotEmpty()) Box {
            TextButton(enabled = state.prepared, onClick = { menu = "captions" },
                modifier = Modifier.semantics { contentDescription = "Subtitle tracks" }) { Text("Subtitles") }
            DropdownMenu(menu == "captions", { menu = null }) {
                listOf("Auto" to ArtifactMediaTracks.AUTO, "Off" to ArtifactMediaTracks.OFF).forEach { (label, value) ->
                    DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (state.captionPreference == value) Text("✓") },
                        onClick = { menu = null; state.view?.selectCaption(value) })
                }
                val labels = ArtifactMediaTracks.labels(captions, locale)
                captions.forEach { track -> DropdownMenuItem(text = { Text(labels.getValue(track.key)) },
                    trailingIcon = { if (state.captionPreference == track.key && state.selectedCaption == track.index) Text("✓") },
                    onClick = { menu = null; state.view?.selectCaption(track.key) }) }
            }
        }
    }
}
