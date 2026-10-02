package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Android presentation of the official TerminalSizeSheet actions. Host snapshots remain authoritative. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalSizeSheet(presentation: TerminalSizingPresentation, enabled: Boolean,
    onDismiss: () -> Unit, change: suspend (TerminalSizingAction) -> Unit) {
    val scope = rememberCoroutineScope()
    val currentChange by rememberUpdatedState(change)
    val currentEnabled by rememberUpdatedState(enabled)
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmOthers by remember { mutableStateOf<List<String>?>(null) }
    var modeMenu by remember { mutableStateOf(false) }
    val policy = presentation.state.policy
    val initial = policy.fixed ?: presentation.state.grid
    var columns by remember(policy.mode, policy.fixed) { mutableStateOf(initial.columns.toString()) }
    var rows by remember(policy.mode, policy.fixed) { mutableStateOf(initial.rows.toString()) }
    fun run(action: TerminalSizingAction) {
        if (busy || !currentEnabled) return
        busy = true; failure = null
        scope.launch {
            try { currentChange(action) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = if (action is TerminalSizingAction.Disconnect && action.ids.size > 1)
                    "Could not finish disconnecting everyone. Some devices may already be disconnected. Review the list before retrying."
                    else error.message ?: "Could not change terminal size. Check the connection and try again."
            } finally { busy = false }
        }
    }
    val editable = enabled && !busy
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp).testTag("terminal-size-sheet")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics { heading() }) {
                    Text("${presentation.state.grid.columns} × ${presentation.state.grid.rows}", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("terminal-size-grid"))
                    Text("Size set by ${presentation.ownerLabel}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Box {
                TextButton(onClick = { modeMenu = true }, enabled = editable,
                    modifier = Modifier.testTag("terminal-size-mode")) { Text("Size: ${policy.mode.title} ▾") }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    TerminalSizeMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.title) },
                        enabled = editable, onClick = { modeMenu = false; run(TerminalSizingAction.Policy(presentation.select(mode))) }) }
                }
            }
            if (policy.mode == TerminalSizeMode.FIXED) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(columns, { columns = it }, label = { Text("Columns") }, enabled = editable,
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("terminal-size-columns"))
                    Text("×")
                    OutlinedTextField(rows, { rows = it }, label = { Text("Rows") }, enabled = editable,
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("terminal-size-rows"))
                    val next = presentation.fixed(columns, rows)
                    TextButton(enabled = editable && next != null && next != policy, onClick = {
                        next?.let { columns = it.fixed!!.columns.toString(); rows = it.fixed.rows.toString(); run(TerminalSizingAction.Policy(it)) }
                    }) { Text("Apply") }
                }
                Text("20–300 columns · 5–120 rows", style = MaterialTheme.typography.bodySmall)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = "Updating terminal size" })
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
            Text("Participants", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
            TerminalSizingParticipants(presentation, editable, Modifier.weight(1f), ::run)
            if (presentation.others.isNotEmpty()) TextButton(enabled = editable, onClick = {
                confirmOthers = presentation.others.map { it.id }
            }, modifier = Modifier.fillMaxWidth().testTag("terminal-size-disconnect-others")) {
                Text("Disconnect Others", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    confirmOthers?.let { ids -> AlertDialog(onDismissRequest = { confirmOthers = null },
        title = { Text("Disconnect other devices?") },
        text = { Text("Disconnect ${ids.size} other ${if (ids.size == 1) "device" else "devices"} from this terminal. They can reattach later.") },
        dismissButton = { TextButton(onClick = { confirmOthers = null }) { Text("Cancel") } },
        confirmButton = { TextButton(enabled = editable, modifier = Modifier.testTag("terminal-size-confirm-disconnect"), onClick = {
            confirmOthers = null; run(TerminalSizingAction.Disconnect(ids))
        }) { Text("Disconnect Others") } }) }
}

@Composable
private fun TerminalSizingParticipants(presentation: TerminalSizingPresentation, enabled: Boolean, modifier: Modifier,
    change: (TerminalSizingAction) -> Unit) {
    val list = rememberLazyListState()
    val current by rememberUpdatedState(presentation)
    val send by rememberUpdatedState(change)
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val priority = presentation.state.policy.mode == TerminalSizeMode.PRIORITY
    var dragged by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableFloatStateOf(0f) }
    var destination by remember { mutableIntStateOf(-1) }
    fun locate() {
        val ids = current.rows.map { it.id }.toSet()
        val visible = list.layoutInfo.visibleItemsInfo.filter { it.key in ids }
        destination = visible.firstOrNull { pointer < it.offset + it.size / 2f }?.index
            ?: visible.lastOrNull()?.let { it.index + 1 } ?: -1
    }
    LaunchedEffect(enabled, priority, presentation.rows.map { it.id }) { dragged = null; destination = -1 }
    LaunchedEffect(dragged) {
        if (dragged == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (dragged != null) {
            val now = withFrameNanos { it }
            val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f); previous = now
            val info = list.layoutInfo
            val speed = when {
                pointer < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - pointer) / edge).coerceIn(0f, 1f)
                pointer > info.viewportEndOffset - edge -> ((pointer - info.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) { list.scrollBy(speed * edge * 8 * dt); locate() }
        }
    }
    fun move(id: String, destination: Int) {
        if (!enabled || !priority || destination < 0) return
        val next = current.move(id, destination)
        if (next != current.state.policy) send(TerminalSizingAction.Policy(next))
    }
    LazyColumn(modifier.testTag("terminal-size-participants").pointerInput(enabled, priority, presentation.rows.map { it.id }) {
        if (enabled && priority) detectDragGesturesAfterLongPress(
            onDragStart = { point -> dragged = list.layoutInfo.visibleItemsInfo.firstOrNull {
                point.y >= it.offset && point.y < it.offset + it.size }?.key as? String
                pointer = point.y; locate() },
            onDrag = { event, delta -> if (dragged != null) { event.consume(); pointer += delta.y; locate() } },
            onDragCancel = { dragged = null; destination = -1 },
            onDragEnd = { dragged?.let { move(it, destination) }; dragged = null; destination = -1 })
    }, state = list, userScrollEnabled = dragged == null) {
        itemsIndexed(presentation.rows, key = { _, row -> row.id }) { index, row ->
            val self = row.id == presentation.selfId
            var menu by remember(row.id) { mutableStateOf(false) }
            if (dragged != null && destination == index) HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.fillMaxWidth().testTag("terminal-size-participant-${row.id}")
                .background(if (dragged == row.id) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .semantics {
                    customActions = if (!enabled || !priority) emptyList() else listOfNotNull(
                        if (index > 0) CustomAccessibilityAction("Move up") { move(row.id, index - 1); true } else null,
                        if (index < presentation.rows.lastIndex) CustomAccessibilityAction("Move down") { move(row.id, index + 2); true } else null)
                }.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(presentation.title(row), style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(row.viewport?.let { "${it.columns} × ${it.rows}" },
                            if (row.id in presentation.state.owners) "Sets size" else if (!row.counts) "Not counted" else null)
                            .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    if (priority) Text("≡", Modifier.padding(8.dp).clearAndSetSemantics { })
                    if (!self || priority) Box {
                        TextButton(onClick = { menu = true }, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = "Options for ${presentation.title(row)}" }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (priority && index > 0) DropdownMenuItem(text = { Text("Move up") }, enabled = enabled,
                                onClick = { menu = false; move(row.id, index - 1) })
                            if (priority && index < presentation.rows.lastIndex) DropdownMenuItem(text = { Text("Move down") }, enabled = enabled,
                                onClick = { menu = false; move(row.id, index + 2) })
                            if (!self) DropdownMenuItem(text = { Text("Disconnect") }, enabled = enabled,
                                onClick = { menu = false; change(TerminalSizingAction.Disconnect(listOf(row.id))) })
                        }
                    }
                }
                if (self) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Counts toward size", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(row.counts, enabled = enabled, onCheckedChange = { change(TerminalSizingAction.Counts(it)) },
                            modifier = Modifier.testTag("terminal-size-counts").semantics { contentDescription = "This phone counts toward size" })
                    }
                    if (row.countsOverride != null) TextButton(enabled = enabled,
                        onClick = { change(TerminalSizingAction.Counts(null)) }) { Text("Use automatic rule") }
                }
            }
            HorizontalDivider()
        }
        item { if (dragged != null && destination == presentation.rows.size) HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary) }
    }
}
