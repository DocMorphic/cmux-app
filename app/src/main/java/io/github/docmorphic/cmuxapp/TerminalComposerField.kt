package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared terminal field geometry; transport, draft ownership and Send stay with the caller. */
@Composable
internal fun TerminalComposerField(
    text: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    sending: Boolean,
    failed: Boolean,
    modifier: Modifier = Modifier,
    editorModifier: Modifier = Modifier,
    sendModifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(20.dp)
    Row(modifier.background(foreground.copy(alpha = .06f), shape)
        .border(1.dp, foreground.copy(alpha = .12f), shape)
        .heightIn(min = 48.dp).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(text, onValueChange,
            modifier = Modifier.weight(1f).then(editorModifier).padding(vertical = 12.dp),
            enabled = enabled, readOnly = readOnly, minLines = 1, maxLines = 14,
            textStyle = LocalTextStyle.current.copy(color = foreground, fontSize = 17.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSend = { if (canSend && !sending) onSend() }),
            decorationBox = { editor ->
                Box {
                    if (text.isEmpty()) Text("Message", color = foreground.copy(alpha = .45f), fontSize = 17.sp, lineHeight = 22.sp)
                    editor()
                }
            })
        val label = when { sending -> "Sending"; failed -> "Send failed"; else -> "Send" }
        IconButton(onClick = onSend, enabled = canSend && !sending,
            modifier = sendModifier.size(48.dp).semantics { contentDescription = label }) {
            val fill = when {
                failed && !sending -> MaterialTheme.colorScheme.error
                sending || canSend -> MaterialTheme.colorScheme.primary
                else -> foreground.copy(alpha = .12f)
            }
            Box(Modifier.size(30.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                when {
                    sending -> CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    failed -> Text("!", color = Color.White, fontSize = 19.sp)
                    else -> Icon(painterResource(R.drawable.ic_task_submit), null, Modifier.size(20.dp),
                        tint = if (canSend) Color.White else foreground.copy(alpha = .35f))
                }
            }
        }
    }
}

/** A 40 dp visual circle within a 48 dp Android touch target. */
@Composable
internal fun ComposerIconButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier,
    active: Boolean = false, content: @Composable () -> Unit) {
    val foreground = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Box(Modifier.size(40.dp).background(foreground.copy(alpha = if (active) .16f else .06f), CircleShape),
            contentAlignment = Alignment.Center) { content() }
    }
}
