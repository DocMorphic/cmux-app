package io.github.docmorphic.cmuxapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Nonexported fixture for actual draft chips, encrypted reads, and Activity recreation. */
class TaskAttachmentPreviewTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = checkNotNull(intent.getStringExtra("session"))
        val id = checkNotNull(intent.getStringExtra("draft"))
        setContent { CmuxTheme {
            val repository by produceState<TaskDraftRepository?>(null, session) {
                value = withContext(Dispatchers.IO) { TaskDraftRepository.get(applicationContext, session) }
            }
            repository?.let { repo ->
                val editor = remember(repo, id) { repo.drafts.begin(id, "preview-fixture", "Preview Mac", "/repo") }
                DisposableEffect(editor) { onDispose { repo.drafts.end(editor) } }
                val drafts by repo.drafts.state.collectAsState()
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TaskAttachmentControls(repo, editor, "preview-fixture", drafts[id]?.attachments.orEmpty(),
                        enabled = false, canAdd = false, isCurrent = { false },
                        canPreview = { repo.drafts.isCurrent(editor) },
                        onPreparing = {}, onChanged = {}, onError = { error(it) }) { strip, _, _ -> strip() }
                }
            }
        } }
    }
}
