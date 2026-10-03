package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

@Composable
internal fun NativeMacAppearanceSettings(team: NativeTeamScope, target: NativeComputerTarget, colorIndex: Int? = null, permits: () -> Boolean) {
    val store = rememberNativeAppearanceStore(team) ?: return
    val snapshot by store.state.collectAsState()
    key(team, target.deviceId, target.buildTag) {
        NativeMacAppearanceSection(target, snapshot.get(target.deviceId, target.buildTag), snapshot.error,
            save = { change -> withContext(Dispatchers.IO) {
                store.update(NativeMacIdentity(target.deviceId, target.buildTag), permits, change)
            } }, retry = { withContext(Dispatchers.IO) { store.reload() } }, colorIndex = colorIndex)
    }
}

@Composable
internal fun NativeMacAppearanceSection(target: NativeComputerTarget, value: NativeMacAppearance, readError: Boolean,
    save: suspend ((NativeMacAppearance) -> NativeMacAppearance) -> Unit, retry: suspend () -> Unit, colorIndex: Int? = null) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember(target, value.name) { mutableStateOf(value.name.orEmpty()) }
    var emoji by remember(target) { mutableStateOf("") }
    var colorPicker by remember { mutableStateOf(false) }
    fun change(transform: (NativeMacAppearance) -> NativeMacAppearance) {
        if (pending || readError) return
        pending = true
        scope.launch {
            try { save(transform); error = null }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "Could not save appearance. Check your account and try again." }
            finally { pending = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("APPEARANCE", color = Color(0xFF9B9FA8), fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NativeMacAvatar(value, nativeMacColorIdentity(target.deviceId, target.buildTag).colorSeed, index = colorIndex)
            Text(value.displayName(target.name), maxLines = 2)
        }
        if (readError) {
            Text("Could not load saved appearance. Retry before making changes.", color = Color(0xFFFF9999))
            TextButton(enabled = !pending, onClick = {
                pending = true
                scope.launch { try { retry() } finally { pending = false } }
            }) { Text("Retry appearance") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it.take(256) }, Modifier.weight(1f), enabled = !pending && !readError,
                label = { Text("Name") }, placeholder = { Text(target.name.ifBlank { "Mac" }) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { val text = name; change { it.copy(name = text) } }))
            TextButton(enabled = !pending && !readError, onClick = { val text = name; change { it.copy(name = text) } }) { Text("Save name") }
        }
        Text("Leave the name empty to use the Mac's name.", color = Color(0xFF9B9FA8), fontSize = 12.sp)
        Text("Color", fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppearanceChoice("Auto color", value.color == null, !pending && !readError, { change { it.copy(color = null) } }) { Text("Auto", fontSize = 12.sp) }
            NativeMacAvatarColors.palettes.forEachIndexed { index, colors ->
                AppearanceChoice("Color palette ${index + 1}", value.color == "palette:$index", !pending && !readError,
                    { change { it.copy(color = "palette:$index") } }) {
                    Box(Modifier.size(30.dp).background(Brush.linearGradient(colors), CircleShape))
                }
            }
            AppearanceChoice("Custom color", value.color?.startsWith("#") == true, !pending && !readError, { colorPicker = true }) { Text("＋") }
        }
        Text("Icon", fontSize = 14.sp)
        (listOf<String?>(null) + NativeMacAppearance.symbols + NativeMacAppearance.emojis).chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { icon ->
                    AppearanceChoice(if (icon == null) "Auto icon" else "Icon ${NativeMacAppearance.symbolLabels[icon] ?: icon}", value.icon == icon, !pending && !readError,
                        { change { it.copy(icon = icon) } }) {
                        if (icon == null) Text("Auto", fontSize = 12.sp) else NativeMacGlyph(icon)
                    }
                }
                repeat(6 - row.size) { Spacer(Modifier.size(48.dp)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            fun submitEmoji() {
                val text = emoji
                if (text.isBlank()) return
                if (runCatching { NativeMacAppearance.icon(text) }.isFailure) {
                    error = "Enter an emoji or choose an icon above."; return
                }
                change { it.copy(icon = text) }
            }
            OutlinedTextField(emoji, { emoji = it.take(64) }, Modifier.weight(1f), label = { Text("Custom emoji") },
                enabled = !pending && !readError, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitEmoji() }))
            TextButton(enabled = !pending && !readError && emoji.isNotBlank(), onClick = { submitEmoji() }) { Text("Use emoji") }
        }
        if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = Color(0xFFFF9999), fontSize = 13.sp) }
    }
    if (colorPicker) NativeAppearanceColorDialog(value.color, { colorPicker = false }) { color ->
        colorPicker = false; change { it.copy(color = color) }
    }
}

@Composable
private fun AppearanceChoice(label: String, chosen: Boolean, enabled: Boolean, click: () -> Unit, content: @Composable () -> Unit) {
    IconButton(click, enabled = enabled, modifier = Modifier.size(48.dp).semantics {
        contentDescription = label; selected = chosen
    }.border(if (chosen) 2.dp else 0.dp, if (chosen) Color(0xFF83B9FF) else Color.Transparent, CircleShape)) { content() }
}

@Composable
private fun NativeAppearanceColorDialog(current: String?, dismiss: () -> Unit, save: (String) -> Unit) {
    var hex by remember { mutableStateOf(current?.takeIf { it.startsWith("#") } ?: "#0A84FF") }
    val valid = Regex("#[0-9A-Fa-f]{6}").matches(hex)
    val rgb = hex.drop(1).toIntOrNull(16)?.takeIf { valid } ?: 0x0A84FF
    AlertDialog(onDismissRequest = dismiss, title = { Text("Custom color") }, text = {
        Column {
            Box(Modifier.fillMaxWidth().height(36.dp).background(Color(0xFF000000 or rgb.toLong())))
            OutlinedTextField(hex, { hex = it.take(7).uppercase() }, label = { Text("Hex color") },
                isError = !valid, singleLine = true)
            listOf("Red" to 16, "Green" to 8, "Blue" to 0).forEach { (label, shift) ->
                Text(label, fontSize = 12.sp)
                Slider(((rgb shr shift) and 255).toFloat(), { value ->
                    val next = (rgb and (255 shl shift).inv()) or (value.toInt().coerceIn(0, 255) shl shift)
                    hex = "#%06X".format(next)
                }, valueRange = 0f..255f, modifier = Modifier.semantics { contentDescription = label })
            }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { save(hex) }) { Text("Use color") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
