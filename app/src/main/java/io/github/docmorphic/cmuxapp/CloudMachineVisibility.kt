/* Local visibility follows CloudSessionController at cmux c2715faa.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.security.MessageDigest

/** Preferences belong to the server/user/team, surviving login and team-generation changes. */
internal class CloudMachineVisibility(owner: CloudAccountScope,
    read: (String) -> Set<String>, private val write: (String, Set<String>) -> Unit,
    private val current: () -> Boolean) : AutoCloseable {
    private val key = "hidden." + MessageDigest.getInstance("SHA-256")
        .digest(JSONArray(listOf("https://cmux.com", owner.user, owner.team)).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private var closed = false
    private var machines = emptySet<String>()
    private val mutable = MutableStateFlow(read(key).toSet())
    val hidden = mutable.asStateFlow()
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    fun reconcile(ids: Set<String>, authoritative: Boolean) {
        if (closed || !current()) return
        machines = ids.toSet()
        // Initial/loading/failed lists cannot erase choices for machines not loaded yet.
        if (authoritative) try { save(mutable.value.intersect(machines)) } catch (_: Exception) { /* Keep the catalog observer alive; failure is published. */ }
    }
    fun setHidden(machineId: String, hidden: Boolean): Boolean {
        if (closed || !current() || machineId !in machines) return false
        save(if (hidden) mutable.value + machineId else mutable.value - machineId)
        return true
    }
    private fun save(ids: Set<String>) {
        if (ids == mutable.value) return
        try { write(key, ids.toSet()) }
        catch (failure: Exception) {
            mutableFailure.value = "Could not save Cloud computer visibility. Try again."
            throw failure
        }
        mutable.value = ids.toSet()
        mutableFailure.value = null
    }
    override fun close() { closed = true; machines = emptySet(); mutable.value = emptySet(); mutableFailure.value = null }
}
