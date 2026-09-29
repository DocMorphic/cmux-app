package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One account incarnation owns these wires. UI/feed/service callers only receive leases. */
internal class MobileRpcConnections : AutoCloseable {
    private class Entry(val client: MobileRpcClient, var references: Int = 0)
    private val lock = Any()
    // Admission is serialized; normal RPC traffic and revocation never wait on this mutex.
    private val admission = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private var candidate: Pair<String, MobileRpcClient>? = null
    private var closed = false

    /** Inspect an already-live Mac without making opening a settings page dial it. */
    fun borrowIfConnected(key: String, permits: () -> Boolean): MobileRpcClient? = synchronized(lock) {
        if (closed || !permits()) return@synchronized null
        val entry = entries[key]?.takeUnless { it.client.isClosed } ?: return@synchronized null
        borrow(key, entry)
    }

    suspend fun acquire(key: String, permits: () -> Boolean,
                        create: () -> MobileRpcClient): MobileRpcClient = admission.withLock {
        synchronized(lock) {
            check(!closed && permits()) { "Computer access changed" }
            entries[key]?.let { existing ->
                if (!existing.client.isClosed) return@withLock borrow(key, existing)
                entries.remove(key)
            }
            check(entries.size < 64) { "Too many active Mac connections" }
        }
        val client = create()
        try {
            synchronized(lock) {
                check(!closed && permits()) { "Computer access changed" }
                candidate = key to client
            }
            client.connect()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                check(!closed && permits() && candidate?.second === client && !client.isClosed) { "Computer access changed" }
                val entry = Entry(client)
                entries[key] = entry
                candidate = null
                borrow(key, entry)
            }
        } catch (error: Throwable) {
            synchronized(lock) { if (candidate?.second === client) candidate = null }
            client.close()
            throw error
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

    /** Revocation also aborts a candidate currently waiting for admission. */
    fun retain(allowed: Set<String>) {
        val removed = synchronized(lock) {
            entries.keys.filter { it !in allowed }.map { entries.remove(it)!!.client } +
                listOfNotNull(candidate?.takeIf { it.first !in allowed }?.second.also {
                    if (it != null) candidate = null
                })
        }
        removed.forEach { it.retire() }
    }

    override fun close() {
        val all = synchronized(lock) {
            if (closed) return
            closed = true
            (entries.values.map { it.client } + listOfNotNull(candidate?.second)).also {
                entries.clear(); candidate = null
            }
        }
        all.forEach { it.retire() }
    }
}
