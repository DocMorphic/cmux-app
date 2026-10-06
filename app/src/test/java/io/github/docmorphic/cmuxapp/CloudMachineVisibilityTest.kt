package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudMachineVisibilityTest {
    private val owner = CloudAccountScope("login", "user", "team", 1)
    private val saved = mutableMapOf<String, Set<String>>()
    private fun store(scope: CloudAccountScope = owner, current: () -> Boolean = { true }) =
        CloudMachineVisibility(scope, { saved[it].orEmpty() }, { key, ids -> saved[key] = ids }, current)
    @Test fun visibilitySurvivesReopeningLoginAndGenerationButIsIsolatedAcrossUsersAndTeams() {
        val first = store(); first.reconcile(setOf("a", "b"), true)
        assertTrue(first.setHidden("a", true)); first.close()
        val reopened = store(owner.copy(login = "new-login", generation = 99))
        assertEquals(setOf("a"), reopened.hidden.value)
        assertTrue(store(owner.copy(user = "another")).hidden.value.isEmpty())
        assertTrue(store(owner.copy(team = "other-team")).hidden.value.isEmpty())
        assertTrue(store(owner.copy(team = null)).hidden.value.isEmpty())
        assertEquals(1, saved.size)
    }
    @Test fun onlySuccessfulInventoriesPruneDeletedMachinesAndNewMachinesAreVisibleByDefault() {
        val first = store(); first.reconcile(setOf("a", "b"), true); first.setHidden("a", true); first.setHidden("b", true); first.close()
        val restored = store()
        restored.reconcile(emptySet(), false); assertEquals(setOf("a", "b"), restored.hidden.value)
        restored.reconcile(setOf("a"), false); assertEquals(setOf("a", "b"), restored.hidden.value)
        restored.reconcile(setOf("a", "new"), true); assertEquals(setOf("a"), restored.hidden.value)
        assertEquals(setOf("a"), store().hidden.value)
        restored.reconcile(setOf("a", "b", "new"), true)
        assertFalse("b" in restored.hidden.value)
    }
    @Test fun showAgainIsPersistedAndUnknownOrRetiredCallbacksCannotHideAnotherAccountsMachine() {
        var current = true
        val first = store(current = { current }); first.reconcile(setOf("a"), true)
        assertFalse(first.setHidden("missing", true)); assertTrue(saved.isEmpty())
        first.setHidden("a", true); assertTrue(first.setHidden("a", false)); assertTrue(store().hidden.value.isEmpty())
        current = false; assertFalse(first.setHidden("a", true))
        first.reconcile(emptySet(), true); assertTrue(first.hidden.value.isEmpty())
        current = true; first.close(); assertFalse(first.setHidden("a", true))
    }
    @Test fun failedPersistenceRetainsPublishedVisibilityAndCatalogReconciliationCanRecover() {
        var fail = false
        val first = CloudMachineVisibility(owner, { emptySet() }, { key, ids -> if (fail) throw IOException("full"); saved[key] = ids }, { true })
        first.reconcile(setOf("a", "b"), true); first.setHidden("a", true)
        fail = true
        assertThrows(IOException::class.java) { first.setHidden("b", true) }
        assertEquals(setOf("a"), first.hidden.value); assertNotNull(first.failure.value)
        // Pruning failure is observable but must not kill the machine/tunnel observer.
        first.reconcile(emptySet(), true)
        assertEquals(setOf("a"), first.hidden.value)
        fail = false; first.reconcile(emptySet(), true)
        assertTrue(first.hidden.value.isEmpty()); assertNull(first.failure.value)
    }
    @Test fun independentlyEncodedOwnerFieldsDoNotAliasAndUnchangedChoicesDoNotRewrite() {
        var writes = 0
        val first = CloudMachineVisibility(owner.copy(user = "a|b", team = "c"), { saved[it].orEmpty() },
            { key, ids -> writes++; saved[key] = ids }, { true })
        first.reconcile(setOf("a"), true); first.setHidden("a", true); first.setHidden("a", true); first.reconcile(setOf("a"), true)
        assertEquals(1, writes)
        assertTrue(store(owner.copy(user = "a", team = "b|c")).hidden.value.isEmpty())
    }
}
