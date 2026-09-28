package io.github.docmorphic.cmuxapp

import android.os.Bundle
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import java.util.concurrent.atomic.AtomicBoolean

/** Adds IME attachments without replacing Compose's text, selection or composition connection. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RichContentEditor(
    owner: Any?, enabled: Boolean, onContent: (TerminalPasteContent) -> Boolean,
    onError: (String) -> Unit, content: @Composable () -> Unit
) {
    val accepting by rememberUpdatedState(enabled)
    val receive by rememberUpdatedState(onContent)
    val report by rememberUpdatedState(onError)
    val mounted = remember(owner) { AtomicBoolean(true) }
    DisposableEffect(mounted) { onDispose { mounted.set(false) } }
    // Restart the platform session when availability changes so the IME sees fresh MIME types.
    val interceptor = remember(mounted, enabled) {
        PlatformTextInputInterceptor { request, next ->
            val session = AtomicBoolean(true)
            try {
                next.startInputMethod(PlatformTextInputMethodRequest { attributes ->
                    val connection = request.createInputConnection(attributes)
                    attributes.contentMimeTypes = if (accepting) arrayOf("image/*") else null
                    RichContentInputConnection(connection,
                        isCurrent = { mounted.get() && session.get() && accepting },
                        receive = { receive(it) }, report = { report(it) })
                })
            } finally { session.set(false) }
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

internal class RichContentInputConnection(
    delegate: InputConnection,
    private val isCurrent: () -> Boolean,
    private val receive: (TerminalPasteContent) -> Boolean,
    private val report: (String) -> Unit
) : InputConnectionWrapper(delegate, false) {
    private val open = AtomicBoolean(true)

    override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
        if (!open.get() || !isCurrent()) return false
        return TerminalPasteContent.receiveImage(info, flags, receive, report) { finishComposingText() }
    }

    override fun closeConnection() {
        open.set(false)
        super.closeConnection()
    }
}
