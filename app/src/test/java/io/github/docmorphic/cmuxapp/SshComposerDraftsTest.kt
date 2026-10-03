package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class SshComposerDraftsTest {
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
