package io.github.docmorphic.cmuxapp

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal class TerminalToolbarStore(private val preferences: SharedPreferences) {
    var layout by mutableStateOf(TerminalToolbarLayout.defaults()); private set
    var error by mutableStateOf<String?>(null); private set
    init { reload() }
    fun reload() {
        try { layout = TerminalToolbarLayout.decode(preferences.getString(TerminalToolbarLayout.PREFERENCE, null)); error = null }
        catch (_: Exception) { error = "Could not load saved shortcuts. Retry or reset the layout." }
    }
    fun save(next: TerminalToolbarLayout) {
        preferences.edit().putString(TerminalToolbarLayout.PREFERENCE, next.encode()).apply()
        layout = next; error = null
    }
}

@Composable
internal fun rememberTerminalToolbar(preferences: SharedPreferences): TerminalToolbarStore {
    val store = remember(preferences) { TerminalToolbarStore(preferences) }
    DisposableEffect(store, preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == TerminalToolbarLayout.PREFERENCE) store.reload()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return store
}

@Composable
internal fun TerminalToolbarView(layout: TerminalToolbarLayout, modifiers: TerminalInputModifiers,
    canInput: Boolean, filesEnabled: Boolean, onModifier: (TerminalInputModifiers.Key) -> Unit,
    onButton: (TerminalToolbarButton) -> Unit, onCustom: (TerminalToolbarAction) -> Unit,
    onCustomize: () -> Unit, insert: (() -> Unit)? = null, inputOwner: Any?) {
    val accent = Color(0xFF76B9FF)
    Row(Modifier.fillMaxWidth().background(Color(0xFF191B1F)), verticalAlignment = Alignment.CenterVertically) {
        TerminalArrowNub(inputOwner, canInput, onButton)
        Row(Modifier.weight(1f).testTag("terminal-toolbar-scroll").horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            insert?.let { TextButton(onClick = it) { Text("Insert") } }
            layout.visible.forEach { id -> key(id) {
                val button = layout.button(id)
                val custom = layout.action(id)
                val armed = button?.modifier != null && modifiers.armed == button.modifier
                val locked = armed && modifiers.sticky
                TextButton(onClick = {
                    if (button?.modifier != null) onModifier(button.modifier)
                    else if (button != null) onButton(button)
                    else if (custom != null) onCustom(custom)
                }, enabled = (canInput && (button != TerminalToolbarButton.FILES || filesEnabled)) || button in listOf(TerminalToolbarButton.ZOOM_IN, TerminalToolbarButton.ZOOM_OUT),
                    modifier = Modifier.testTag("terminal-shortcut-$id").semantics {
                        contentDescription = layout.label(id)
                        if (button?.modifier != null) stateDescription = if (locked) "Locked" else if (armed) "Armed" else "Off"
                    }, shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (armed) accent else Color.Transparent,
                        contentColor = if (armed) Color.Black else Color(0xFF9B9FA8)),
                    border = if (locked) androidx.compose.foundation.BorderStroke(2.dp, Color.White) else null
                ) { Text(button?.label ?: custom?.title.orEmpty(), maxLines = 1,
                    fontWeight = if (locked) FontWeight.Bold else FontWeight.Normal) }
            } }
        }
        TextButton(onClick = onCustomize, modifier = Modifier.semantics { contentDescription = "Customize terminal shortcuts" }) { Text("⋯") }
    }
}

@Composable
internal fun TerminalToolbarSettings(store: TerminalToolbarStore, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<TerminalToolbarAction?>(null) }
    var adding by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Terminal Shortcuts", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text("Choose buttons to show. Hold and drag a row to reorder it.", Modifier.padding(16.dp))
                store.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = store::reload) { Text("Retry") } }
                ToolbarShortcutList(store.layout, store.error == null, Modifier.weight(1f), store::save) { editing = it }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { adding = true }, enabled = store.error == null && store.layout.actions.size < 128) { Text("Add Custom Action") }
                    TextButton(onClick = { store.save(store.layout.reset()) }) { Text("Reset to Defaults") }
                }
            }
        }
    }
    if (adding || editing != null) TerminalToolbarActionEditor(editing,
        onSave = { store.save(store.layout.save(it)); editing = null; adding = false },
        onDismiss = { editing = null; adding = false })
}

