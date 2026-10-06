package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class NativeNoticeFeedRuntimeTest {
    @Test fun configuredPublicFeedPersistsAndFreshViewModelRecoversWhenFetchFails() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "notice-feed-runtime-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val owners = mutableListOf<ViewModelStore>()
        fun create(failFetch: Boolean): NativeWhatsNewViewModel {
            val store = ViewModelStore().also { owners += it }
            lateinit var model: NativeWhatsNewViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        val feed = NativeWhatsNewFeed() // Actual production endpoint and cache identity.
                        @Suppress("UNCHECKED_CAST")
                        return NativeWhatsNewViewModel(context, feed,
                            if (failFetch) ({ throw IOException("Fixture network unavailable") }) else feed::fetch) as T
                    }
                })[NativeWhatsNewViewModel::class.java]
            }
            return model
        }
        try {
            val live = create(false)
            val liveCenter = withTimeout(20_000) { live.center.filterNotNull().first() }
            val received = withTimeout(20_000) { liveCenter.state.first { it.initialRefreshComplete } }
            assertTrue("Public Android feed must actually load: ${received.error}", received.fetchedThisLaunch)
            assertNull(received.error)
            assertTrue(received.archive.isNotEmpty())
            val ledger = File(directory, "whats-new/notices-v1.json")
            assertTrue(ledger.isFile)
            val bytes = ledger.readBytes()
            val feed = NativeWhatsNewFeed()
            val cache = NativeWhatsNewFileStore(ledger.parentFile!!)
                .read("whatsNew.remote.v1.${feed.cacheIdentity}")
            feed.close()
            assertNotNull("The configured feed must persist under its exact endpoint identity", cache)
            assertTrue(WhatsNewRemote.decode(cache!!).visibleEntryIds.isNotEmpty())
            instrumentation.runOnMainSync { owners.first().clear() }

            val offline = create(true)
            val recoveredCenter = withTimeout(10_000) { offline.center.filterNotNull().first() }
            val recovered = withTimeout(10_000) { recoveredCenter.state.first { it.initialRefreshComplete } }
            assertFalse(recovered.fetchedThisLaunch)
            assertEquals(NativeWhatsNewCenter.REFRESH_ERROR, recovered.error)
            assertEquals(received.archive.filter { it.body !is WhatsNewBody.Web },
                recovered.archive.filter { it.body !is WhatsNewBody.Web })
            assertArrayEquals("A failed refresh must not replace the persisted generation", bytes, ledger.readBytes())
            assertNull(NativeWhatsNewFileStore(ledger.parentFile!!).read(NativeWhatsNewCenter.MARKER))
        } finally {
            instrumentation.runOnMainSync { owners.forEach { it.clear() } }
            directory.deleteRecursively()
        }
    }
}
