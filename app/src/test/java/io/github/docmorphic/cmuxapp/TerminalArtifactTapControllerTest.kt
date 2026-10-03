package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalArtifactTapControllerTest {
    @Test fun enabledDefaultOpensImmediatelyWithoutStat() = runBlocking {
        val controller = TerminalArtifactTapController(this)
        var opened = false
        controller.tap("./folder", true, { fail("Default must not stat"); ArtifactKind.DIRECTORY }, { true }, { opened = true }, { fail("Must open") })
        assertTrue(opened); controller.close()
    }
    @Test fun disabledPreferenceClassifiesFoldersFilesRefusalsAndTransportFailures() = runBlocking {
        val cases: List<Pair<suspend (String) -> ArtifactKind, Boolean>> = listOf(
            Pair({ ArtifactKind.DIRECTORY }, false), Pair({ ArtifactKind.TEXT }, true),
            Pair({ throw MobileRpcException("forbidden", "Not in scope") }, true),
            Pair({ throw MobileRpcException(" FORBIDDEN ", "Not in scope") }, true),
            Pair({ throw MobileRpcException("permission_denied", "Cannot read") }, false),
            Pair({ throw CancellationException("Stat cancelled") }, false),
            Pair({ throw java.io.IOException("Disconnected") }, false))
        for ((stat, expected) in cases) {
            val controller = TerminalArtifactTapController(this)
            val result = CompletableDeferred<Boolean>()
            controller.tap("./path", false, stat, { true }, { result.complete(true) }, { click -> assertTrue(click); result.complete(false) })
            assertEquals(expected, withTimeout(2_000) { result.await() }); controller.close()
        }
    }
    @Test fun deadlineFocusesWithoutWaitingForUncooperativeStat() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val focused = CompletableDeferred<Unit>()
        val controller = TerminalArtifactTapController(this, 20)
        try {
            controller.tap("./folder", false, {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; ArtifactKind.TEXT
            }, { true }, { fail("Expired classification must not open") }, { assertTrue(it); focused.complete(Unit) })
            entered.await(); withTimeout(2_000) { focused.await() }
            assertFalse(release.isCompleted)
        } finally { controller.close(); release.complete(Unit) }
    }
    @Test fun newerTapAndCloseDiscardLateClassification() = runBlocking {
        for (close in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val controller = TerminalArtifactTapController(this)
            var callbacks = 0
            controller.tap("old.txt", false, {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; ArtifactKind.TEXT
            }, { true }, { callbacks++ }, { callbacks++ })
            entered.await()
            if (close) controller.close() else controller.tap("new.txt", true, { ArtifactKind.TEXT }, { true }, { callbacks += 10 }, { fail() })
            release.complete(Unit); yield(); yield()
            assertEquals(if (close) 0 else 10, callbacks); controller.close()
        }
    }
    @Test fun changedContentCannotOpenOrClickOldCell() = runBlocking {
        for (kind in listOf(ArtifactKind.TEXT, ArtifactKind.DIRECTORY)) {
            val controller = TerminalArtifactTapController(this)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val focused = CompletableDeferred<Boolean>()
            var opened = false
            val job = controller.tap("old.txt", false, { entered.complete(Unit); release.await(); kind }, { false }, { opened = true }, { focused.complete(it) })
            entered.await(); release.complete(Unit); job!!.join()
            if (kind == ArtifactKind.DIRECTORY) assertFalse(withTimeout(2_000) { focused.await() })
            assertFalse(opened); controller.close()
        }
    }
}
