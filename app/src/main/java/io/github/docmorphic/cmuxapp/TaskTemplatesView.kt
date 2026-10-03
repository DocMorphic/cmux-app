package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONObject
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.icu.text.BreakIterator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

@Composable
internal fun TaskTemplateIcon(value: String, modifier: Modifier = Modifier) {
    val image = when (value) { "agent:claude" -> R.drawable.ic_task_claude
        "agent:codex" -> R.drawable.ic_task_codex; "agent:opencode" -> R.drawable.ic_task_opencode; else -> null }
    if (image != null) Image(painterResource(image), contentDescription = null, modifier = modifier.size(24.dp))
    else if (value in templateSymbols.map { it.first }) Icon(painterResource(nativeWorkspaceGroupIcon(value)),
        contentDescription = null, modifier = modifier.size(24.dp), tint = Color(0xFFE1E3E8))
    else Text(value, modifier, style = MaterialTheme.typography.titleLarge)
}

private val templateSymbols = listOf("agent:claude" to "Claude", "agent:codex" to "Codex", "agent:opencode" to "OpenCode",
    "terminal" to "Terminal", "hammer" to "Hammer", "wrench.and.screwdriver" to "Tools", "globe" to "Globe",
    "folder" to "Folder", "bolt" to "Bolt", "testtube.2" to "Test", "ladybug" to "Bug", "doc.text" to "Document", "shippingbox" to "Package")

/** Mirrors the iOS template list and add/edit form; edits publish only after storage succeeds. */
@Composable
internal fun TaskTemplatesView(state: TaskTemplateState, onDismiss: () -> Unit,
    onChange: suspend (TaskTemplateChange) -> Unit, onSaved: (TaskTemplate, Boolean) -> Unit,
    onDeleted: (String) -> Unit) {
    var editingJson by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingJson?.let { TaskTemplate.read(JSONObject(it)) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var newId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun back() { if (!busy) { if (editing != null || adding) { editingJson = null; adding = false; error = null } else onDismiss() } }
    fun commit(change: TaskTemplateChange, success: () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { onChange(change); success() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Could not save task templates"
            } finally { busy = false }
        }
    }
    BackHandler { back() }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0C0E)) {
        if (editing != null || adding) key(editing?.id, adding) {
            val initial = editing
            var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
            var icon by rememberSaveable { mutableStateOf(initial?.icon ?: "terminal") }
            var emoji by rememberSaveable { mutableStateOf(icon.takeUnless { v -> templateSymbols.any { it.first == v } }.orEmpty()) }
            var command by rememberSaveable { mutableStateOf(initial?.command.orEmpty()) }
            var directory by rememberSaveable { mutableStateOf(initial?.defaultDirectory.orEmpty()) }
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { back() }, enabled = !busy) { Text("Cancel") }
                    Text(if (adding) "Add Template" else "Edit Template", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                        val template = TaskTemplate(initial?.id ?: newId, name, icon, command, directory).normalized()
                        val wasAdding = adding
                        commit(TaskTemplateChange.Save(template, wasAdding && state.entries.none { it.id == template.id })) { onSaved(template, wasAdding); editingJson = null; adding = false }
                    }) { Text(if (busy) "Saving…" else "Save") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("Name") })
                    Text("Icon", color = Color(0xFF9B9FA8))
                    templateSymbols.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (value, label) ->
                                Box(Modifier.size(48.dp).background(if (icon == value) Color(0xFF253440) else Color(0xFF191B1F), androidx.compose.foundation.shape.CircleShape)
                                    .semantics { contentDescription = "Template icon: $label"; selected = icon == value }
                                    .clickable(enabled = !busy) { icon = value; emoji = "" }, contentAlignment = Alignment.Center) {
                                    TaskTemplateIcon(value)
                                }
                            }
                        }
                    }
                    OutlinedTextField(emoji, { text ->
                        val trimmed = text.trim()
                        val first = if (trimmed.isEmpty()) "" else BreakIterator.getCharacterInstance(Locale.ROOT).run {
                            setText(trimmed); first(); trimmed.substring(0, next()) }
                        emoji = first
                        if (first.isNotEmpty()) icon = first
                    }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text("Custom emoji") })
                    OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), enabled = !busy,
                        minLines = 3, maxLines = 10, label = { Text("Command") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                    Text("The task prompt is available to the command as \$CMUX_TASK_PROMPT. Example: claude -- \"\$CMUX_TASK_PROMPT\"",
                        color = Color(0xFF9B9FA8), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(directory, { directory = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                        label = { Text("Default directory") }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                    error?.let { Text(it, color = Color(0xFFFF9999)) }
                    Spacer(Modifier.height(16.dp))
                }
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Done") }
                Text("Task Templates", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { newId = UUID.randomUUID().toString(); adding = true; error = null }, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "Add Template" }) { Text("＋") }
            }
            error?.let { Text(it, color = Color(0xFFFF9999)) }
            LazyColumn(Modifier.weight(1f)) {
                items(state.entries, key = { it.id }) { template ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable(enabled = !busy) { editingJson = template.json().toString(); error = null }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TaskTemplateIcon(template.icon)
                            Column(Modifier.weight(1f)) { Text(template.name)
                                Text(if (template.plainShell) "Plain shell" else template.command, maxLines = 1,
                                    color = Color(0xFF9B9FA8), style = MaterialTheme.typography.bodySmall) }
                        }
                        if (template.builtInKind == null) TextButton(enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "Delete template: ${template.name}" },
                            onClick = { commit(TaskTemplateChange.Delete(template.id)) { onDeleted(template.id) } }) { Text("Delete", color = Color(0xFFFF9999)) }
                    }
                    HorizontalDivider(color = Color(0xFF30333A))
                }
                item { Text("The task prompt is available to the command as \$CMUX_TASK_PROMPT. A blank command opens a plain shell.",
                    Modifier.padding(vertical = 18.dp), color = Color(0xFF9B9FA8), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
