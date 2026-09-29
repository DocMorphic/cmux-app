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
        Text("CONNECTION", color = Color(0xFF9B9FA8), fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("This phone", Modifier.weight(1f))
            Text(connection.phrase, color = when (connection.availability) {
                NativeFeedAvailability.CONNECTED -> Color(0xFF72D49A)
                NativeFeedAvailability.CONNECTING -> Color(0xFFFFC46B)
                NativeFeedAvailability.OFFLINE -> Color(0xFF9B9FA8)
            })
        }
        if (connection.foreground) Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Role", Modifier.weight(1f))
            Text("Active (foreground)")
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Workspaces", Modifier.weight(1f))
            Text(connection.workspaceCount?.toString() ?: "—")
        }
    }
}
