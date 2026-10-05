package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** Reclaims abandoned bytes without expiring a user's resumable save or active UI. */
internal class FileSaveMaintenance(private val files: FileSaveFiles) {
    suspend fun prune(now: Long): Int = withContext(Dispatchers.IO) {
        var removed = 0
        for (entry in files.entries()) {
            ensureActive()
            if (!eligible(entry, now)) continue
            val lease = files.claimUi(entry.id) ?: continue
            lease.use {
                files.withWriter(entry) {
                    val latest = files.load(entry.id) ?: return@withWriter
                    if (!eligible(latest, now)) return@withWriter
                    if (latest.phase == FileSavePhase.PREPARING)
                        files.finish(latest, FileSavePhase.CANCELLED)
                    else files.remove(latest)
                    removed++
                }
            }
        }
        removed
    }
    private fun eligible(value: FileSaveSnapshot, now: Long): Boolean {
        if (value.ownsGrant) return false
        val modified = files.updatedAt(value)
        if (modified <= 0 || now < modified) return false
        val age = now - modified
        return value.phase in FileSaveFiles.terminal && age >= RECEIPT_AGE ||
            value.phase == FileSavePhase.PREPARING && !files.hasSeal(value) && age >= INCOMPLETE_AGE
    }
    companion object {
        const val INCOMPLETE_AGE = 24 * 60 * 60 * 1000L
        const val RECEIPT_AGE = 7 * INCOMPLETE_AGE
    }
}
