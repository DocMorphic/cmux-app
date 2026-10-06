package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SshComposerDraftsTest {
    @Test fun selectedImageSnapshotOutlivesAcknowledgementAndReturnsIndependentBytes() = runBlocking {
        SshComposerPool().use { pool ->
            val draft = pool.open("terminal"); val item = image()
            draft.attach(item, byteArrayOf(7, 9)); draft.edit("Send me")
            val preview = checkNotNull(draft.preview(item)); val send = checkNotNull(draft.begin())
            draft.accepted(send, item); draft.finish(send)
            assertTrue(draft.current.attachments.isEmpty()); assertEquals("", draft.current.text)
            assertTrue(preview.active.value)
            assertArrayEquals(byteArrayOf(7, 9), preview.read())
            preview.read().fill(0); assertArrayEquals(byteArrayOf(7, 9), preview.read())
            assertTrue(runCatching { draft.read(item) }.isFailure)
            preview.close(); assertFalse(preview.active.value); assertTrue(runCatching { preview.read() }.isFailure)
        }
    }

    @Test fun routeRetirementAndAccountCloseRevokeSnapshotsEvenAfterAllChipsDisappear() = runBlocking {
        val pool = SshComposerPool(); val draft = pool.open("terminal", "host-A"); val item = image()
        draft.attach(item, byteArrayOf(1, 2)); val first = checkNotNull(draft.preview(item))
        draft.remove(item.id); assertTrue(first.active.value); assertArrayEquals(byteArrayOf(1, 2), first.read())
        val replacement = pool.open("terminal", "host-B")
        assertFalse(first.active.value); assertTrue(runCatching { first.read() }.isFailure)
        replacement.attach(item, byteArrayOf(3, 4)); val second = checkNotNull(replacement.preview(item))
        replacement.remove(item.id); assertTrue(second.active.value)
        first.close(); assertArrayEquals(byteArrayOf(3, 4), second.read())
        pool.close(); assertFalse(second.active.value); assertTrue(runCatching { second.read() }.isFailure)
        second.close()
    }

    @Test fun previewBindingAndPermissionRetireWithHostReplacementRemovalAndAccountEnd() {
        val pool = SshComposerPool()
        val first = pool.open("terminal", "host-A"); val attachment = image()
        first.attach(attachment, byteArrayOf(7, 8))
        assertTrue(first.ownsAttachment(attachment))
        assertFalse(first.ownsAttachment(attachment.copy(size = 1)))
        assertEquals(first.previewBinding, pool.open("terminal", "host-A").previewBinding)
        first.begin(); assertTrue(first.ownsAttachment(attachment))
        val replacement = pool.open("terminal", "host-B")
        assertNotEquals(first.previewBinding, replacement.previewBinding)
        assertFalse(first.ownsAttachment(attachment))
        replacement.attach(attachment, byteArrayOf(8, 9))
        assertFalse(first.ownsAttachment(attachment)); assertTrue(replacement.ownsAttachment(attachment))
        replacement.remove(attachment.id); assertFalse(replacement.ownsAttachment(attachment))
        replacement.attach(attachment, byteArrayOf(8, 9)); pool.close()
        assertFalse(replacement.ownsAttachment(attachment))
        assertTrue(runCatching { replacement.read(attachment) }.isFailure)
    }

    @Test fun verifiedServerKeysScopeDraftsEvenWhenTheSavedDialPlanIsUnchanged() {
        SshComposerPool().use { pool ->
            val host = java.util.UUID.randomUUID()
            val plan = SshDialPlan(java.util.UUID.randomUUID(), emptyList())
            val route = SshDraftRoute(plan, mapOf(host to "verified-server-key-A"))
            val first = pool.open("terminal", route); first.edit("old host draft")
            assertSame(first, pool.open("terminal", route.copy(hostKeys = route.hostKeys.toMap())))
            val replacement = pool.open("terminal", route.copy(hostKeys = mapOf(host to "verified-server-key-B")))
            assertNotSame(first, replacement); assertEquals("", replacement.current.text)
            assertFalse(first.edit("late callback"))
        }
    }

    @Test fun editingTheSshRouteCannotCarryOldDraftBytesToTheReplacementComputer() {
        SshComposerPool().use { pool ->
            val old = pool.open("terminal", route = "host-route-A"); val attachment = image()
            old.attach(attachment, byteArrayOf(1, 2)); old.edit("old host content")
            assertSame(old, pool.open("terminal", route = "host-route-A"))
            val replacement = pool.open("terminal", route = "host-route-B")
            assertTrue(replacement.current.attachments.isEmpty()); assertEquals("", replacement.current.text)
            assertTrue(runCatching { old.read(attachment) }.isFailure)
            old.close(); replacement.edit("new host content")
            assertEquals("new host content", replacement.current.text)
        }
    }

    private fun image(name: String = "image.png") = ComposerAttachment(name = name, size = 2, imageFormat = "png")

    @Test fun navigationAndRendererReplacementReuseOnlyTheSameTerminalDraft() {
        SshComposerPool().use { pool ->
            val first = pool.open("terminal-a"); val second = pool.open("terminal-b")
            val attachment = image(); val bytes = byteArrayOf(7, 9)
            first.edit("Explain this"); first.attach(attachment, bytes); bytes.fill(0)
            assertSame(first, pool.open("terminal-a"))
            assertEquals("Explain this", first.current.text)
            assertTrue(second.current.attachments.isEmpty()); assertEquals("", second.current.text)
            val read = first.read(attachment); read.fill(1)
            assertArrayEquals(byteArrayOf(7, 9), first.read(attachment))
            assertTrue(runCatching { second.read(attachment) }.isFailure)
        }
    }

    @Test fun partialSendRetainsRemainingImagesAndEditsWithoutDuplicatingAcceptedImages() {
        SshComposerPool().use { pool ->
            val draft = pool.open("terminal"); val first = image("first"); val second = image("second")
            draft.attach(first, byteArrayOf(1, 2)); draft.attach(second, byteArrayOf(3, 4)); draft.edit("original")
            val send = draft.begin()!!
            assertNull(draft.begin())
            draft.accepted(send, first)
            draft.edit("edited while sending")
            draft.finish(send, "Upload interrupted")
            assertEquals(listOf(second), draft.current.attachments)
            assertEquals("edited while sending", draft.current.text)
            assertTrue(runCatching { draft.read(first) }.isFailure)
            val retry = draft.begin()!!; draft.accepted(retry, second); draft.finish(retry)
            assertEquals("", draft.current.text); assertTrue(draft.current.attachments.isEmpty())
        }
    }

    @Test fun removingAWaitingImageSkipsItAndRetiredBindingCannotClearItsReplacement() {
        SshComposerPool().use { pool ->
            val draft = pool.open("same"); val attachment = image()
            draft.attach(attachment, byteArrayOf(1, 2)); draft.edit("old")
            val send = draft.begin()!!; draft.remove(attachment.id)
            assertFalse(draft.contains(send, attachment))
            pool.discardWhere { it == "same" }
            val replacement = pool.open("same"); replacement.edit("new")
            draft.finish(send); draft.close()
            assertEquals("new", replacement.current.text)
            assertFalse(draft.edit("stale"))
        }
    }

    @Test fun countCapsApplyAcrossTerminalsAndRemovalReleasesCapacity() {
        SshComposerPool().use { pool ->
            val first = pool.open("first"); val second = pool.open("second"); val third = pool.open("third")
            repeat(10) { first.attach(image(), byteArrayOf(1, 2)); second.attach(image(), byteArrayOf(1, 2)) }
            assertTrue(runCatching { first.attach(image(), byteArrayOf(1, 2)) }.isFailure)
            assertTrue(runCatching { third.attach(image(), byteArrayOf(1, 2)) }.isFailure)
            first.remove(first.current.attachments.first().id)
            third.attach(image(), byteArrayOf(1, 2)); assertEquals(1, third.current.attachments.size)
        }
    }

    @Test fun accountClosePreventsOldPreparationsAndAcknowledgementsFromRevivingBytes() {
        val pool = SshComposerPool(); val draft = pool.open("terminal"); val attachment = image()
        draft.attach(attachment, byteArrayOf(1, 2)); draft.edit("secret")
        val send = draft.begin()!!; pool.close()
        assertTrue(runCatching { draft.attach(image(), byteArrayOf(1, 2)) }.isFailure)
        assertTrue(runCatching { draft.read(attachment) }.isFailure)
        assertTrue(runCatching { pool.open("terminal") }.isFailure)
        draft.finish(send); draft.accepted(send, attachment)
        assertTrue(pool.state.value.isEmpty())
    }
}
