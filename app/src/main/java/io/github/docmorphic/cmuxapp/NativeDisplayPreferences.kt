package io.github.docmorphic.cmuxapp

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/** The setting controls cold screen-anchored hydration, not the host terminal's history. */
internal object TerminalScrollbackPreference {
    const val defaultRows = 4_000
    const val maximumRows = 20_000
    val choices = listOf(1_000, 4_000, 10_000, 20_000)
    fun clamp(rows: Int) = rows.coerceIn(0, maximumRows)
}

internal data class NativeDisplayPreferences(
    val wrapTitles: Boolean = false,
    val previewLines: Int = 2,
    val scrollbackRows: Int = TerminalScrollbackPreference.defaultRows,
    val showAltScreenNotice: Boolean = true,
    val useFullTerminalHeight: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
) {
    companion object {
        const val wrapKey = "wrap-workspace-titles"
        const val previewKey = "workspace-preview-lines"
        const val scrollbackKey = "terminal-scrollback-rows"
        const val altScreenNoticeKey = "show-alt-screen-notice"
        const val fullTerminalHeightKey = "use-full-terminal-height"
        const val hapticsKey = "haptic-feedback-enabled"
        fun read(preferences: SharedPreferences): NativeDisplayPreferences {
            val stored = preferences.all
            return NativeDisplayPreferences(
                stored[wrapKey] as? Boolean ?: false,
                (stored[previewKey] as? Int ?: 2).coerceIn(1, 2),
                TerminalScrollbackPreference.clamp(stored[scrollbackKey] as? Int ?: TerminalScrollbackPreference.defaultRows),
                stored[altScreenNoticeKey] as? Boolean ?: true,
                stored[fullTerminalHeightKey] as? Boolean ?: false,
                stored[hapticsKey] as? Boolean ?: true,
            )
        }
    }
}

@Composable
internal fun rememberNativeDisplayPreferences(preferences: SharedPreferences): NativeDisplayPreferences {
    var state by remember(preferences) { mutableStateOf(NativeDisplayPreferences.read(preferences)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            state = NativeDisplayPreferences.read(preferences)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        state = NativeDisplayPreferences.read(preferences)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@Composable
internal fun NativeDisplaySettings(preferences: SharedPreferences, state: NativeDisplayPreferences) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text("DISPLAY", style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wrap Workspace Titles", Modifier.weight(1f))
            Switch(state.wrapTitles, onCheckedChange = {
                preferences.edit().putBoolean(NativeDisplayPreferences.wrapKey, it).apply()
            }, modifier = Modifier.testTag("settings.wrap-titles").semantics { contentDescription = "Wrap Workspace Titles" })
        }
        DisplayChoice("Preview Lines", if (state.previewLines == 1) "1 Line" else "2 Lines", "settings.preview-lines",
            listOf(1 to "1 Line", 2 to "2 Lines")) {
            preferences.edit().putInt(NativeDisplayPreferences.previewKey, it).apply()
        }
        val numbers = NumberFormat.getIntegerInstance()
        DisplayChoice("Terminal Scrollback", "${numbers.format(state.scrollbackRows)} Rows", "settings.scrollback",
            TerminalScrollbackPreference.choices.map { it to "${numbers.format(it)} Rows" }) {
            preferences.edit().putInt(NativeDisplayPreferences.scrollbackKey, it).apply()
        }
        Text("History loaded when a Mac terminal opens or reconnects. More rows use more data and memory.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DisplayChoice(title: String, selected: String, tag: String, choices: List<Pair<Int, String>>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(tag).semantics {
                contentDescription = title; stateDescription = selected
            }) { Text(selected) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(value) })
                }
            }
        }
    }
}
