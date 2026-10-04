package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal fun moveWorkspaceComputer(ids: List<String>, id: String, destination: Int): List<String> {
    val old = ids.indexOf(id)
    if (old < 0 || destination !in 0..ids.size) return ids
    return ids.toMutableList().apply { removeAt(old); add((if (destination > old) destination - 1 else destination).coerceIn(0, size), id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeComputerOrderSheet(computers: List<NativeSortComputer>, onDismiss: () -> Unit, save: (List<String>) -> Unit) {
    var ids by remember { mutableStateOf(computers.map { it.id }) }
    val currentComputers by rememberUpdatedState(computers)
    val currentSave by rememberUpdatedState(save)
    val list = rememberLazyListState()
    var dragged by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableFloatStateOf(0f) }
    var destination by remember { mutableIntStateOf(-1) }
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val rows = ids.mapNotNull { id -> computers.firstOrNull { it.id == id } }
    LaunchedEffect(computers.map { it.id }.toSet()) {
        ids = ids.filter { id -> computers.any { it.id == id } } + computers.map { it.id }.filterNot { it in ids }
        dragged = null; destination = -1
    }
    fun locate() {
        val visible = list.layoutInfo.visibleItemsInfo
        destination = visible.firstOrNull { pointer < it.offset + it.size / 2f }?.index
            ?: visible.lastOrNull()?.let { it.index + 1 } ?: -1
    }
    fun move(id: String, to: Int) {
        if (currentComputers.none { it.id == id }) return
        val next = moveWorkspaceComputer(ids, id, to)
        if (next != ids) { ids = next; currentSave(next) }
    }
    LaunchedEffect(dragged) {
        if (dragged == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (dragged != null) {
            val now = withFrameNanos { it }; val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f); previous = now
            val info = list.layoutInfo
            val speed = when {
                pointer < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - pointer) / edge).coerceIn(0f, 1f)
                pointer > info.viewportEndOffset - edge -> ((pointer - info.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) { list.scrollBy(speed * edge * 8 * dt); locate() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Computer Order", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Text("Workspaces keep each computer’s own order. Drag computers to choose which come first.",
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("workspace.sort.computers")
                .pointerInput(Unit) { detectDragGesturesAfterLongPress(
                    onDragStart = { point -> dragged = list.layoutInfo.visibleItemsInfo.firstOrNull {
                        point.y >= it.offset && point.y < it.offset + it.size }?.key as? String
                        pointer = point.y; locate() },
                    onDrag = { event, delta -> if (dragged != null) { event.consume(); pointer += delta.y; locate() } },
                    onDragCancel = { dragged = null; destination = -1 },
                    onDragEnd = { dragged?.let { move(it, destination) }; dragged = null; destination = -1 })
                }, state = list, userScrollEnabled = dragged == null) {
                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    if (dragged != null && destination == index) HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).animateItem()
                        .background(if (dragged == row.id) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 20.dp).testTag("workspace.sort.computer:${row.id}").semantics {
                            customActions = listOfNotNull(
                                if (index > 0) CustomAccessibilityAction("Move up") { move(row.id, index - 1); true } else null,
                                if (index < rows.lastIndex) CustomAccessibilityAction("Move down") { move(row.id, index + 2); true } else null)
                        }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name)
                            row.buildLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        }
                        Text("≡", Modifier.semantics { contentDescription = "Drag to reorder ${row.name}" })
                    }
                    if (dragged != null && destination == rows.size && index == rows.lastIndex)
                        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
