package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart

@Composable
internal fun NativeTodoView(authoritative: TodoSnapshot, enabled: Boolean,
    mutate: suspend (TodoMutation) -> TodoSnapshot?) {
    val model = remember { NativeTodoModel(authoritative) }
    val currentMutation by rememberUpdatedState(mutate)
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(authoritative) { model.reconcile(authoritative) }
    val scope = rememberCoroutineScope()
    val haptics = rememberNativeHaptics()
    fun run(mutation: TodoMutation) {
        if (currentEnabled && !model.pending) {
            if (mutation is TodoMutation.Add || mutation is TodoMutation.SetState)
                haptics.perform(NativeHaptic.LIGHT)
            scope.launch(start = CoroutineStart.UNDISPATCHED) { model.perform(mutation) { currentMutation(it) } }
        }
    }
    val canEdit = enabled && !model.pending
    val snapshot = model.snapshot
    var draft by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun add() {
        if (canEdit && snapshot.items.size < TodoSnapshot.MAX_ITEMS && draft.isNotBlank()) {
            val text = draft; draft = ""; run(TodoMutation.Add(text))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (snapshot.items.isEmpty()) "" else "${snapshot.completed} of ${snapshot.items.size} done",
                Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TodoStatusPicker(snapshot, canEdit) { run(TodoMutation.SetStatus(it)) }
        }
        if (snapshot.items.isNotEmpty()) {
            val progress = snapshot.completed.toFloat() / snapshot.items.size
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .15f))
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(
                    if (progress == 1f) Color(0xFF739E80) else MaterialTheme.colorScheme.primary))
            }
        }
        if (snapshot.items.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("No items yet", style = MaterialTheme.typography.titleMedium)
            Text("Anything you add here stays in sync with your Mac.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else TodoItems(snapshot.items, canEdit, Modifier.weight(1f), ::run)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f), RoundedCornerShape(22.dp)).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextField(draft, { draft = it }, modifier = Modifier.weight(1f).testTag("todo-new-item"),
                placeholder = { Text("New checklist item") }, maxLines = 4, enabled = canEdit,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
            IconButton(onClick = ::add, enabled = canEdit && snapshot.items.size < TodoSnapshot.MAX_ITEMS && draft.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Add checklist item" }) { Text("↑", fontSize = 22.sp) }
        }
    }
    LaunchedEffect(enabled) { if (!enabled) focus.clearFocus(force = true) }
    LaunchedEffect(model.failure) {
        if (model.failure) haptics.perform(NativeHaptic.ERROR)
    }
    if (model.failure) AlertDialog(onDismissRequest = model::dismissFailure,
        title = { Text("Couldn’t Update Checklist") },
        text = { Text("The change could not be confirmed. Check the refreshed checklist before trying again.") },
        confirmButton = { TextButton(onClick = model::dismissFailure) { Text("OK") } })
}

private fun TodoStatus.tint() = when (this) {
    TodoStatus.TODO -> Color(0xFF9B9FA8)
    TodoStatus.WORKING -> Color(0xFF76B9FF)
    TodoStatus.ATTENTION -> Color(0xFFFF6B33)
    TodoStatus.REVIEW -> Color(0xFF4ACA6C)
    TodoStatus.DONE -> Color(0xFF739E80)
}

@Composable
private fun TodoStatusPicker(snapshot: TodoSnapshot, enabled: Boolean, choose: (TodoStatus?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val title = if (snapshot.statusHidden) "No Status" else snapshot.status.title
    val tint = if (snapshot.statusHidden) Color(0xFF9B9FA8) else snapshot.status.tint()
    LaunchedEffect(enabled) { if (!enabled) open = false }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled,
            modifier = Modifier.widthIn(min = 155.dp).semantics { contentDescription = "Choose status"; stateDescription = title },
            colors = ButtonDefaults.textButtonColors(contentColor = tint, containerColor = tint.copy(alpha = .15f))) {
            Text("$title  ⌃⌄", fontSize = 12.sp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Automatic") }, onClick = { open = false; choose(null) }, enabled = enabled)
            TodoStatus.entries.forEach { status -> DropdownMenuItem(text = { Text(status.title) },
                trailingIcon = { if (!snapshot.statusHidden && snapshot.status == status) Text("✓") },
                onClick = { open = false; choose(status) }, enabled = enabled) }
        }
    }
}

