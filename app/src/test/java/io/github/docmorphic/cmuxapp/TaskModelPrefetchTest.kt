package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskModelPrefetchTest {
    private val key = TaskModelRepository.Key("mac-one", TaskAgentCommand.CODEX)
    private val host = TaskModelResult(listOf(TaskModel("installed", "Installed")), TaskModelSource.DISCOVERED)
    private val backend = TaskModelResult(listOf(TaskModel("catalog", "Catalog")), TaskModelSource.BACKEND)
    private fun catalog() = TaskAgentCommand.entries.associateWith { backend }

    @Test fun fullCatalogKeepsAnEmptyProviderWithoutDroppingOtherProvidersAndRejectsInvalidSchema() {
        val value = JSONObject("""{"schemaVersion":1,"providers":{
          "claude":{"models":[]},"codex":{"models":[{"id":"a","label":"A"}],"defaultModel":"a"}}}""")
        val parsed = TaskModelParser.catalogAll(value)
        assertEquals(setOf(TaskAgentCommand.CLAUDE, TaskAgentCommand.CODEX), parsed.keys)
        assertFalse(parsed.getValue(TaskAgentCommand.CLAUDE).usable)
        assertEquals("a", parsed.getValue(TaskAgentCommand.CODEX).defaultModel!!.id)
        value.put("schemaVersion", 2)
        assertTrue(runCatching { TaskModelParser.catalogAll(value) }.isFailure)
        assertTrue(runCatching { TaskModelParser.catalogAll(JSONObject("""{"schemaVersion":1,"providers":{}}""")) }.isFailure)
    }

    @Test fun composerJoinsWarmingAndCancellingOneWaiterLeavesTheOtherLive() = runTest {
        val repo = TaskModelRepository(backgroundScope)
        val answer = CompletableDeferred<TaskModelResult>(); var hosts = 0; var catalogs = 0
        val first = mutableListOf<TaskModelResult>(); val second = mutableListOf<TaskModelResult>()
        val warm = backgroundScope.async {
            repo.refreshShared(key, "wire", backgroundScope, { hosts++; answer.await() }, { catalogs++; backend }, update = first::add)
        }
        runCurrent()
        val composer = backgroundScope.async {
            repo.refreshShared(key, "wire", backgroundScope, { error("Duplicate host probe") }, { error("Duplicate catalog") }, update = second::add)
        }
        runCurrent()
        assertEquals(backend, second.single()); assertEquals(1, hosts); assertEquals(1, catalogs)
        warm.cancel(); runCurrent()
        answer.complete(host); runCurrent()
        assertFalse(composer.await()); assertEquals(host, second.last())
        assertEquals(listOf(backend), first); assertEquals(host, repo.cached(key, "wire"))
    }

    @Test fun replacementWireRejectsOldNoncooperativeResultsAndOldCleanupCannotRetireIt() = runTest {
        val repo = TaskModelRepository(backgroundScope)
        val oldAnswer = CompletableDeferred<TaskModelResult>(); val newAnswer = CompletableDeferred<TaskModelResult>()
        val oldSeen = mutableListOf<TaskModelResult>(); val newSeen = mutableListOf<TaskModelResult>()
        val old = backgroundScope.async {
            repo.refreshShared(key, "old", backgroundScope,
                { withContext(NonCancellable) { oldAnswer.await() } }, { awaitCancellation() }, update = oldSeen::add)
        }
        runCurrent()
        val replacement = backgroundScope.async {
            repo.refreshShared(key, "new", backgroundScope, { newAnswer.await() }, { backend }, update = newSeen::add)
        }
        runCurrent()
        oldAnswer.complete(host); runCurrent()
        assertTrue(old.isCancelled); assertTrue(oldSeen.isEmpty())
        newAnswer.complete(host.copy(models = listOf(TaskModel("new", "New")))); runCurrent()
        assertFalse(replacement.await()); assertEquals("new", repo.cached(key, "new")!!.models.single().id)
        assertNull(repo.cached(key, "old"))
    }

    @Test fun lastWaiterCancellationWaitsForProducerExitBeforeRetiringTheSlot() = runTest {
        val repo = TaskModelRepository(backgroundScope)
        val release = CompletableDeferred<Unit>(); var catalogStopped = false
        val caller = backgroundScope.async {
            repo.refreshShared(key, "wire", backgroundScope,
                { withContext(NonCancellable) { release.await() }; host },
                { try { awaitCancellation() } finally { catalogStopped = true } })
        }
        runCurrent(); caller.cancel(); runCurrent()
        assertFalse(caller.isCompleted); assertTrue(catalogStopped)
        release.complete(Unit); runCurrent()
        assertTrue(caller.isCompleted); assertNull(repo.cached(key))
        assertFalse(repo.refreshShared(key, "wire", backgroundScope, { host }, { backend }))
    }

    @Test fun warmCacheAgeAndWireIdentityNeverSkipComposerRevalidation() = runTest {
        var clock = 0L; var probes = 0
        val repo = TaskModelRepository(backgroundScope) { clock }
        suspend fun refresh(wire: String?, age: Long) = repo.refreshShared(key, wire, backgroundScope,
            { probes++; host }, { backend }, maximumCacheAge = age)
        assertFalse(refresh("wire", TaskModelPrefetch.CACHE_AGE)); assertEquals(1, probes)
        clock = 299_999; assertFalse(refresh("wire", TaskModelPrefetch.CACHE_AGE)); assertEquals(1, probes)
        assertFalse(refresh("wire", 0)); assertEquals(2, probes)
        clock += TaskModelPrefetch.CACHE_AGE
        assertFalse(refresh("wire", TaskModelPrefetch.CACHE_AGE)); assertEquals(3, probes)
        assertFalse(refresh("replacement", TaskModelPrefetch.CACHE_AGE)); assertEquals(4, probes)
        assertNull(repo.cached(key, "wire"))
    }

    @Test fun accountClearCancelsWorkAndReentrantObserversCannotPublishIntoRetiredOwners() = runTest {
        val repo = TaskModelRepository(backgroundScope); val release = CompletableDeferred<TaskModelResult>()
        var secondCallbacks = 0
        val first = backgroundScope.async {
            repo.refreshShared(key, "wire", backgroundScope, { release.await() }, { awaitCancellation() }, update = { repo.clear() })
        }
        val second = backgroundScope.async {
            repo.refreshShared(key, "wire", backgroundScope, { error("Duplicate") }, { error("Duplicate") }, update = { secondCallbacks++ })
        }
        runCurrent(); release.complete(host); runCurrent()
        assertTrue(first.isCancelled); assertTrue(second.isCancelled)
        assertEquals(0, secondCallbacks); assertNull(repo.cached(key))
    }

    @Test fun warmingEveryMacAndProviderUsesAtMostFourSlotsAndOneCatalog() = runTest {
        val repo = TaskModelRepository(backgroundScope)
        val release = CompletableDeferred<Unit>(); var active = 0; var peak = 0; var catalogs = 0
        val probes = mutableListOf<Pair<String, TaskAgentCommand>>()
        val prefetch = TaskModelPrefetch(backgroundScope, repo, { catalogs++; catalog() })
        val targets = (1..6).map { number ->
            TaskModelPrefetch.Target("mac-$number", "wire-$number") { provider ->
                probes += "mac-$number" to provider; active++; peak = maxOf(peak, active)
                try { release.await(); host } finally { active-- }
            }
        }
        prefetch.update(targets); runCurrent()
        assertEquals(4, active); assertEquals(4, probes.size); assertEquals(1, catalogs)
        // A new callback instance/topology observation for an unchanged key is not a new probe.
        prefetch.update(targets.map { it.copy() }); runCurrent(); assertEquals(4, probes.size)
        release.complete(Unit); runCurrent()
        assertEquals(18, probes.size); assertEquals(18, probes.distinct().size)
        assertEquals(4, peak); assertEquals(0, active); assertEquals(1, catalogs)
        prefetch.update(targets); runCurrent(); assertEquals(18, probes.size)
        assertEquals(host, repo.cached(TaskModelRepository.Key("mac-6", TaskAgentCommand.OPENCODE), "wire-6"))
        prefetch.update(emptyList()); runCurrent()
    }

    @Test fun failedCatalogIsSharedWhileEveryIndependentHostStillGetsItsProbe() = runTest {
        val repo = TaskModelRepository(backgroundScope); var catalogs = 0; var hosts = 0
        val prefetch = TaskModelPrefetch(backgroundScope, repo, { catalogs++; error("Catalog offline") })
        val targets = (1..3).map { number -> TaskModelPrefetch.Target("mac-$number", "wire-$number") { hosts++; host } }
        prefetch.update(targets); runCurrent()
        assertEquals(9, hosts); assertEquals(1, catalogs)
        prefetch.update(targets); runCurrent(); assertEquals(9, hosts); assertEquals(1, catalogs)
        prefetch.update(emptyList()); runCurrent()
    }

    @Test fun aFailingProviderDoesNotPreventOtherProvidersAndOnlyChangedMacsRestart() = runTest {
        val repo = TaskModelRepository(backgroundScope); val probes = mutableListOf<Pair<String, TaskAgentCommand>>()
        val prefetch = TaskModelPrefetch(backgroundScope, repo, { catalog() })
        fun target(mac: String, wire: String) = TaskModelPrefetch.Target(mac, wire) { provider ->
            probes += mac to provider
            if (provider == TaskAgentCommand.CODEX) throw MobileRpcException("forbidden", "Unavailable")
            host
        }
        val a = target("a", "a-1"); val b = target("b", "b-1")
        prefetch.update(listOf(a, b)); runCurrent(); assertEquals(6, probes.size)
        prefetch.update(listOf(a, b)); runCurrent(); assertEquals(6, probes.size)
        prefetch.update(listOf(a, target("b", "b-2"))); runCurrent()
        assertEquals(3, probes.count { it.first == "a" }); assertEquals(6, probes.count { it.first == "b" })
        prefetch.update(emptyList()); runCurrent()
    }

    @Test fun rapidBackgroundAndResumeCannotMarkAnUnfinishedOldWaveCompleted() = runTest {
        val repo = TaskModelRepository(backgroundScope); val release = CompletableDeferred<Unit>(); var hosts = 0
        val prefetch = TaskModelPrefetch(backgroundScope, repo, { catalog() })
        val target = TaskModelPrefetch.Target("a", "wire") {
            hosts++
            withContext(NonCancellable) { release.await() }
            host
        }
        prefetch.update(listOf(target)); runCurrent(); assertEquals(3, hosts)
        prefetch.update(emptyList()); prefetch.update(listOf(target)); runCurrent()
        assertEquals(3, hosts)
        release.complete(Unit); runCurrent()
        assertEquals(6, hosts)
        assertEquals(host, repo.cached(TaskModelRepository.Key("a", TaskAgentCommand.CODEX), "wire"))
        prefetch.update(emptyList()); runCurrent()
    }

    @Test fun stoppingAnOfflineWaveCancelsTheOnlyCatalogAndResumeGetsOneFreshDownload() = runTest {
        val repo = TaskModelRepository(backgroundScope); var catalogs = 0; var cancelled = 0
        val prefetch = TaskModelPrefetch(backgroundScope, repo, {
            catalogs++
            try { awaitCancellation() } finally { cancelled++ }
        })
        val targets = listOf(TaskModelPrefetch.Target("a", null), TaskModelPrefetch.Target("b", null))
        prefetch.update(targets); runCurrent(); assertEquals(1, catalogs)
        prefetch.update(emptyList()); runCurrent(); assertEquals(1, cancelled)
        prefetch.update(targets); runCurrent(); assertEquals(2, catalogs)
        prefetch.update(emptyList()); runCurrent(); assertEquals(2, cancelled)
    }

    @Test fun cancellingWarmingAndItsCatalogLeavesAJoinedComposersHostReadLive() = runTest {
        val repo = TaskModelRepository(backgroundScope); val reply = CompletableDeferred<TaskModelResult>()
        var probes = 0; var stoppedCatalogs = 0
        val prefetch = TaskModelPrefetch(backgroundScope, repo, {
            try { awaitCancellation() } finally { stoppedCatalogs++ }
        })
        prefetch.update(listOf(TaskModelPrefetch.Target(key.origin, "wire") { probes++; reply.await() }))
        runCurrent(); assertEquals(3, probes)
        val seen = mutableListOf<TaskModelResult>()
        val composer = backgroundScope.async {
            repo.refreshShared(key, "wire", backgroundScope, { error("Duplicate host") }, { error("Duplicate catalog") }, update = seen::add)
        }
        runCurrent(); prefetch.update(emptyList()); runCurrent()
        assertEquals(1, stoppedCatalogs); assertFalse(composer.isCompleted)
        reply.complete(host); runCurrent()
        assertFalse(composer.await()); assertEquals(listOf(host), seen)
        assertEquals(host, repo.cached(key, "wire"))
    }
}
