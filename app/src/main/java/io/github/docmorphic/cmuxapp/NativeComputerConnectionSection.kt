package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun NativeComputerConnectionSection(connection: NativeComputerConnection) {
    Column(Modifier.fillMaxWidth().padding(22.dp)) {
        Text("CONNECTION", color = NativeAppColors.muted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("This phone", Modifier.weight(1f))
            Text(connection.phrase, color = when (connection.availability) {
                NativeFeedAvailability.CONNECTED -> Color(0xFF72D49A)
                NativeFeedAvailability.CONNECTING -> Color(0xFFFFC46B)
                NativeFeedAvailability.OFFLINE -> NativeAppColors.muted
            })
        }
        if (connection.foreground) Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Role", Modifier.weight(1f))
            Text("Active (foreground)")
        }
        connection.transport?.takeIf { connection.availability == NativeFeedAvailability.CONNECTED }?.let {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Active Transport", Modifier.weight(1f))
                Text(it.label)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Workspaces", Modifier.weight(1f))
            Text(connection.workspaceCount?.toString() ?: "—")
        }
    }
}
