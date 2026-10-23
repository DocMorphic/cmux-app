package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale

internal fun browserDialogHasLongLabel(text: String): Boolean {
    val iterator = android.icu.text.BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    repeat(13) { if (iterator.next() == android.icu.text.BreakIterator.DONE) return false }
    return true
}

@Composable private fun dialogStyle(points: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = LocalTextStyle.current.fontFamily, fontSize = points.sp, fontWeight = weight,
    lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false))

/** In-pane card keeps navigation reachable, as BrowserStreamPane's overlay
 * does. Geometry/labels/fields follow the source; matched glass/material and
 * physical accessibility acceptance remain separate. */
@Composable
internal fun NativeBrowserDialogCard(value: JSONObject, onRespond: (JSONObject, String?) -> Unit) {
    val id = value.optString("dialog_id")
    val field = value.optJSONObject("text_field")
    val basic = value.optString("kind") == "http_basic_authentication"
    var text by remember(id) { mutableStateOf(field?.optString("initial").orEmpty()) }
    var username by remember(id) { mutableStateOf(if (basic) field?.optString("initial").orEmpty() else "") }
    var password by remember(id) { mutableStateOf("") }
    val buttons = value.optJSONArray("buttons")?.let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.orEmpty()
    val informational = value.optBoolean("informational")
    val media = value.optString("kind") == "media_capture_permission"
    val fallback = stringResource(when {
        informational -> R.string.cmux_browser_dialog_needsmac
        media -> R.string.cmux_browser_dialog_mediatitle
        else -> R.string.cmux_browser_dialog_requesttitle
    })
    val title = value.opt("title") as? String ?: fallback
    val detail = (value.opt("message") as? String)?.takeIf { it.isNotEmpty() } ?: when {
        informational -> stringResource(R.string.cmux_browser_dialog_needsmacdetail)
        media -> stringResource(R.string.cmux_browser_dialog_mediadetail)
        else -> null
    }
    val host = (value.opt("host") as? String)?.takeIf { it.isNotEmpty() }
    val fieldPlaceholder = field?.opt("placeholder") as? String
    val inputLabel = stringResource(R.string.cmux_browser_dialog_input)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("BrowserDialog")) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .48f))
            .pointerInput(Unit) { detectTapGestures {} })
        val shape = RoundedCornerShape(24.dp)
        Column(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp).fillMaxWidth()
            .heightIn(max = (maxHeight - 48.dp).coerceAtLeast(1.dp)).clip(shape)
            .background(NativeAppColors.panel).border(.5.dp, NativeAppColors.line, shape)
            .semantics { paneTitle = title.ifEmpty { fallback }; isTraversalGroup = true }
            .verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = dialogStyle(17, FontWeight.SemiBold), color = NativeAppColors.text,
                    modifier = Modifier.semantics { heading() })
                if (detail != null) Text(detail, style = dialogStyle(15), color = NativeAppColors.muted)
                if (value.opt("title") !is String && host != null) Text(host, style = dialogStyle(12), color = NativeAppColors.muted)
            }
            if (basic) {
                BrowserDialogField(username, { username = it }, stringResource(R.string.cmux_browser_dialog_username), "BrowserDialogUsername", next = true)
                BrowserDialogField(password, { password = it }, fieldPlaceholder ?: stringResource(R.string.cmux_browser_dialog_password), "BrowserDialogPassword", secure = true)
            } else if (field != null) {
                BrowserDialogField(text, { text = it }, fieldPlaceholder ?: inputLabel, "BrowserDialogText", secure = field.optBoolean("secure"))
            }
            @Composable fun Action(button: JSONObject, modifier: Modifier) {
                val prominent = button.optString("role") == "default"
                Button(onClick = { onRespond(button, browserDialogResponseText(value, button, text, username, password)) },
                    modifier = modifier.heightIn(min = 48.dp).testTag("BrowserDialogButton:${button.optString("id")}"),
                    shape = RoundedCornerShape(percent = 50), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (prominent) NativeAppColors.accent else NativeAppColors.control,
                        contentColor = when { button.optString("role") == "destructive" -> NativeAppColors.error
                            prominent -> Color.White; else -> NativeAppColors.text })) {
                    Text(button.optString("label"), style = dialogStyle(17), modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (buttons.size <= 2 && buttons.none { browserDialogHasLongLabel(it.optString("label")) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    buttons.forEach { Action(it, Modifier.weight(1f)) }
                }
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons.forEach { Action(it, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable private fun BrowserDialogField(value: String, change: (String) -> Unit, placeholder: String,
    tag: String, secure: Boolean = false, next: Boolean = false) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(value, change, Modifier.fillMaxWidth().background(NativeAppColors.control, shape)
        .border(.5.dp, NativeAppColors.line, shape).padding(12.dp).testTag(tag)
        .semantics { contentDescription = placeholder }, singleLine = true,
        textStyle = dialogStyle(17).copy(color = NativeAppColors.text), cursorBrush = SolidColor(NativeAppColors.accent),
        visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (secure) KeyboardType.Password else KeyboardType.Text,
            imeAction = if (next) ImeAction.Next else ImeAction.Done),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { keyboard?.hide() }),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = dialogStyle(17), color = NativeAppColors.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); inner() } })
}
