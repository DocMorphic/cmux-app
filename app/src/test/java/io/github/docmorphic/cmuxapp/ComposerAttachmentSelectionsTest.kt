package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ComposerAttachmentSelectionsTest {
    private class Snapshot : ComposerAttachmentSnapshot {
        override val attachment = ComposerAttachment(name = "image.png", size = 1, imageFormat = "png")
        override val active = MutableStateFlow(true)
        var closes = 0
        override suspend fun read() = byteArrayOf(1)
        override fun close() { closes++; active.value = false }
    }
    private val owner = ComposerAttachmentPreviewOwner.Ssh("first")
    @Test fun switchingSourcesReleasesOldSnapshotAndStaleDismissalCannotCloseReplacement() {
        val selections = ComposerAttachmentSelections(); val a = Snapshot(); val b = Snapshot()
        val old = selections.select(owner, a)!!
        val next = selections.select(ComposerAttachmentPreviewOwner.Ssh("second"), b)!!
        assertEquals(1, a.closes); assertSame(next, selections.state.value)
        selections.clear(old.identity); assertTrue(b.active.value)
        selections.clear(next.identity); assertNull(selections.state.value); assertEquals(1, b.closes)
        selections.close(); assertEquals(1, b.closes)
    }
    @Test fun destroyedPresentationCannotAcceptALateSnapshot() {
        val selections = ComposerAttachmentSelections(); selections.close()
        val late = Snapshot(); assertNull(selections.select(owner, late))
        assertEquals(1, late.closes); assertNull(selections.state.value)
    }
    @Test fun alreadyRevokedSourceCannotReplaceCurrentPresentationOrLeakLease() {
        val selections = ComposerAttachmentSelections(); val original = Snapshot()
        val current = selections.select(owner, original)!!
        val retired = Snapshot().apply { active.value = false }
        assertNull(selections.select(owner, retired)); assertEquals(1, retired.closes)
        assertSame(current, selections.state.value); assertEquals(0, original.closes)
        selections.close(); assertEquals(1, original.closes)
    }
}
