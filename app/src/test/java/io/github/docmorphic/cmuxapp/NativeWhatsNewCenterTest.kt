package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWhatsNewCenterTest {
    private class Memory : WhatsNewStorage {
        val values = mutableMapOf<String, String>()
        var fail = false
        override fun read(key: String) = values[key]
        override fun write(updates: Map<String, String?>): Boolean {
            if (fail) return false
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
    }
    private val feature = WhatsNewBody.Features(listOf(WhatsNewFeature("Title", "Detail")))
    private fun page(id: String, required: Boolean = false, channels: List<String>? = null,
                     min: String? = null, max: String? = null) =
        WhatsNewPage(id, id, feature, channels = channels, requiredPairing = required, minVersion = min, maxVersion = max)
    private val catalog = listOf(page("new"), page("pairing", required = true), page("old"))
    private fun center(store: Memory = Memory(), pages: List<WhatsNewPage> = catalog,
                       channel: WhatsNewChannel = WhatsNewChannel.BETA, origin: String? = "https://cmux.com",
                       languages: List<String> = listOf("en")) =
        NativeWhatsNewCenter(pages, "0.2.0", channel, store, origin, languages)
    private fun remote(ids: List<String>, announcements: List<JSONObject> = emptyList(), channels: JSONObject? = null): String =
        JSONObject().put("visibleEntryIds", JSONArray(ids)).put("announcements", JSONArray(announcements))
            .apply { if (channels != null) put("entryChannels", channels) }.toString()
    private fun notice(id: String) = JSONObject().put("id", id).put("minVersion", "0.2")
        .put("maxVersion", "0.2.0").put("title", "Notice $id").put("features", JSONArray().put(
            JSONObject().put("title", "Feature $id").put("detail", "Detail $id")))
    private fun NativeWhatsNewCenter.ids() = state.value.archive.map { it.id }

    @Test fun undeclaredChannelsOnlyPermitDevelopmentLanes() {
        for (channel in WhatsNewChannel.entries) {
            val permitted = channel in listOf(WhatsNewChannel.DEV, WhatsNewChannel.BETA, WhatsNewChannel.INTERNAL)
            assertEquals(permitted, channel.permits(null))
            assertFalse(channel.permits(emptyList()))
            assertFalse(channel.permits(listOf("future", channel.token.uppercase())))
            assertTrue(channel.permits(listOf(channel.token)))
            assertEquals(if (permitted) 3 else 0, center(channel = channel).ids().size)
        }
    }
    @Test fun dottedNumericComparisonIsInclusiveWithMissingAndInvalidComponentsAsZero() {
        assertEquals(0, WhatsNewVersion.compare("0.2", "0.2.0"))
        assertTrue(WhatsNewVersion.compare("0.10", "0.2") > 0)
        assertTrue(WhatsNewVersion.within("0.2", "0.2.0", "0.2.0"))
        assertFalse(WhatsNewVersion.within("0.2.1", "0.2", "0.2.0"))
        assertEquals(0, WhatsNewVersion.compare("1.bad.2", "1.0.2"))
        assertEquals(0, WhatsNewVersion.compare("1.9223372036854775808", "1.0"))
        assertEquals(listOf("yes"), center(pages = listOf(page("future", min = "0.3"), page("old", max = "0.1"), page("yes", min = "0.2", max = "0.2.0"))).ids())
    }
    @Test fun emptyRetractsEverythingButWhollyRetiredListFallsBack() = runTest {
        val c = center(); c.refresh { remote(emptyList()) }; assertEquals(emptyList<String>(), c.ids())
        c.refresh { remote(listOf("retired")) }; assertEquals(listOf("new", "pairing", "old"), c.ids())
        c.refresh { remote(listOf("old")) }; assertEquals(listOf("pairing", "old"), c.ids())
    }
    @Test fun requiredPairingStillObeysVersionAndChannelAndRemoteOverrideReplacesCompiled() = runTest {
        val c = center(pages = listOf(page("future", required = true, min = "0.3"), page("pairing", required = true), page("new", channels = emptyList())))
        c.refresh { remote(listOf("new"), channels = JSONObject().put("new", JSONArray(listOf("beta"))).put("pairing", JSONArray())) }
        assertEquals(listOf("new"), c.ids())
        val prod = center(channel = WhatsNewChannel.PROD)
        prod.refresh { remote(listOf("new"), channels = JSONObject().put("new", JSONArray(listOf("prod")))) }
        assertEquals(listOf("new"), prod.ids())
    }
    @Test fun failedRefreshKeepsCacheAndNeverFetchedFailureUsesCatalog() = runTest {
        val store = Memory(); val first = center(store)
        first.refresh { remote(listOf("old")) }
        val restarted = center(store)
        restarted.refresh { error("offline") }
        assertEquals(listOf("pairing", "old"), restarted.ids())
        assertTrue(restarted.state.value.initialRefreshComplete)
        assertFalse(restarted.state.value.fetchedThisLaunch)
        assertNotNull(restarted.state.value.error)
        val fresh = center(); fresh.refresh { error("offline") }
        assertEquals(3, fresh.ids().size)
    }
    @Test fun cacheIsScopedToSchemeHostAndPortNotPath() = runTest {
        val store = Memory(); center(store, origin = "https://CMUX.com/api").refresh { remote(emptyList()) }
        assertEquals(0, center(store, origin = "https://cmux.com/other").ids().size)
        for (origin in listOf("http://cmux.com", "https://cmux.com:8443", "https://staging.cmux.com", null))
            assertEquals(3, center(store, origin = origin).ids().size)
    }
    @Test fun malformedVisibilityCannotRetractOrPruneButMalformedAnnouncementIsIsolated() = runTest {
        val store = Memory(); val c = center(store)
        c.refresh { remote(listOf("old"), listOf(notice("valid"), notice("bad").apply { remove("maxVersion") }, notice("wrong").put("title", 42))) }
        assertEquals(listOf("valid", "pairing", "old"), c.ids())
        c.acknowledgeAppearance(c.state.value.archive)
        val saved = store.values.toMap()
        c.refresh { "{\"visibleEntryIds\":[12],\"announcements\":[]}" }
        assertEquals(saved, store.values)
        assertEquals(listOf("valid", "pairing", "old"), c.ids())
    }
    @Test fun nativeDuplicatesAreDroppedAndHiddenNativeBodyNeverResurrects() = runTest {
        val c = center()
        val reference = notice("announcement").put("nativeEntryId", "new")
        c.refresh { remote(listOf("new"), listOf(reference)) }; assertFalse("announcement" in c.ids())
        c.refresh { remote(listOf("old"), listOf(reference)) }
        val announcement = c.state.value.archive.first()
        assertEquals("announcement", announcement.id)
        assertEquals("Feature announcement", (announcement.body as WhatsNewBody.Features).rows.first().title)
        reference.remove("features")
        c.refresh { remote(listOf("old"), listOf(reference)) }; assertFalse("announcement" in c.ids())
    }
    @Test fun announcementNamespaceCannotCollideWithBinaryIdAndDuplicatesKeepFirst() = runTest {
        val c = center()
        c.refresh { remote(listOf("new"), listOf(notice("new"), notice("new").put("title", "second"))) }
        val matches = c.state.value.archive.filter { it.id == "new" }
        assertEquals(listOf("announcement:new", "entry:new"), matches.map { it.key })
        c.acknowledgeAppearance(listOf(matches.first()))
        assertTrue(c.state.value.unseen.any { it.key == "entry:new" })
        assertFalse(c.state.value.unseen.any { it.key == "announcement:new" })
    }
    @Test fun localizationOnlyChangesContentWithEnglishFallback() = runTest {
        val translations = JSONObject().put("de", JSONObject().put("title", "Neu").put("releaseLabel", "Android")
            .put("features", JSONArray().put(JSONObject().put("title", "Titel").put("detail", "Text"))))
            .put("en", JSONObject().put("title", "News").put("features", JSONArray()))
        for ((languages, title) in listOf(listOf("de-DE") to "Neu", listOf("fr") to "News")) {
            val c = center(languages = languages)
            c.refresh { remote(emptyList(), listOf(notice("localized").put("localizations", translations).put("webUrl", "https://cmux.com/news"))) }
            assertEquals("localized", c.state.value.archive.single().id)
            assertEquals(title, c.state.value.archive.single().title)
        }
    }
    @Test fun webPolicyRejectsSpoofedHostsCredentialsAndPlaintextExceptConfiguredLoopback() {
        val policy = WhatsNewWebPolicy("https://staging.cmux.com")
        for (url in listOf("https://cmux.com/a", "https://www.cmux.com", "https://STAGING.cmux.com/a")) assertTrue(url, policy.allows(url))
        for (url in listOf("http://cmux.com", "https://cmux.com.evil.test", "https://cmux.com@evil.test", "https://u:p@cmux.com", "javascript:alert(1)", "file:///tmp/a", "http://localhost", "https://cmux.com\\@evil.test")) assertFalse(url, policy.allows(url))
        assertTrue(WhatsNewWebPolicy("http://localhost:8000").allows("http://localhost:8000/news"))
        assertFalse(WhatsNewWebPolicy("http://localhost:8000").allows("http://127.0.0.1/news"))
    }
    @Test fun webIsPreferredWhenAllowedOtherwiseInlineFallbackAndOfflineSkipsLaunchOnly() = runTest {
        val store = Memory(); val c = center(store)
        c.refresh { remote(emptyList(), listOf(notice("web").put("webUrl", "https://cmux.com/news"), notice("rows").put("webUrl", "http://cmux.com/news"))) }
        assertTrue(c.state.value.archive[0].body is WhatsNewBody.Web)
        assertTrue(c.state.value.archive[1].body is WhatsNewBody.Features)
        assertEquals(listOf("web", "rows"), c.state.value.unseen.map { it.id })
        val offline = center(store); offline.refresh { error("offline") }
        assertEquals(listOf("web", "rows"), offline.ids())
        assertEquals(listOf("rows"), offline.state.value.unseen.map { it.id })
    }
    @Test fun initialAttemptGateAndCancellationDoNotAcknowledgeOrComplete() = runTest {
        val store = Memory(); val c = center(store)
        assertTrue(c.preparePresentation(c.state.value.unseen, emptySet()).isEmpty())
        val job = launch { c.refresh { awaitCancellation() } }; yield(); job.cancelAndJoin()
        assertFalse(c.state.value.initialRefreshComplete); assertTrue(store.values.isEmpty())
        c.refresh()
        assertEquals(3, c.preparePresentation(c.state.value.unseen, emptySet()).size)
        assertTrue(store.values.isEmpty())
    }
    @Test fun allShownPagesAcknowledgeOnAppearanceAndArchiveSurvivesRestart() = runTest {
        val store = Memory(); val c = center(store)
        c.refresh { remote(listOf("old"), listOf(notice("a"), notice("b"))) }
        val presented = c.preparePresentation(c.state.value.unseen, emptySet())
        assertEquals(4, c.state.value.unseen.size)
        assertTrue(c.acknowledgeAppearance(presented))
        assertTrue(c.state.value.unseen.isEmpty()); assertEquals(4, c.ids().size)
        assertEquals("pairing", store.values[NativeWhatsNewCenter.MARKER])
        assertEquals(4, center(store).ids().size); assertTrue(center(store).state.value.unseen.isEmpty())
    }
    @Test fun skippedVersionMarkerUsesFullCatalogAndNeverRegresses() = runTest {
        val store = Memory(); store.values[NativeWhatsNewCenter.MARKER] = "old"
        val c = center(store); c.refresh { remote(listOf("pairing")) }
        assertEquals(listOf("pairing"), c.state.value.unseen.map { it.id })
        c.acknowledgeAppearance(listOf(catalog.first()))
        c.acknowledgeAppearance(listOf(catalog.last()))
        assertEquals("new", store.values[NativeWhatsNewCenter.MARKER])
        assertTrue(c.state.value.unseen.isEmpty())
    }
    @Test fun unknownMarkerOnDowngradeStaysQuietWithoutSuppressingAnnouncements() = runTest {
        val store = Memory(); store.values[NativeWhatsNewCenter.MARKER] = "from-newer-binary"
        val c = center(store); c.refresh { remote(listOf("new"), listOf(notice("a"))) }
        assertEquals(listOf("a"), c.state.value.unseen.map { it.id })
        c.acknowledgeAppearance(catalog)
        assertEquals("from-newer-binary", store.values[NativeWhatsNewCenter.MARKER])
    }
    @Test fun announcementPruningRequiresSuccessfulAuthoritativeFetch() = runTest {
        val store = Memory(); val c = center(store)
        c.refresh { remote(emptyList(), listOf(notice("a"), notice("b"))) }
        c.acknowledgeAppearance(c.state.value.archive)
        c.refresh { error("offline") }
        assertEquals("[\"a\",\"b\"]", store.values[NativeWhatsNewCenter.ANNOUNCEMENTS])
        c.refresh { remote(emptyList(), listOf(notice("b"))) }
        assertEquals("[\"b\"]", store.values[NativeWhatsNewCenter.ANNOUNCEMENTS])
    }
    @Test fun failedStorageDoesNotLoseSeenStateOrReplaceCache() = runTest {
        val store = Memory(); val c = center(store); c.refresh { remote(listOf("old")) }
        val before = store.values.toMap(); store.fail = true
        assertFalse(c.acknowledgeAppearance(c.state.value.archive))
        assertEquals(before, store.values); assertEquals(2, c.state.value.unseen.size)
        assertEquals(NativeWhatsNewCenter.SAVE_ERROR, c.state.value.error)
        c.refresh { remote(emptyList()) }
        assertEquals(before, store.values); assertEquals(listOf("pairing", "old"), c.ids())
    }
    @Test fun lateRefreshCannotOverwriteNewerResultAndClosedOwnerCannotPublish() = runTest {
        val c = center(); val late = CompletableDeferred<String>()
        val job = launch { c.refresh { late.await() } }; yield()
        c.refresh { remote(emptyList()) }
        late.complete(remote(listOf("new"))); job.join()
        assertTrue(c.ids().isEmpty())
        val pending = CompletableDeferred<String>()
        val other = launch { c.refresh { pending.await() } }; yield(); c.close()
        pending.complete(remote(listOf("new"))); other.join()
        assertTrue(c.ids().isEmpty()); c.refresh { error("must not run") }
    }
    @Test fun retractionDuringPreloadDropsPagesAndWebContentChangeRequiresNewLoad() = runTest {
        val c = center()
        c.refresh { remote(listOf("new"), listOf(notice("web").put("webUrl", "https://cmux.com/one"))) }
        val staged = c.state.value.unseen.toList()
        assertFalse(c.preparePresentation(staged, emptySet()).any { it.body is WhatsNewBody.Web })
        c.refresh { remote(emptyList(), listOf(notice("web").put("webUrl", "https://cmux.com/two"))) }
        assertTrue(c.preparePresentation(staged, setOf("announcement:web")).isEmpty())
        assertEquals(3, staged.size) // Frozen input still includes old web, new and required pairing.
        val current = c.state.value.unseen
        assertEquals(current, c.preparePresentation(current, setOf("announcement:web")))
    }
    @Test fun corruptCacheFallsBackAndAndroidCatalogIdsAreUniqueAndVersioned() {
        val store = Memory(); store.values["whatsNew.remote.v1.https://cmux.com:default"] = "bad"
        assertEquals(3, center(store).ids().size)
        assertEquals(NativeWhatsNewCatalog.pages.size, NativeWhatsNewCatalog.pages.map { it.id }.distinct().size)
        assertTrue(NativeWhatsNewCatalog.pages.all { it.id.startsWith("android.") && it.minVersion == "0.2.0" })
    }
}
