package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One account incarnation owns these wires. UI/feed/service callers only receive leases. */
internal class MobileRpcConnections : AutoCloseable {
    private class Entry(val client: MobileRpcClient, var references: Int = 0)
    private class Admission(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val lock = Any()
    // Serialize callers for one Mac without making other Macs wait for its dial/probe.
    // Reservations also count toward capacity before a transport has been created.
    private val admissions = mutableMapOf<String, Admission>()
    private val entries = mutableMapOf<String, Entry>()
    private val candidates = mutableMapOf<String, MobileRpcClient>()
    private var closed = false

    /** Inspect an already-live Mac without making opening a settings page dial it. */
    fun borrowIfConnected(key: String, permits: () -> Boolean): MobileRpcClient? = synchronized(lock) {
        if (closed || !permits()) return@synchronized null
        val entry = entries[key]?.takeUnless { it.client.isClosed } ?: return@synchronized null
        borrow(key, entry)
    }

    suspend fun acquire(key: String, permits: () -> Boolean,
                        validate: suspend (MobileRpcClient) -> Unit = {},
                        create: () -> MobileRpcClient): MobileRpcClient {
        val admission = synchronized(lock) {
            check(!closed && permits()) { "Computer access changed" }
            admissions.getOrPut(key) {
                check(key in entries || (entries.keys + admissions.keys).size < 64) { "Too many active Mac connections" }
                Admission()
            }.also { it.users++ }
        }
        try {
            return admission.mutex.withLock {
                synchronized(lock) {
                    check(!closed && permits()) { "Computer access changed" }
                    entries[key]?.let { existing ->
                        if (!existing.client.isClosed) return@withLock borrow(key, existing)
                        entries.remove(key)
                    }
                }
                val client = create()
                try {
                    synchronized(lock) {
                        check(!closed && permits()) { "Computer access changed" }
                        candidates[key] = client
                    }
                    client.connect()
                    validate(client)
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        check(!closed && permits() && candidates[key] === client && !client.isClosed) { "Computer access changed" }
                        val entry = Entry(client)
                        entries[key] = entry
                        candidates.remove(key)
                        borrow(key, entry)
                    }
                } catch (error: Throwable) {
                    synchronized(lock) { if (candidates[key] === client) candidates.remove(key) }
                    client.close()
                    throw error
                }
            }
        } finally {
            synchronized(lock) {
                admission.users--
                if (admission.users == 0 && admissions[key] === admission) admissions.remove(key)
            }
        }
    }

    private fun borrow(key: String, entry: Entry): MobileRpcClient {
        val lease = entry.client.lease {
            val retire = synchronized(lock) {
                entry.references--
                if (entry.references == 0 && entries[key] === entry) {
                    entries.remove(key)
                    true
                } else false
            }
            if (retire) entry.client.close()
        }
        entry.references++
        return lease
    }

    /** Revocation also aborts all disallowed candidates currently being admitted. */
    fun retain(allowed: Set<String>) {
        val removed = synchronized(lock) {
            entries.keys.filter { it !in allowed }.map { entries.remove(it)!!.client } +
                candidates.keys.filter { it !in allowed }.map { candidates.remove(it)!! }
        }
        removed.forEach { it.retire() }
    }

    override fun close() {
        val all = synchronized(lock) {
            if (closed) return
            closed = true
            (entries.values.map { it.client } + candidates.values).also {
                entries.clear(); candidates.clear()
            }
        }
        all.forEach { it.retire() }
    }
}
