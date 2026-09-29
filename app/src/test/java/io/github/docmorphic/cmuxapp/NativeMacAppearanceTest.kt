package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeMacAppearanceTest {
    private val mac = NativeMacIdentity("mac", "default")

    @Test fun independentFieldEditsPersistAndBlankNameRestoresHostName() {
        var disk: String? = null
        val store = NativeMacAppearanceStore({ disk }, { disk = it })
        store.update(mac, { true }) { it.copy(name = "  Studio Mac  ") }
        store.update(mac, { true }) { it.copy(color = "#abc123") }
        store.update(mac, { true }) { it.copy(icon = "🧑🏽‍💻") }
        val expected = NativeMacAppearance("Studio Mac", "#ABC123", "🧑🏽‍💻")
        assertEquals(expected, store.state.value.values[mac])
        val restored = NativeMacAppearanceStore({ disk }, { error("Read only") })
        assertEquals(expected, restored.state.value.values[mac])
        store.update(mac, { true }) { it.copy(name = "  ") }
        assertEquals("Original hostname", store.state.value.values.getValue(mac).displayName("Original hostname"))
        assertEquals("#ABC123", store.state.value.values.getValue(mac).color)
        store.update(mac, { true }) { NativeMacAppearance() }
        assertTrue(store.state.value.values.isEmpty()); assertEquals("[]", disk)
    }

    @Test fun deviceAndBuildAreExactAndNullTagIsNotAnAlias() {
        var disk: String? = null
        val store = NativeMacAppearanceStore({ disk }, { disk = it })
        val keys = listOf(mac, mac.copy(buildTag = "debug"), mac.copy(buildTag = null), mac.copy(deviceId = "other"))
        keys.forEachIndexed { index, key -> store.update(key, { true }) { it.copy(name = "Mac $index") } }
        keys.forEachIndexed { index, key -> assertEquals("Mac $index", store.state.value.get(key.deviceId, key.buildTag).name) }
        store.update(mac, { true }) { NativeMacAppearance() }
        assertEquals(3, store.state.value.values.size)
        assertNull(store.state.value.get("MAC", "default").name)
    }

    @Test fun retiredScopeAndDiskFailureDoNotPublishUnsavedAppearance() {
        var disk: String? = null
        var writes = 0
        var fail = false
        val store = NativeMacAppearanceStore({ disk }, { if (fail) error("Disk full"); writes++; disk = it })
        store.update(mac, { true }) { it.copy(name = "Before") }
        assertTrue(runCatching { store.update(mac, { false }) { it.copy(name = "Other account") } }.isFailure)
        var permits = true
        assertTrue(runCatching { store.update(mac, { permits }) { permits = false; it.copy(name = "Late") } }.isFailure)
        fail = true
        assertTrue(runCatching { store.update(mac, { true }) { it.copy(name = "Unsaved") } }.isFailure)
        assertEquals(1, writes); assertEquals("Before", store.state.value.values.getValue(mac).name)
    }

    @Test fun malformedFilesSurfaceReadErrorAndCannotBeSilentlyOverwritten() {
        for (initial in listOf("not json", "[{\"deviceId\":\"mac\",\"color\":\"#bad\"}]",
            "[{\"deviceId\":\"mac\"},{\"deviceId\":\"mac\"}]", "[{\"deviceId\":7}]")) {
            var disk = initial
            val store = NativeMacAppearanceStore({ disk }, { error("Must not overwrite") })
            assertTrue(initial, store.state.value.error)
            assertTrue(runCatching { store.update(mac, { true }) { it.copy(name = "New") } }.isFailure)
            disk = "[]"; store.reload(); assertFalse(store.state.value.error)
        }
    }

    @Test fun inputValidationAllowsPaletteRgbSymbolsAndEmojiAndRejectsMalformedValues() {
        for (index in 0..7) assertEquals("palette:$index", NativeMacAppearance.color("palette:$index"))
        assertEquals("#00ABFF", NativeMacAppearance.color("#00abff"))
        for (icon in NativeMacAppearance.symbols + NativeMacAppearance.emojis + listOf("🏳️‍🌈", "👨‍👩‍👧‍👦"))
            assertEquals(icon, NativeMacAppearance.icon(icon))
        for (color in listOf("palette:8", "palette:-1", "#123", "#000000ff", "rgb(0,0,0)", "#GG0000"))
            assertTrue(color, runCatching { NativeMacAppearance.color(color) }.isFailure)
        for (icon in listOf("not-a-symbol", "🚀\n", "🚀".repeat(33))) {
            if (icon.endsWith("\n")) assertEquals("🚀", NativeMacAppearance.icon(icon))
            else assertTrue(icon, runCatching { NativeMacAppearance.icon(icon) }.isFailure)
        }
        assertTrue(runCatching { NativeMacAppearance.name("bad\nname") }.isFailure)
        assertTrue(runCatching { NativeMacAppearance.name("x".repeat(257)) }.isFailure)
    }

    @Test fun storageScopeIncludesApplicationProjectUserAndTeamWithUnambiguousEncoding() {
        val baseline = NativeMacAppearanceStore.scopeFile("app", "project", "user", "team")
        val variants = listOf(
            NativeMacAppearanceStore.scopeFile("app.debug", "project", "user", "team"),
            NativeMacAppearanceStore.scopeFile("app", "other-project", "user", "team"),
            NativeMacAppearanceStore.scopeFile("app", "project", "other-user", "team"),
            NativeMacAppearanceStore.scopeFile("app", "project", "user", "other-team"))
        assertFalse(baseline.contains("user")); assertEquals(69, baseline.length)
        assertTrue(variants.all { it != baseline }); assertEquals(4, variants.distinct().size)
        assertNotEquals(NativeMacAppearanceStore.scopeFile("a", "b", "c:d", "e"),
            NativeMacAppearanceStore.scopeFile("a", "b", "c", "d:e"))
    }

    @Test fun displayProjectionKeepsOriginalPairingAndNotificationDestination() {
        val paired = NativeCredentialStore.PairedMac("route", "mac", "Server name", "default")
        val workspace = NativeWorkspace("w", "Work", listOf(NativeTerminal("s", "shell")), null, false, null,
            null, false, emptyList(), null, null, null)
        val source = NativeFeedSource(paired, listOf(NativeNotification("n", "w", "s", "Ready", "Done", false)), listOf(workspace))
        val appearances = NativeMacAppearances(mapOf(mac to NativeMacAppearance("Studio")))
        val original = aggregateNativeFeed(listOf(source)).single()
        val decorated = aggregateNativeFeed(listOf(source), computerName = appearances::name).single()
        assertEquals("Studio", decorated.computer)
        assertEquals(original.id, decorated.id); assertSame(source, decorated.source); assertSame(paired, decorated.source.mac)
        assertEquals("Server name", paired.name)
        assertEquals(original.notification.destination(source.workspaces), decorated.notification.destination(source.workspaces))
        assertTrue(decorated.searchFields().any { it == "Studio" })
    }

    @Test fun colorAssignmentIsSharedAcrossBuildsAndStableAcrossSourceOrder() {
        val saved = listOf(NativeCredentialStore.PairedMac("a", "a", "A", "default"),
            NativeCredentialStore.PairedMac("b", "a", "A debug", "debug"))
        val discovered = listOf(IrohV2Computer("r", "e", "b", "default", "B", emptyList()))
        assertEquals(mapOf("a" to 0, "b" to 1), nativeMacColorIndices(saved, discovered))
        assertEquals(nativeMacColorIndices(saved, discovered), nativeMacColorIndices(saved.reversed(), discovered))
        assertEquals(NativeMacAppearance.paletteSlot("same Mac"), NativeMacAppearance.paletteSlot("same Mac"))
        assertTrue(NativeMacAppearance.paletteSlot("🖥️".repeat(100)) in 0..7)
    }
}