@Composable
private fun ToolbarShortcutList(layout: TerminalToolbarLayout, enabled: Boolean, modifier: Modifier,
    onChange: (TerminalToolbarLayout) -> Unit, onEdit: (TerminalToolbarAction) -> Unit) {
    val list = rememberLazyListState()
    val current by rememberUpdatedState(layout)
    val change by rememberUpdatedState(onChange)
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    var dragged by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableFloatStateOf(0f) }
    var destination by remember { mutableIntStateOf(-1) }
    fun locate() {
        val visible = list.layoutInfo.visibleItemsInfo.filter { it.key in current.order }
        destination = visible.firstOrNull { pointer < it.offset + it.size / 2f }?.index
            ?: visible.lastOrNull()?.let { it.index + 1 } ?: -1
    }
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
    LazyColumn(modifier.testTag("terminal-shortcut-list").pointerInput(enabled) {
        if (enabled) detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                dragged = list.layoutInfo.visibleItemsInfo.firstOrNull { point.y >= it.offset && point.y < it.offset + it.size }?.key as? String
                pointer = point.y; locate()
            }, onDrag = { event, delta -> if (dragged != null) { event.consume(); pointer += delta.y; locate() } },
            onDragCancel = { dragged = null; destination = -1 },
            onDragEnd = { dragged?.let { change(current.move(it, destination)) }; dragged = null; destination = -1 })
    }, state = list, userScrollEnabled = dragged == null) {
        itemsIndexed(layout.order, key = { _, id -> id }) { index, id ->
            if (dragged != null && destination == index) HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth().background(if (dragged == id) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .padding(horizontal = 16.dp).semantics {
                    customActions = if (!enabled) emptyList() else listOfNotNull(
                        if (index > 0) CustomAccessibilityAction("Move up") { change(current.move(id, current.order.indexOf(id) - 1)); true } else null,
                        if (index < layout.order.lastIndex) CustomAccessibilityAction("Move down") { change(current.move(id, current.order.indexOf(id) + 2)); true } else null)
                }, verticalAlignment = Alignment.CenterVertically) {
                Text(layout.label(id), Modifier.weight(1f).padding(vertical = 16.dp))
                layout.action(id)?.let { action ->
                    TextButton(onClick = { onEdit(action) }, enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "Edit ${action.title}" }) { Text("Edit") }
                    TextButton(onClick = { change(current.remove(id)) }, enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "Delete ${action.title}" }) { Text("×") }
                }
                Switch(id in layout.enabled, onCheckedChange = { change(current.toggle(id, it)) }, enabled = enabled,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White),
                    modifier = Modifier.testTag("shortcut-toggle-$id").semantics { contentDescription = "Show ${layout.label(id)}" })
                Text("≡", Modifier.padding(start = 12.dp).clearAndSetSemantics { })
            }
        }
        item { if (dragged != null && destination == layout.order.size) HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun TerminalToolbarActionEditor(existing: TerminalToolbarAction?, onSave: (TerminalToolbarAction) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var text by rememberSaveable(existing?.id) { mutableStateOf(existing?.text?.removeSuffix("\n").orEmpty()) }
    var run by rememberSaveable(existing?.id) { mutableStateOf(existing?.text?.endsWith("\n") ?: true) }
    val id = rememberSaveable(existing?.id) { existing?.id ?: java.util.UUID.randomUUID().toString() }
    val action = TerminalToolbarAction(id, title.trim(), text + if (run) "\n" else "")
    val valid = text.isNotEmpty() && runCatching { action.validate() }.isSuccess
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Add Action" else "Edit Action") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Button label") }, singleLine = true,
                modifier = Modifier.testTag("shortcut-action-title"))
            OutlinedTextField(text, { text = it }, label = { Text("Sends") }, minLines = 2, maxLines = 6,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.testTag("shortcut-action-text"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Run after typing", Modifier.weight(1f))
                Switch(run, { run = it }, Modifier.semantics { contentDescription = "Run after typing" })
            }
            Text("Types this text into the terminal when tapped. Run after typing also presses Return.")
            if (title.length > 128 || action.text.toByteArray(Charsets.UTF_8).size > 16 * 1024)
                Text("Use a label up to 128 characters and text up to 16 KB.", color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = { onSave(action) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
