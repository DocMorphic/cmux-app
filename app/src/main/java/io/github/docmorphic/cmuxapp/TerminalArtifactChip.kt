package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TerminalArtifactChip(count: Int, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val label = "$count ${if (count == 1) "file" else "files"}"
    Row(modifier.background(Color(0xF0272B31), RoundedCornerShape(24.dp)).border(1.dp, Color(0xFF424751), RoundedCornerShape(24.dp))
        .clickable(role = Role.Button, onClick = onOpen).semantics(mergeDescendants = true) {
            contentDescription = "Open files in view"; stateDescription = label
        }.heightIn(min = 44.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilesGlyph(ArtifactFilter.IMAGES, Modifier.size(19.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("⌃", color = filesMuted, fontSize = 15.sp)
    }
}
