package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

class TerminalArtifactCountTest {
    private fun fixture() = JSONObject(GZIPInputStream(javaClass.getResourceAsStream("/artifacts/ios-count-paths.json.gz")!!).bufferedReader().readText())
    private fun request(r: TerminalArtifactCount.Request?): Any = r?.let { JSONObject().put("state", it.stateGeneration).put("generation", it.surfaceGeneration).put("count", it.localCount) } ?: JSONObject.NULL
    private fun report(r: TerminalArtifactCount.Report?): Any = r?.let { JSONObject().put("count", it.count).put("generation", it.surfaceGeneration) } ?: JSONObject.NULL
    private fun canonical(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().associateWith { canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
        is Number -> value.toString()
        JSONObject.NULL -> null
        else -> value
    }

    @Test fun countPolicyMatches6400UnmodifiedSwiftTransitions() {
        val scenarios = fixture().getJSONArray("scenarios")
        for (scenario in 0 until scenarios.length()) {
            val state = TerminalArtifactCount(); var active: TerminalArtifactCount.Request? = null
            val steps = scenarios.getJSONArray(scenario)
            for (index in 0 until steps.length()) {
                val step = steps.getJSONObject(index)
                val actual = when (step.getString("op")) {
                    "reset" -> { state.reset(); JSONObject().put("kind", "reset") }
                    "trigger" -> {
                        val result = state.trigger(step.getInt("count"), step.getLong("generation"), step.getBoolean("supported"))
                        if (result.request != null) active = result.request
                        JSONObject().put("kind", "trigger").put("report", report(result.report)).put("request", request(result.request)).put("authoritative", result.authoritative)
                    }
                    else -> {
                        val result = state.complete(active ?: TerminalArtifactCount.Request(-1, 0, 0),
                            (step.opt("gallery") as? Number)?.toInt(), (step.opt("sessionTotal") as? Number)?.toInt(), step.opt("session") as? String,
                            step.getBoolean("succeeded"), step.getLong("generation"), step.getInt("count"))
                        active = result.next
                        JSONObject().put("kind", result.outcome.name).put("report", report(result.report)).put("next", request(result.next))
                    }
                }
                assertEquals("Scenario $scenario step $index", canonical(step.getJSONObject("expected")), canonical(actual))
            }
        }
    }
    @Test fun localDetectorMatchesUnmodifiedSwiftEscapeAndPathCases() {
        val cases = fixture().getJSONArray("paths")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val actual = JSONArray(TerminalArtifactPaths.paths(case.getString("text")))
            assertEquals("Path case $index", canonical(case.getJSONArray("expected")), canonical(actual))
        }
    }
    @Test fun remotePosixPathsDoNotUseTheDevelopmentHostsFilesystemRules() {
        for (root in listOf("/", "//", "/..", "/../../", "/a//b/../../")) {
            assertNull(root, TerminalArtifactPaths.normalized(root))
        }
        // Colons are legal in Mac filenames; parent components stay intact for the host.
        for (path in listOf("/tmp/a:abc", "/a/../b", "/../../b", "//tmp///file")) {
            assertEquals(path, TerminalArtifactPaths.normalized(path))
        }
    }
    @Test fun visibilityKeepsTheLastCountDuringGraceAndPositiveEvidenceCancelsHide() {
        val state = TerminalArtifactVisibility()
        assertEquals(TerminalArtifactVisibility.Action.NONE, state.update(0, true))
        assertEquals(TerminalArtifactVisibility.Action.MOUNT, state.update(2, true))
        assertEquals(TerminalArtifactVisibility.Action.SCHEDULE_HIDE, state.update(0, true))
        assertEquals(TerminalArtifactVisibility.Action.NONE, state.update(0, true))
        assertEquals(TerminalArtifactVisibility.Action.MOUNT, state.update(2, true))
        state.hideCompleted()
        assertEquals(TerminalArtifactVisibility.Action.NONE, state.update(2, true))
        assertEquals(TerminalArtifactVisibility.Action.HIDE, state.update(2, false))
        assertEquals(TerminalArtifactVisibility.Action.NONE, state.update(0, false))
    }
    @Test fun authoritativeTotalSurvivesFailureAndOnlyAcceptedReportsRefreshGallery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>(); var calls = 0; var reject = false
        val rpc = ArtifactRpc(ArtifactCapabilities(true, true, false, false)) { method, params ->
            assertEquals("mobile.terminal.artifact.scan", method); assertTrue(params.getBoolean("count_only")); assertFalse(params.getBoolean("include_missing"))
            if (++calls == 1) release.await()
            if (reject) error("temporary failure")
            JSONObject().put("session_id", "session").put("gallery_row_total", 13)
        }
        val controller = TerminalArtifactController(scope, rpc, ArtifactAuthorization.Terminal("ws", "surface"), false)
        try {
            controller.observe("/one"); assertEquals(1, controller.count.value); assertEquals(0, controller.galleryRefresh.value)
            controller.observe("/one /two"); assertEquals(2, controller.count.value); assertEquals(0, controller.galleryRefresh.value)
            release.complete(Unit)
            withTimeout(1000) { controller.galleryRefresh.first { it > 0 } }
            assertEquals(13, controller.count.value); assertEquals(2, calls)
            reject = true; controller.observe("/three"); assertEquals(13, controller.count.value)
        } finally { release.complete(Unit); controller.close(); scope.cancel() }
    }
    @Test fun graceExpiresButSamePositiveCountRemountsAndDisabledConnectionClosesImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rpc = ArtifactRpc(ArtifactCapabilities(true, false, false, false)) { _, _ -> error("Legacy count is local") }
        val controller = TerminalArtifactController(scope, rpc, ArtifactAuthorization.Terminal("ws", "surface"), false, hideDelayMillis = 40)
        try {
            controller.observe("/one"); controller.observe(""); assertEquals(1, controller.count.value)
            controller.observe("/one"); delay(60); assertEquals(1, controller.count.value)
            controller.observe(""); withTimeout(1000) { controller.count.first { it == null } }
            controller.observe("/one"); assertEquals(1, controller.count.value)
            controller.close(); assertNull(controller.count.value)
        } finally { controller.close(); scope.cancel() }
    }
    @Test fun closingTerminalRejectsNonCooperativeLateCountResponse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val rpc = ArtifactRpc(ArtifactCapabilities(true, true, false, false)) { _, _ ->
            entered.complete(Unit); withContext(NonCancellable) { release.await() }; JSONObject().put("gallery_row_total", 99)
        }
        val controller = TerminalArtifactController(scope, rpc, ArtifactAuthorization.Terminal("ws", "surface"), false)
        try {
            controller.observe("/one"); entered.await(); controller.close(); release.complete(Unit)
            assertNull(controller.count.value); assertEquals(0, controller.galleryRefresh.value)
        } finally { release.complete(Unit); controller.close(); scope.cancel() }
    }
}
