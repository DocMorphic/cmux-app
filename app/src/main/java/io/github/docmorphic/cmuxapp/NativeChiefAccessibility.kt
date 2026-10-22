package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import kotlinx.coroutines.delay

/** A captured text action remains bound to the original computer/account owner. */
@Composable
internal fun rememberChiefCopy(session: NativeChiefTranscriptSession): (String) -> Boolean {
    val view = LocalView.current
    return remember(session, view) {
        { text ->
            try {
                session.admitCapturedAction()
                view.context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("", text))
                true
            } catch (_: IllegalStateException) { false }
        }
    }
}

internal data class NativeChiefMessageAccessibility(
    val actions: List<CustomAccessibilityAction>,
    val copy: CustomAccessibilityAction?,
    val copiedState: String?
)

/** Copy's acknowledgement is a semantic state change, supported on Android 16+. */
@Composable
internal fun chiefMessageAccessibility(text: String, onReply: (() -> Boolean)?,
    onCopy: ((String) -> Boolean)?): NativeChiefMessageAccessibility {
    val replyLabel = stringResource(R.string.chief_reply_action)
    val copyLabel = stringResource(R.string.chief_menu_copy)
    val copiedLabel = stringResource(R.string.chief_message_copied)
    var copied by remember { mutableStateOf(false) }
    var revision by remember { mutableLongStateOf(0) }
    LaunchedEffect(revision) {
        if (revision > 0) {
            copied = false
            withFrameNanos { }
            copied = true
            delay(2_000)
            copied = false
        }
    }
    val copy = if (text.isNotEmpty() && onCopy != null) CustomAccessibilityAction(copyLabel) {
        if (onCopy(text)) { copied = true; revision++; true } else false
    } else null
    return NativeChiefMessageAccessibility(buildList {
        if (onReply != null) add(CustomAccessibilityAction(replyLabel, onReply))
        copy?.let(::add)
    }, copy, copiedLabel.takeIf { copied })
}
