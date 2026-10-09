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
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        ArtifactMediaTrackButtons(state)
    }
}

@Composable
internal fun ArtifactMediaTrackButtons(state: ArtifactMediaState, onMenuChanged: (Boolean) -> Unit = {}) {
    val audio = state.tracks.filter { it.kind == ArtifactTrackKind.AUDIO }
    val captions = state.tracks.filter { it.caption }
    val locale = LocalConfiguration.current.locales[0]
    var menu by remember { mutableStateOf<String?>(null) }
    fun selectMenu(value: String?) { menu = value; onMenuChanged(value != null) }
    DisposableEffect(Unit) { onDispose { onMenuChanged(false) } }
        if (audio.size > 1) Box {
            TextButton(enabled = state.prepared, onClick = { selectMenu("audio") },
                modifier = Modifier.semantics { contentDescription = "Audio tracks" }) { Text("Audio") }
            DropdownMenu(menu == "audio", { selectMenu(null) }) {
                DropdownMenuItem(text = { Text("Default") }, trailingIcon = { if (state.audioPreference == ArtifactMediaTracks.AUTO) Text("✓") },
                    onClick = { selectMenu(null); state.view?.selectAudio(ArtifactMediaTracks.AUTO) })
                val labels = ArtifactMediaTracks.labels(audio, locale)
                audio.forEach { track -> DropdownMenuItem(text = { Text(labels.getValue(track.key)) },
                    trailingIcon = { if (state.audioPreference != ArtifactMediaTracks.AUTO && state.selectedAudio == track.index) Text("✓") },
                    onClick = { selectMenu(null); state.view?.selectAudio(track.key) }) }
            }
        }
        if (captions.isNotEmpty()) Box {
            TextButton(enabled = state.prepared, onClick = { selectMenu("captions") },
                modifier = Modifier.semantics { contentDescription = "Subtitle tracks" }) { Text("Subtitles") }
            DropdownMenu(menu == "captions", { selectMenu(null) }) {
                listOf("Auto" to ArtifactMediaTracks.AUTO, "Off" to ArtifactMediaTracks.OFF).forEach { (label, value) ->
                    DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (state.captionPreference == value) Text("✓") },
                        onClick = { selectMenu(null); state.view?.selectCaption(value) })
                }
                val labels = ArtifactMediaTracks.labels(captions, locale)
                captions.forEach { track -> DropdownMenuItem(text = { Text(labels.getValue(track.key)) },
                    trailingIcon = { if (state.captionPreference == track.key && state.selectedCaption == track.index) Text("✓") },
                    onClick = { selectMenu(null); state.view?.selectCaption(track.key) }) }
            }
        }
}
