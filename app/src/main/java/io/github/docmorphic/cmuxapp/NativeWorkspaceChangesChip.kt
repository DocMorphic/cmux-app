/* Presentation derived from cmux WorkspaceChangesChipLabel at
 * 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc, GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun NativeWorkspaceChangesChip(chip: WorkspaceChangesChip, workspaceId: String, onOpen: () -> Unit) {
    Box(Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
        .testTag("workspace.changes:$workspaceId")
        .clickable(role = Role.Button, onClickLabel = "View changes", onClick = onOpen)
        .semantics(mergeDescendants = true) { contentDescription = chip.label }, contentAlignment = Alignment.Center) {
        Row(Modifier.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .12f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 3.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (chip.additions == 0L && chip.deletions == 0L) {
                Text(chip.fileText, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("+${chip.additions}", fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1, color = changesAdded)
                Text("−${chip.deletions}", fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1, color = changesRemoved)
            }
        }
    }
}
