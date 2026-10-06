package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class TerminalDraftsTest {
    private val first = TerminalDrafts.Target("mac-a", "workspace", "surface")
    private val second = first.copy(surface = "other")

    @Test fun attachmentPreviewPermissionUsesExactTargetMetadataAndAccountGeneration() {
        val drafts = TerminalDrafts()
        val item = ComposerAttachment(name = "staged.txt", size = 4)
        val generation = drafts.generation
        drafts.attach(first, item, generation)
        assertTrue(drafts.ownsAttachment(first, item, generation))
        assertFalse(drafts.ownsAttachment(second, item, generation))
        assertFalse(drafts.ownsAttachment(first.copy(pairing = "other-mac"), item, generation))
        assertFalse(drafts.ownsAttachment(first.copy(workspace = "other-workspace"), item, generation))
        assertFalse(drafts.ownsAttachment(first, item.copy(name = "replacement.txt"), generation))
        drafts.begin(first)
        assertTrue("Sending does not forbid preview", drafts.ownsAttachment(first, item, generation))
        drafts.removeAttachment(first, item.id)
        assertFalse(drafts.ownsAttachment(first, item, generation))
        drafts.clear(); drafts.attach(first, item, drafts.generation)
        assertFalse(drafts.ownsAttachment(first, item, generation))
        assertTrue(drafts.ownsAttachment(first, item, drafts.generation))
    }

    @Test fun acknowledgementClearsOnlyCapturedTerminalAndPreservesNewEdits() {
        val drafts = TerminalDrafts()
        drafts.edit(first, "first\nmessage")
        drafts.edit(second, "second draft")
        val send = drafts.begin(first)!!
        drafts.edit(first, "next message")
        drafts.finish(send)
        assertEquals("next message", drafts.state.value[first]?.text)
        assertEquals("second draft", drafts.state.value[second]?.text)
        assertNull(drafts.state.value[first]?.operation)
    }

    @Test fun editedThenRestoredTextIsStillANewDraft() {
        val drafts = TerminalDrafts()
        drafts.edit(first, "same text")
        val send = drafts.begin(first)!!
        drafts.edit(first, "changed")
        drafts.edit(first, "same text")
        drafts.finish(send)
        assertEquals("same text", drafts.state.value[first]?.text)
    }

    @Test fun doubleSendIsRejectedAndFailureRetainsDraftForExplicitRetry() {
        val drafts = TerminalDrafts()
        drafts.edit(first, "keep me")
        val send = drafts.begin(first)!!
        assertNull(drafts.begin(first))
        drafts.finish(send, TerminalDrafts.DELIVERY_UNCONFIRMED)
        assertEquals("keep me", drafts.state.value[first]?.text)
        val retry = drafts.begin(first)!!
        assertNotEquals(send.operation, retry.operation)
        drafts.finish(send) // Late completion of the old request cannot clear a new send.
        assertEquals(retry.operation, drafts.state.value[first]?.operation)
        drafts.finish(retry)
        assertFalse(drafts.state.value.containsKey(first))
    }

    @Test fun savedDraftsKeepMacScopeAndRecoverInterruptedSendWithoutResending() {
        val drafts = TerminalDrafts()
        val otherMac = first.copy(pairing = "mac-b")
        drafts.edit(first, "multi\nline ☃")
        drafts.edit(otherMac, "other Mac")
        drafts.begin(first)
        val restored = TerminalDrafts(drafts.saved())
        assertEquals("multi\nline ☃", restored.state.value[first]?.text)
        assertNull(restored.state.value[first]?.operation)
        assertEquals(TerminalDrafts.DELIVERY_UNCONFIRMED, restored.state.value[first]?.error)
        assertEquals("other Mac", restored.state.value[otherMac]?.text)
        assertNull(restored.state.value[otherMac]?.error)
    }

    @Test fun signOutClearInvalidatesInFlightAcknowledgements() {
        val drafts = TerminalDrafts()
        drafts.edit(first, "old account")
        val old = drafts.begin(first)!!
        drafts.clear()
        drafts.edit(first, "new account")
        drafts.finish(old)
        assertEquals("new account", drafts.state.value[first]?.text)
    }
}
