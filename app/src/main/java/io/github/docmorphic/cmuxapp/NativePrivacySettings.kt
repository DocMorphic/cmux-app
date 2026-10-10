package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun NativePrivacySettings(consent: NativePrivacyConsent) {
    val enabled by consent.enabled.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text("PRIVACY", style = MaterialTheme.typography.labelSmall, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("settings.telemetry")
            .toggleable(enabled, role = Role.Switch, onValueChange = consent::setEnabled)
            .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Text("Share Anonymous Analytics", Modifier.weight(1f).padding(end = 12.dp))
            Switch(enabled, onCheckedChange = null)
        }
        Text("When off, cmux does not send Android product analytics.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("This version does not upload product analytics.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
