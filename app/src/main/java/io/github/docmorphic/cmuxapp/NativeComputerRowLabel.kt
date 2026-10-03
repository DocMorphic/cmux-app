package io.github.docmorphic.cmuxapp

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
private fun presencePhrase(presence: NativeComputerPresence): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(presence.lastSeenAtMillis, presence.online) {
        if (presence.lastSeenAtMillis != null && presence.online != true) while (true) {
            now = System.currentTimeMillis(); delay(60_000)
        }
    }
    return when {
        presence.online == true -> "Online"
        presence.lastSeenAtMillis != null -> "Last seen ${DateUtils.getRelativeTimeSpanString(
            presence.lastSeenAtMillis.coerceAtMost(now), now, DateUtils.MINUTE_IN_MILLIS)}"
        presence.online == false -> "Offline"
        else -> "Presence unknown"
    }
}

@Composable
internal fun NativeComputerRowLabel(name: String, buildLabel: String?, connection: NativeComputerConnection,
    presence: NativeComputerPresence, reconnect: Boolean, modifier: Modifier = Modifier,
    routeDescription: String? = null, olderPairing: Boolean = false, identity: NativeMacIdentity? = null) {
    val heartbeat = presencePhrase(presence)
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            buildLabel?.let { label ->
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(label, Modifier.padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(if (reconnect) heartbeat else connection.phrase + (connection.workspaceCount?.let {
            " · $it ${if (it == 1) "workspace" else "workspaces"}"
        } ?: ""), color = Color(0xFF9B9FA8), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val route = routeDescription ?: "no route"
        val diagnostic = if (reconnect || (presence.online == null && connection.availability == NativeFeedAvailability.CONNECTED)) route
            else "Presence: ${if (presence.online == null) "unknown" else heartbeat} · $route"
        Text((if (olderPairing) "Older pairing · " else "") + diagnostic,
            color = Color(0xFF9B9FA8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        NativeMacUpdateGuidance(identity)
    }
}

@Composable
internal fun NativeComputerStatusDot(connection: NativeComputerConnection, presence: NativeComputerPresence, reconnect: Boolean) {
    val heartbeat = presencePhrase(presence)
    val connecting = connection.availability == NativeFeedAvailability.CONNECTING
    val description = if (connecting) connection.phrase else if (reconnect) heartbeat else connection.phrase
    val color = if (reconnect) {
        if (presence.online == true) Color(0xFF72D49A) else Color(0xFF9B9FA8)
    } else when (connection.availability) {
        NativeFeedAvailability.CONNECTED -> Color(0xFF72D49A)
        NativeFeedAvailability.CONNECTING -> Color(0xFFFFC46B)
        NativeFeedAvailability.OFFLINE -> Color(0xFF9B9FA8)
    }
    Box(Modifier.padding(horizontal = 8.dp).semantics { contentDescription = "Computer status: $description" }) {
        if (connecting && reconnect) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Color(0xFFFFC46B))
        else Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}


@Composable
internal fun NativeComputerMethodSections(rows: List<NativeComputerListRow>, row: @Composable (NativeComputerListRow) -> Unit) {
    NativeComputerList.sections(rows).forEach { (title, computers) ->
        Text(title, Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            color = Color(0xFF9B9FA8), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        computers.forEach { computer -> key(computer.mac.origin) { row(computer) } }
    }
}
