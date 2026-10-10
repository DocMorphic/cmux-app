package io.github.docmorphic.cmuxapp

import android.os.Bundle
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import androidx.compose.runtime.*
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import java.util.concurrent.atomic.AtomicBoolean

/** Adds system-menu, hardware and IME attachments while retaining the native text editor. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RichContentEditor(
    owner: Any?, enabled: Boolean, onContent: (TerminalPasteContent) -> Boolean,
    onError: (String) -> Unit, truncateAttachmentPaste: Boolean = false,
    content: @Composable (Modifier) -> Unit
) {
    val context = LocalContext.current
    val accepting by rememberUpdatedState(enabled)
    val receive by rememberUpdatedState(onContent)
    val report by rememberUpdatedState(onError)
    val mounted = remember(owner) { AtomicBoolean(true) }
    DisposableEffect(mounted) { onDispose { mounted.set(false) } }
    val paste = remember(mounted, context, truncateAttachmentPaste) { ComposerClipboardPaste(context,
        { mounted.get() }, { accepting }, { receive(it) }, { report(it) }, truncateAttachmentPaste) }
    var clipboardRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(paste) {
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener { clipboardRevision++ }
        paste.clipboard.addPrimaryClipChangedListener(listener)
        onDispose { paste.clipboard.removePrimaryClipChangedListener(listener) }
    }
    // This holder only captures the current menu's native callback. It is reset when
    // menu data is built; it is never saved, and an old owner's action is inert.
    val menu = remember(mounted) { ComposerPasteMenu() }
    val modifier = Modifier.onPreviewKeyEvent { paste.key(it.nativeKeyEvent) }
        .appendTextContextMenuComponents {
            clipboardRevision // Rebuild an open menu when the clipboard changes.
            menu.nativePaste = null
            menu.replace = paste.mayHaveAttachments()
            if (menu.replace) item(menu, context.getString(android.R.string.paste)) {
                if (mounted.get() && !paste.paste()) {
                    val native = menu.nativePaste
                    if (native != null) native.onClick(this)
                    else report("The clipboard changed. Please paste again.")
                }
                close()
            }
        }.filterTextContextMenuComponents { component ->
            if (menu.replace && component.key == TextContextMenuKeys.PasteKey) {
                menu.nativePaste = component as? TextContextMenuItem
                false
            } else true
        }
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
                        receive = { receive(it) }, report = { report(it) },
                        paste = { if (mounted.get() && session.get()) paste.paste() else true },
                        pasteText = { if (mounted.get() && session.get()) paste.plainText() else null })
                })
            } finally { session.set(false) }
        }
    }
    InterceptPlatformTextInput(interceptor) { content(modifier) }
}

private class ComposerPasteMenu {
    var replace = false
    var nativePaste: TextContextMenuItem? = null
}

internal class RichContentInputConnection(
    delegate: InputConnection,
    private val isCurrent: () -> Boolean,
    private val receive: (TerminalPasteContent) -> Boolean,
    private val report: (String) -> Unit,
    private val paste: () -> Boolean = { false },
    private val pasteText: () -> CharSequence? = { null }
) : InputConnectionWrapper(delegate, false) {
    private val open = AtomicBoolean(true)

    override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
        if (!open.get() || !isCurrent()) return false
        return TerminalPasteContent.receiveImage(info, flags, receive, report) { finishComposingText() }
    }

    override fun performContextMenuAction(id: Int): Boolean {
        if (!open.get()) return false
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            if (paste()) return true
            // Keep selection and paste in the delegate's ordered edit lane.
            // Legacy Compose's context action dispatches a synthetic paste key
            // even while returning false; it can race the preceding selection.
            val text = pasteText() ?: return false
            if (!open.get()) return false
            super.beginBatchEdit()
            return try {
                super.finishComposingText()
                super.commitText(text, 1)
            } finally { super.endBatchEdit() }
        }
        return super.performContextMenuAction(id)
    }

    override fun closeConnection() {
        open.set(false)
        super.closeConnection()
    }
}
