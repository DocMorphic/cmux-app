package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class CloudCreateJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = CloudAccountScope("login", "user", "team", 1)

    @Test fun retriesSurviveReopeningAndNormalizeOptionsButStayAccountAndTeamScoped() = runBlocking {
        val root = temporary.newFolder()
        val options = CloudMachineCreateOptions(provider = " provider ")
        val first = CloudCreateFileJournal(root, owner).resolve(options)
        assertEquals(first, CloudCreateFileJournal(root, owner.copy(generation = 2)).resolve(options.normalized()))
        for (other in listOf(owner.copy(login = "replacement"), owner.copy(user = "another"), owner.copy(team = "other")))
            assertNotEquals(first, CloudCreateFileJournal(root, other).resolve(options))
        assertEquals(4, root.list()!!.size)
    }
    @Test fun completedCreationRetiresItsKeyButStaleCompletionCannotDeleteNewerIntent() = runBlocking {
        val store = CloudCreateFileJournal(temporary.newFolder(), owner)
        val options = CloudMachineCreateOptions()
        val old = store.resolve(options)
        val newer = store.resolve(options.copy(kind = CloudMachineKind.DESKTOP))
        assertNotEquals(old, newer)
        store.complete(old)
        assertEquals(newer, store.resolve(options.copy(kind = CloudMachineKind.DESKTOP)))
        store.complete(newer)
        assertNotEquals(newer, store.resolve(options.copy(kind = CloudMachineKind.DESKTOP)))
    }
    @Test fun failedAtomicWriteKeepsOldRetryIdentityAndCleansTemporaryFiles() = runBlocking {
        val root = temporary.newFolder()
        val options = CloudMachineCreateOptions()
        val key = CloudCreateFileJournal(root, owner).resolve(options)
        val failing = CloudCreateFileJournal(root, owner) { _, _ -> throw IOException("Full disk") }
        assertTrue(runCatching { failing.resolve(options.copy(memoryMb = 4096)) }.isFailure)
        assertEquals(key, CloudCreateFileJournal(root, owner).resolve(options))
        assertEquals(1, root.list()!!.size)
    }
    @Test fun corruptRecordCannotBecomeAFreshRequest() = runBlocking {
        val root = temporary.newFolder()
        val store = CloudCreateFileJournal(root, owner)
        store.resolve(CloudMachineCreateOptions())
        root.listFiles()!!.single().writeText("broken")
        assertTrue(runCatching { store.resolve(CloudMachineCreateOptions()) }.isFailure)
        assertEquals("broken", root.listFiles()!!.single().readText())
    }
}
