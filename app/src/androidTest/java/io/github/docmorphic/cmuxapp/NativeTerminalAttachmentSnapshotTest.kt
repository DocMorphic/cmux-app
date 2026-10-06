package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeTerminalAttachmentSnapshotTest {
    @Test fun encryptedPayloadSurvivesAcknowledgementUntilPreviewClosesAndLogoutRevokesReads() = runBlocking {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = TerminalDraftRepository.get(context)
        repo.drafts.clear(); repo.persistNow()
        val target = TerminalDrafts.Target("snapshot-mac", "workspace", "surface")
        val bytes = "Private preview snapshot after send".toByteArray()
        val item = ComposerAttachment(name = "snapshot.txt", size = bytes.size)
        try {
            repo.attach(target, AttachmentFiles.Prepared(item, bytes), repo.drafts.generation)
            val preview = checkNotNull(repo.preview(target, item, repo.drafts.generation))
            try {
                val send = checkNotNull(repo.drafts.begin(target))
                repo.drafts.finish(send, deliveredFiles = setOf(item.id)); repo.persistNow()
                val file = File(context.noBackupFilesDir, "terminal-attachments/${item.id}")
                assertTrue(file.exists()); assertFalse(file.readBytes().contentEquals(bytes))
                assertTrue(repo.drafts.state.value.isEmpty()); assertArrayEquals(bytes, preview.read())
                preview.close(); repo.persistNow(); assertFalse(file.exists())
                repo.attach(target, AttachmentFiles.Prepared(item, bytes), repo.drafts.generation)
                val revoked = checkNotNull(repo.preview(target, item, repo.drafts.generation))
                try {
                    repo.drafts.clear(); assertFalse(revoked.active.value)
                    assertTrue(runCatching { revoked.read() }.isFailure)
                    repo.persistNow(); assertFalse(file.exists())
                } finally { revoked.close() }
            } finally { preview.close() }
        } finally { repo.drafts.clear(); repo.persistNow() }
    }
}
