package io.github.docmorphic.cmuxapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

internal data class ArtifactPlaybackBookmark(val position: Int, val play: Boolean, val speed: Float,
    val muted: Boolean, val audio: String, val captions: String) {
    fun applyTo(state: ArtifactMediaState) {
        state.position = position; state.playRequested = play; state.speed = speed
        state.muted = muted; state.audioPreference = audio; state.captionPreference = captions
    }
    fun result() = Intent().putExtra("position", position).putExtra("speed", speed).putExtra("muted", muted)
        .putExtra("audio", audio).putExtra("captions", captions)
    companion object {
        fun capture(state: ArtifactMediaState): ArtifactPlaybackBookmark {
            state.capture()
            return ArtifactPlaybackBookmark(state.position, state.playRequested, state.speed, state.muted,
                state.audioPreference, state.captionPreference)
        }
        fun result(intent: Intent) = ArtifactPlaybackBookmark(intent.getIntExtra("position", 0).coerceAtLeast(0), false,
            ArtifactMediaControls.speed(intent.getFloatExtra("speed", 1f)), intent.getBooleanExtra("muted", false),
            intent.getStringExtra("audio") ?: ArtifactMediaTracks.AUTO, intent.getStringExtra("captions") ?: ArtifactMediaTracks.AUTO)
    }
}

/** Pending handoffs are process-local capabilities, never filenames or credentials in Intents. */
internal object ArtifactPlaybackSessions {
    const val EXTRA = "cmux.media.playback"
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = mutableMapOf<String, Entry>()
    fun recover(context: Context) { cleanup.launch { runCatching { ArtifactExportCache.prune(File(context.cacheDir, "media-playback")) } } }
    class Entry(val files: ArtifactPlaybackFiles, val bookmark: ArtifactPlaybackBookmark, val account: String) : AutoCloseable {
        override fun close() { cleanup.launch { files.close() } }
    }
    suspend fun prepare(context: Context, source: File, bookmark: ArtifactPlaybackBookmark): Pair<String, Intent> = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, "media-playback")
        ArtifactExportCache.prune(root)
        val owner = account(context)
        val files = ArtifactPlaybackFiles(root, source, checkActive = { ensureActive() })
        try {
            ensureActive()
            check(account(context) == owner) { "Media owner changed" }
            val id = UUID.randomUUID().toString()
            synchronized(pending) { pending[id] = Entry(files, bookmark, owner) }
            cleanup.launch { delay(30_000); cancel(id) }
            val process = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName() else
                context.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                    ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName.orEmpty()
            val activity = if (process.endsWith(":browser")) BrowserMediaPlaybackActivity::class.java else MediaPlaybackActivity::class.java
            id to Intent(context, activity).putExtra(EXTRA, id)
        } catch (failure: Throwable) { files.close(); throw failure }
    }
    fun claim(id: String?): Entry? = synchronized(pending) { pending.remove(id) }
    fun cancel(id: String) { claim(id)?.close() }
    /** Provider executes in the account-owning process even when the viewer is in :browser. */
    fun account(context: Context): String = checkNotNull(context.contentResolver.call(
        Uri.parse("content://${context.packageName}.media-playback-account"), "owner", null, null)?.getString("owner"))
}
