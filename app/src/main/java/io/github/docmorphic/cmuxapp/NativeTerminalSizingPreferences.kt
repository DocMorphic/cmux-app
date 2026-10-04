package io.github.docmorphic.cmuxapp

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeTerminalSizingSettings(preferences: SharedPreferences, state: NativeDisplayPreferences) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        TerminalSizingToggle("Full-Screen Sizing Notice", state.showAltScreenNotice, "settings.alt-screen-notice") {
            preferences.edit().putBoolean(NativeDisplayPreferences.altScreenNoticeKey, it).apply()
        }
        TerminalSizingToggle("Use Full Terminal Height", state.useFullTerminalHeight, "settings.full-terminal-height",
            "Let apps like Vim extend beneath the keyboard and toolbars.") {
            preferences.edit().putBoolean(NativeDisplayPreferences.fullTerminalHeightKey, it).apply()
        }
    }
}

@Composable
private fun TerminalSizingToggle(title: String, enabled: Boolean, tag: String, description: String? = null,
    onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(enabled, onCheckedChange = onChange,
            modifier = Modifier.testTag(tag).semantics { contentDescription = title })
    }
}

/** The explanation belongs to the visible connection/surface, while suppression is an app preference. */
@Composable
internal fun NativeAltScreenNotice(owner: Any?, surface: String, visible: Boolean, onSuppress: () -> Unit) {
    if (!visible) return
    var expanded by remember(owner, surface) { mutableStateOf(false) }
    val warning = Color(0xFFFFA543)
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("terminal.alt-screen-notice")
            .semantics { contentDescription = "Explain full-screen terminal sizing" }) {
            Icon(painterResource(R.drawable.ic_terminal_fullscreen_notice), contentDescription = null, tint = warning)
        }
        // Material's anchored popup supplies back/outside dismissal and bounded vertical scrolling.
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.width(300.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_terminal_fullscreen_notice), contentDescription = null, tint = warning)
                    Text("Full-screen terminal app", color = warning, style = MaterialTheme.typography.titleSmall)
                }
                Text("Full-screen mode mirrors the Mac terminal's exact size, so it may not fill this screen, " +
                    "and scrolling won't be as smooth. Claude Code: `/tui default`. Codex: restart with `codex --no-alt-screen`.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { expanded = false; onSuppress() }, modifier = Modifier.testTag("terminal.alt-screen-notice.suppress")) {
                    Text("Don't Show Again")
                }
            }
        }
    }
}