@Composable
private fun TodoItems(items: List<TodoItem>, enabled: Boolean, modifier: Modifier, mutate: (TodoMutation) -> Unit) {
    val list = rememberLazyListState()
    val current by rememberUpdatedState(items)
    val send by rememberUpdatedState(mutate)
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    var dragged by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableFloatStateOf(0f) }
    var destination by remember { mutableIntStateOf(-1) }
    fun locate() {
        val visible = list.layoutInfo.visibleItemsInfo
        destination = visible.firstOrNull { pointer < it.offset + it.size }?.index ?: visible.lastOrNull()?.index ?: -1
    }
    LaunchedEffect(enabled) { if (!enabled) { dragged = null; destination = -1 } }
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
    LazyColumn(modifier.fillMaxWidth().testTag("todo-list").pointerInput(enabled) {
        if (enabled) detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                dragged = list.layoutInfo.visibleItemsInfo.firstOrNull { point.y >= it.offset && point.y < it.offset + it.size }?.key as? String
                pointer = point.y; locate()
            }, onDrag = { event, delta -> if (dragged != null) { event.consume(); pointer += delta.y; locate() } },
            onDragCancel = { dragged = null; destination = -1 },
            onDragEnd = {
                dragged?.let { id -> if (destination >= 0 && current.indexOfFirst { it.id == id } != destination) send(TodoMutation.Move(id, destination)) }
                dragged = null; destination = -1
            })
    }, state = list, userScrollEnabled = dragged == null) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            if (dragged != null && destination == index) HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
            TodoRow(item, enabled && dragged == null, Modifier.background(if (dragged == item.id) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .semantics {
                    customActions = if (!enabled) emptyList() else listOfNotNull(
                        CustomAccessibilityAction("Delete") { mutate(TodoMutation.Remove(item.id)); true },
                        if (index > 0) CustomAccessibilityAction("Move up") { mutate(TodoMutation.Move(item.id, index - 1)); true } else null,
                        if (index < items.lastIndex) CustomAccessibilityAction("Move down") { mutate(TodoMutation.Move(item.id, index + 1)); true } else null)
                }, mutate)
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem, enabled: Boolean, modifier: Modifier, mutate: (TodoMutation) -> Unit) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentItem by rememberUpdatedState(item)
    val send by rememberUpdatedState(mutate)
    var editing by remember(item.id) { mutableStateOf(false) }
    var draft by remember(item.id) { mutableStateOf("") }
    var hadFocus by remember(item.id) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun commit() {
        if (!editing) return
        editing = false
        if (currentEnabled && draft.isNotBlank() && draft.trim() != currentItem.text) send(TodoMutation.Edit(currentItem.id, draft))
    }
    LaunchedEffect(editing) { if (editing) { hadFocus = false; focus.requestFocus() } }
    LaunchedEffect(enabled) { if (!enabled) editing = false }
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart && currentEnabled) send(TodoMutation.Remove(currentItem.id))
        false
    })
    SwipeToDismissBox(swipe, modifier = modifier.testTag("todo-row-${item.id}"), enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled && !editing, backgroundContent = {
            Box(Modifier.fillMaxSize().background(Color(0xFFB82D36)).padding(16.dp).clearAndSetSemantics { }, contentAlignment = Alignment.CenterEnd) { Text("Delete", color = Color.White) }
        }) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { mutate(TodoMutation.SetState(item.id, item.state.next)) }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Mark ${item.text} as ${item.state.next.title}"; stateDescription = item.state.title }) {
                val ink = when (item.state) {
                    TodoItemState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    TodoItemState.WORKING -> MaterialTheme.colorScheme.primary
                    TodoItemState.COMPLETED -> Color(0xFF739E80)
                }
                val check = MaterialTheme.colorScheme.background
                Canvas(Modifier.size(24.dp)) {
                    val radius = size.minDimension / 2 - 1.dp.toPx()
                    if (item.state == TodoItemState.COMPLETED) {
                        drawCircle(ink, radius)
                        drawLine(check, Offset(size.width * .25f, size.height * .5f), Offset(size.width * .43f, size.height * .68f), 2.dp.toPx())
                        drawLine(check, Offset(size.width * .43f, size.height * .68f), Offset(size.width * .75f, size.height * .32f), 2.dp.toPx())
                    } else {
                        drawCircle(ink, radius, style = Stroke(1.5.dp.toPx()))
                        if (item.state == TodoItemState.WORKING) drawArc(ink, 90f, 180f, true,
                            topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2))
                    }
                }
            }
            if (editing) TextField(draft, { draft = it }, Modifier.weight(1f).testTag("todo-edit-${item.id}")
                .focusRequester(focus).onFocusChanged { state -> if (state.isFocused) hadFocus = true else if (hadFocus) commit() },
                maxLines = 6, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }))
            else Text(item.text, Modifier.weight(1f).clickable(enabled) { draft = item.text; hadFocus = false; editing = true }.padding(vertical = 12.dp),
                textDecoration = if (item.state == TodoItemState.COMPLETED) TextDecoration.LineThrough else null,
                color = if (item.state == TodoItemState.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
    }
}
