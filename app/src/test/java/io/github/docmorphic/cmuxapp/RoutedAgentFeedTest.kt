package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedAgentFeedTest {
    private val mac = NativeCredentialStore.PairedMac("PRIVATE-PAIRING", "mac", "Mac", instanceTag = "stable")
    private val item = NativeAgentFeedItem("event", "turn", "Claude", AgentFeedKind.STOP, AgentFeedStatus.TELEMETRY,
        10.0, 11.0, reason = "Report ready", workspaceId = "workspace", surfaceId = "terminal")
    private val source = NativeFeedSource(mac, availability = NativeFeedAvailability.CONNECTED,
        workspaces = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"workspace","title":"Work","terminals":[{"id":"terminal","title":"Shell"}]}]}""")),
        capabilities = setOf(AGENT_FEED_CAPABILITY), agentFeed = NativeAgentFeedState(NativeAgentFeedSnapshot(1, listOf(item))))
    private fun input(source: NativeFeedSource = this.source) = NativeSidebarInput(listOf(source), emptyList(),
        listOf(NativeSortComputer(workspaceMacFilterId(mac.deviceId, mac.instanceTag)!!, "Mac")), NativeWorkspaceSortState())
    private fun host(input: () -> NativeSidebarInput?, navigate: (NativeSidebarTarget) -> Unit = {},
        read: (NativeAgentFeedEntry, Boolean?) -> Unit = { _, _ -> }) = NativeRoutedSidebarHost("owner", "salt", input,
        { RoutedSidebarLease({}) {} }, navigate, agentSession = { null }, readAgent = read, refreshAgent = {})
    private fun snapshot() = host({ input() }).feed(RoutedSidebarQuery(feed = true))

    @Test fun unicodeDuplicateStopsAgreeBetweenBrowserFeedBadgeAndLiveRead() = runTest {
        val preview = item.copy(reason = "Report\u00a0ready…", replyText = "Thanks")
        val full = item.copy(id = "full", createdAt = 9.0, reason = "Report ready for review")
        var current = input(source.copy(agentFeed = NativeAgentFeedState(NativeAgentFeedSnapshot(2, listOf(preview, full)))))
        val reads = mutableListOf<NativeAgentFeedEntry>()
        val h = host({ current }, read = { row, _ -> reads += row })
        repeat(3) {
            assertEquals(1, h.read(RoutedSidebarQuery())!!.feedNeedsInput)
            val row = h.feed(RoutedSidebarQuery(feed = true)).entries.single()
            assertEquals(full.reason, row.item.reason)
            assertEquals("Thanks", row.item.replyText)
            assertTrue(h.feedAction(RoutedAgentFeedCommand(row.key, RoutedAgentFeedVerb.READ)) { true })
        }
        assertEquals(3, reads.size)
        assertTrue(reads.all { it.item.id == full.id && it.item.replyText == "Thanks" })
        current = input(source.copy(agentFeed = NativeAgentFeedState(NativeAgentFeedSnapshot(3, emptyList()))))
        assertEquals(0, h.read(RoutedSidebarQuery())!!.feedNeedsInput)
        assertTrue(h.feed(RoutedSidebarQuery(feed = true)).entries.isEmpty())
    }

    @Test fun projectionUsesOpaqueIdentityAndRoundTripsAllDisplayValuesIncludingLongLocalReply() {
        val s = snapshot()
        val value = s.copy(entries = listOf(s.entries.single().copy(item = item.copy(
            replyText = "  " + "🙂".repeat(10000) + "\n", context = mapOf("extra" to " retained "),
            decision = AgentFeedDecision("approve", "plan", listOf("Choice"), " feedback "),
            questions = listOf(AgentFeedQuestion("q", "Header", "Question?", true,
                listOf(AgentFeedOption("a", "A", "Description"))))),
            failure = AgentFeedFailure("Unconfirmed", AgentFeedDelivery.UNCONFIRMED, "draft\n"))))
        val wire = RoutedAgentFeedWire.snapshot(value)
        assertFalse(wire.contains("PRIVATE-PAIRING"))
        assertEquals(64, value.entries.single().key.length)
        assertNull(value.entries.single().owner.instanceTag)
        assertEquals(value, RoutedAgentFeedWire.snapshot(wire))
    }
    @Test fun malformedSnapshotsCannotIntroduceUnknownOwnersDuplicateRowsOrUnknownKinds() {
        val wire = RoutedAgentFeedWire.snapshot(snapshot())
        val unknown = JSONObject(wire).apply { getJSONArray("entries").getJSONObject(0).put("owner", "unknown") }
        assertThrows(IllegalArgumentException::class.java) { RoutedAgentFeedWire.snapshot(unknown.toString()) }
        val duplicate = JSONObject(wire).apply { getJSONArray("entries").put(getJSONArray("entries").get(0)) }
        assertThrows(IllegalArgumentException::class.java) { RoutedAgentFeedWire.snapshot(duplicate.toString()) }
        val kind = JSONObject(wire).apply { getJSONArray("entries").getJSONObject(0).getJSONObject("item").put("kind", "invented") }
        assertThrows(NoSuchElementException::class.java) { RoutedAgentFeedWire.snapshot(kind.toString()) }
    }
    @Test fun commandValidationRejectsAmbiguousIntentAndKeepsLongReplies() {
        val commands = listOf(RoutedAgentFeedCommand("row", RoutedAgentFeedVerb.REPLY, text = "x".repeat(20000)),
            RoutedAgentFeedCommand("row", RoutedAgentFeedVerb.READ, needsInput = false),
            RoutedAgentFeedCommand("row", RoutedAgentFeedVerb.DECIDE, decision = AgentFeedDecision("approve")),
            RoutedAgentFeedCommand(null, RoutedAgentFeedVerb.REFRESH))
        commands.forEach { assertEquals(it, RoutedAgentFeedWire.command(RoutedAgentFeedWire.command(it))) }
        listOf(commands.last().copy(key = "row"), commands.first().copy(key = null),
            commands.first().copy(needsInput = true), commands[1].copy(text = "unrequested")).forEach {
            assertThrows(IllegalArgumentException::class.java) { RoutedAgentFeedWire.command(RoutedAgentFeedWire.command(it)) }
        }
    }
    @Test fun computerScopeDoesNotBroadenAfterRemovalAndReadUsesLiveIdentity() = runTest {
        var current: NativeSidebarInput? = input()
        val reads = mutableListOf<Pair<NativeAgentFeedEntry, Boolean?>>()
        val h = host({ current }, read = { row, needs -> reads += row to needs })
        val computer = h.read(RoutedSidebarQuery())!!.computers.single().key
        val query = RoutedSidebarQuery(feed = true, computer = computer)
        val row = h.feed(query).entries.single()
        assertTrue(h.feedAction(RoutedAgentFeedCommand(row.key, RoutedAgentFeedVerb.READ, needsInput = false)) { true })
        assertEquals(item, reads.single().first.item); assertEquals(false, reads.single().second)
        current = input(source.copy(mac = mac.copy(code = "REPLACEMENT")))
        assertNotEquals(row.key, h.feed(query).entries.single().key)
        try { h.feedAction(RoutedAgentFeedCommand(row.key, RoutedAgentFeedVerb.READ)) { true }; fail() } catch (_: IllegalStateException) { }
        current = input().copy(computers = emptyList())
        assertTrue(h.feed(query).entries.isEmpty())
        current = null
        assertThrows(IllegalStateException::class.java) { h.feed(query) }
        assertEquals(1, reads.size)
    }
    @Test fun preparedNavigationRechecksRequestDestinationAndConnection() {
        var current = input()
        val destinations = mutableListOf<NativeSidebarTarget>()
        val h = host({ current }, destinations::add)
        val key = h.feed(RoutedSidebarQuery(feed = true)).entries.single().key
        val open = h.resolveFeed(key, true)!!
        open(); assertEquals(NativeSidebarTarget.Agent(NativeAgentFeedEntry(source, item), true), destinations.single())
        current = input(source.copy(agentFeed = source.agentFeed.copy(snapshot = NativeAgentFeedSnapshot(2, listOf(item.copy(surfaceId = "changed"))))))
        assertNull(h.resolveFeed(key, true)); assertThrows(IllegalStateException::class.java) { open() }
        current = input(source.copy(availability = NativeFeedAvailability.OFFLINE))
        assertNull(h.resolveFeed(key, true))
        assertEquals(1, destinations.size)
    }
    @Test fun feedQueriesKeepIndependentSearchAndLegacyDecodeDefaults() {
        val query = RoutedSidebarQuery(workspaceQuery = "work", notificationQuery = "notice", feed = true,
            feedQuery = "agent", feedNeedsInputOnly = true)
        assertEquals(query, RoutedSidebarWire.query(RoutedSidebarWire.query(query)))
        assertFalse(query.workspaces); assertEquals("agent", query.text); assertTrue(query.unread)
        assertEquals("other", query.withText("other").feedQuery)
        assertEquals("work", query.withText("other").workspaceQuery)
        assertFalse(RoutedSidebarWire.query("{}").feed)
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarWire.query("""{"feed":true,"notifications":true}""") }
    }
    @Test fun controllerWaitsForAuthoritativeSnapshotAndDropsLateComputerResponse() = runTest {
        val first = CompletableDeferred<AgentFeedUiSnapshot>()
        val controller = RoutedAgentFeedController(backgroundScope, { query ->
            if (query.computer == null) withContext(NonCancellable) { first.await() } else snapshot().copy(entries = emptyList())
        }, { true }, { "text" }, { _, _ -> "ticket" })
        controller.configure(true, true); controller.visible(true, RoutedSidebarQuery(feed = true)); runCurrent()
        assertFalse(controller.state.value.hasSnapshot)
        controller.visible(true, RoutedSidebarQuery(feed = true, computer = "other")); runCurrent()
        assertTrue(controller.state.value.hasSnapshot); assertTrue(controller.state.value.snapshot.entries.isEmpty())
        first.complete(snapshot()); runCurrent()
        assertTrue(controller.state.value.snapshot.entries.isEmpty())
        controller.configure(false, false)
        assertFalse(controller.state.value.hasSnapshot)
    }
    @Test fun sessionRechecksPresentationBeforeReadingAnotherPageAndRejectsHiddenReply() = runTest {
        var visible = true; var requests = 0
        val session = NativeAgentFeedSession(backgroundScope, { true }, { _, _ ->
            requests++; visible = false
            JSONObject().put("text", "hello").put("version", 1.0).put("next_offset", 5)
        }, NativeAgentFeedSnapshot(1, listOf(item)))
        try { session.fullText(item) { visible }; fail() } catch (_: IllegalStateException) { }
        assertEquals(1, requests)
        try { session.terminalReply(item, "no") { false }; fail() } catch (_: IllegalStateException) { }
        assertEquals(1, requests); session.close()
    }
    @Test fun cancelledAdmittedReplyRetainsUncertaintyWithoutReplay() = runTest {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val session = NativeAgentFeedSession(backgroundScope, { true }, { _, _ ->
            calls++; started.complete(Unit); awaitCancellation()
        }, NativeAgentFeedSnapshot(1, listOf(item)))
        val reply = launch { session.terminalReply(item, "draft") }
        runCurrent(); assertTrue(started.isCompleted)
        reply.cancelAndJoin(); runCurrent()
        assertEquals(1, calls)
        assertEquals(AgentFeedFailure("Delivery could not be confirmed. Check the terminal before trying again.",
            AgentFeedDelivery.UNCONFIRMED, "draft"), session.state.value.failures[item.id])
        assertTrue(session.state.value.pending.isEmpty()); session.close()
    }

}
