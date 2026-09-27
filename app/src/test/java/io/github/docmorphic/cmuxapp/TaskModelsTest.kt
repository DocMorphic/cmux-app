package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.zip.GZIPInputStream

class TaskModelsTest {
    private val key = TaskModelRepository.Key("mac-one", TaskAgentCommand.CODEX)
    private val fast = TaskEffort("low", "Low", "Faster")
    private val deep = TaskEffort("high", "High", "More reasoning")
    private val model = TaskModel("model-one", "Model One", listOf(fast, deep), "high")
    private val host = TaskModelResult(listOf(model), TaskModelSource.DISCOVERED, model)
    private val backend = TaskModelResult(listOf(model.copy(id = "catalog")), TaskModelSource.BACKEND)

    @Test fun commandOptionsMatchUnmodifiedPinnedSwift() {
        val data = javaClass.getResourceAsStream("/tasks/ios-commands.json.gz")!!
        val golden = JSONObject(GZIPInputStream(data).bufferedReader().use { it.readText() })
        assertEquals("4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0", golden.getString("upstream"))
        val rows = golden.getJSONArray("cases")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val command = row.getString("command")
            val provider = TaskAgentCommand.detect(command)
            assertEquals(row.toString(), if (row.isNull("provider")) null else row.getString("provider"), provider?.wireName)
            val model = if (row.isNull("model")) null else row.getString("model")
            val effort = if (row.isNull("effort")) null else row.getString("effort")
            assertEquals(row.toString(), row.getString("expected"), provider?.apply(command, model, effort) ?: command)
        }
    }

    @Test fun explicitEffortWithDefaultDoesNotTurnDefaultMetadataIntoAModelFlag() {
        val params = TaskCommand.parameters(TaskCommand.Agent.CODEX, "Fix 'quotes'\nAnd Unicode 中", "/tmp/a b", UUID(1,2),
            effortId = "high")
        assertEquals("codex -c model_reasoning_effort='high' -- \"\$CMUX_TASK_PROMPT\"", params.getString("initial_command"))
        assertEquals("Fix 'quotes'\nAnd Unicode 中", params.getJSONObject("initial_env").getString("CMUX_TASK_PROMPT"))
        assertEquals("/tmp/a b", params.getString("working_directory"))
        val shell = TaskCommand.parameters(TaskCommand.Agent.SHELL, "", null, UUID(1,2), "ignored", "ignored")
        assertFalse(shell.has("initial_command")); assertFalse(shell.has("initial_env"))
    }

    @Test fun hostMetadataIsSeparateAndInvalidDuplicateChoicesAreRejected() {
        val source = JSONObject("""{"source":"discovered","models":[],"default_model":{
          "id":"auto","display_name":"Automatic","default_effort_id":"missing",
          "efforts":[{"id":"high","display_name":"High","description":"Think longer"}]}}""")
        val result = TaskModelParser.host(source)
        assertTrue(result.models.isEmpty()); assertEquals("auto", result.defaultModel!!.id)
        assertNull(result.defaultModel.defaultEffortId)
        val duplicate = JSONObject("""{"source":"discovered","models":[
          {"id":"same","display_name":"One"},{"id":"same","display_name":"Two"}]}""")
        assertTrue(runCatching { TaskModelParser.host(duplicate) }.isFailure)
        source.put("error", "new_unknown_error")
        assertTrue(runCatching { TaskModelParser.host(source) }.isFailure)
        val repeatedEffort = JSONObject("""{"source":"discovered","models":[{"id":"a","display_name":"A",
          "efforts":[{"id":"x","display_name":"X"},{"id":"x","display_name":"Again"}]}]}""")
        assertTrue(runCatching { TaskModelParser.host(repeatedEffort) }.isFailure)
    }

    @Test fun catalogTrimsSkipsDuplicatesAndResolvesDefaultOnlyFromOfferedModels() {
        val input = JSONObject("""{"schemaVersion":1,"providers":{"codex":{"defaultModel":" a ","models":[
          {"id":" a ","label":" A ","defaultEffort":" high ","efforts":[
            {"value":" high ","label":" High ","description":" Details "},
            {"value":"high","label":"Duplicate"},{"value":"","label":"Empty"}]},
          {"id":"a","label":"Duplicate"},{"id":"","label":"Empty"}]}}}""")
        val result = TaskModelParser.catalog(input, TaskAgentCommand.CODEX)
        assertEquals(listOf(TaskModel("a", "A", listOf(TaskEffort("high","High","Details")), "high")), result.models)
        assertEquals(result.models.single(), result.defaultModel)
        assertTrue(runCatching { TaskModelParser.catalog(input, TaskAgentCommand.CLAUDE) }.isFailure)
        input.put("schemaVersion", 2)
        assertTrue(runCatching { TaskModelParser.catalog(input, TaskAgentCommand.CODEX) }.isFailure)
    }

    @Test fun hostWinsAfterColdBackendAndExistingHostSurvivesTransientFailure() = runBlocking {
        val repo = TaskModelRepository(); val gate = CompletableDeferred<TaskModelResult>()
        val seen = mutableListOf<TaskModelResult>()
        val job = async { repo.refresh(key, { gate.await() }, { backend }, seen::add) }
        while (seen.isEmpty()) yield()
        assertEquals(backend, seen.single())
        gate.complete(host); assertFalse(job.await())
        assertEquals(host, seen.last()); assertEquals(host, repo.cached(key))
        assertTrue(repo.refresh(key, { error("offline") }, { error("offline") }, seen::add))
        assertEquals(host, repo.cached(key))
        assertTrue(repo.refresh(key, { error("offline") }, { backend }, seen::add))
        assertEquals(host, repo.cached(key))
    }

    @Test fun defaultOnlyDiscoveredHostCancelsSlowCatalog() = runBlocking {
        val repo = TaskModelRepository(); val started = CompletableDeferred<Unit>(); var cancelled = false
        val result = host.copy(models = emptyList())
        assertFalse(repo.refresh(key, { started.await(); result }, {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }, {}))
        assertTrue(cancelled); assertEquals(result, repo.cached(key))
    }

    @Test fun permanentProviderFailureRetainsCatalogInEitherCompletionOrderAndStopsRetry() = runBlocking {
        for (hostFirst in listOf(true, false)) {
            val repo = TaskModelRepository(); val gate = CompletableDeferred<Unit>()
            val failed = TaskModelResult(emptyList(), TaskModelSource.FALLBACK, error = TaskModelError.PROVIDER_UNAVAILABLE)
            assertFalse(repo.refresh(key, {
                if (hostFirst) gate.complete(Unit) else gate.await()
                failed
            }, {
                if (!hostFirst) gate.complete(Unit) else gate.await()
                backend
            }, {}))
            assertEquals(backend.copy(error = TaskModelError.PROVIDER_UNAVAILABLE), repo.cached(key))
        }
    }

    @Test fun requestTimeoutRetriesButOwnerCancellationDoesNotPublish() = runBlocking {
        val repo = TaskModelRepository()
        assertTrue(repo.refresh(key, { withTimeout(1) { awaitCancellation() } }, { backend }, {}))
        assertEquals(backend, repo.cached(key))
        val started = CompletableDeferred<Unit>(); val seen = mutableListOf<TaskModelResult>()
        val refresh = launch { repo.refresh(key, { started.complete(Unit); awaitCancellation() }, { awaitCancellation() }, seen::add) }
        started.await(); refresh.cancelAndJoin()
        assertTrue(seen.isEmpty())
        assertEquals(listOf(500L,1000L,2000L,4000L,8000L,15000L,15000L), (0..6).map(TaskModelRepository::retryDelay))
    }

    @Test fun forgottenClearedAndSupersededOwnersCannotReceiveOldResults() = runBlocking {
        for (action in listOf("forget", "clear", "supersede")) {
            val repo = TaskModelRepository(); val gate = CompletableDeferred<TaskModelResult>(); val started = CompletableDeferred<Unit>()
            val seen = mutableListOf<TaskModelResult>()
            val old = async { repo.refresh(key, { started.complete(Unit); gate.await() }, { awaitCancellation() }, seen::add) }
            started.await()
            when (action) {
                "forget" -> repo.retainOrigins(emptySet())
                "clear" -> repo.clear()
                else -> repo.refresh(key, { host.copy(models = listOf(model.copy(id="new"))) }, { awaitCancellation() }, {})
            }
            gate.complete(host); old.await()
            assertTrue(action, seen.isEmpty())
            if (action == "supersede") assertEquals("new", repo.cached(key)!!.models.single().id)
            else assertNull(repo.cached(key))
        }
        val repo = TaskModelRepository()
        repo.refresh(key, { host }, { awaitCancellation() }, {})
        assertNull(repo.cached(key.copy(origin="mac-two")))
        assertNull(repo.cached(key.copy(provider=TaskAgentCommand.CLAUDE)))
    }

    @Test fun presentedModelSurvivesDelistingAndEffortReconcilesOnlyAgainstItsModel() {
        val explicit = TaskModelSelection().choose(model, host).copy(effortId="low")
        val replaced = host.copy(models=listOf(model.copy(id="other")))
        assertEquals(model, explicit.model(replaced))
        assertEquals("low", explicit.reconcile(replaced).effortId)
        val changed = host.copy(models=listOf(model.copy(efforts=listOf(deep))))
        assertEquals("high", explicit.reconcile(changed).effortId)
        val default = explicit.choose(null, host)
        assertNull(default.explicit); assertEquals("high", default.effortId)
    }
}
