package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeMacAppearanceTest {
    private val mac = NativeMacIdentity("mac", "default")

    @Test fun admittedAppearanceKeepsItsOwnTeamWithoutOverwritingSameDevicePickerRows() {
        val team = NativeTeamScope("login", "user", "a", 1)
        val foreground = NativeCredentialStore.PairedMac("shared", "ABCDEFAB-0123-4567-89AB-0123456789AB", "Original A", "default", "user", "a", "origin-a")
        val picker = foreground.copy(name = "Original B", accountTeamId = "b", stableOrigin = "origin-b")
        val selected = NativeMacAppearances(mapOf(NativeMacIdentity("abcdefab-0123-4567-89ab-0123456789ab", "default") to NativeMacAppearance("Picker B", "palette:2", "server.rack")))
        val original = NativeMacAppearances(mapOf(NativeMacIdentity("abcdefab-0123-4567-89ab-0123456789ab", "default") to NativeMacAppearance("Foreground A", "palette:5", "terminal")))
        val merged = selected.withForeground(team, foreground, original)
        assertEquals("Foreground A", merged.name(foreground)); assertEquals("palette:5", merged.get(foreground).color)
        assertEquals("terminal", merged.get(foreground).icon)
        assertEquals("Picker B", merged.name(picker)); assertEquals("palette:2", merged.get(picker).color)
        assertEquals("Picker B", merged.get("abcdefab-0123-4567-89ab-0123456789ab", "default").name)
        assertEquals(selected.values, merged.values)
    }
    @Test fun unreadableForegroundAppearanceUsesItsHostNameWithoutBorrowingAnotherTeamsAlias() {
        val team = NativeTeamScope("login", "user", "a", 1)
        val foreground = NativeCredentialStore.PairedMac("route", "mac", "Original A", "default", "user", "a")
        val selected = NativeMacAppearances(mapOf(mac to NativeMacAppearance(name = "Picker B")))
        val merged = selected.withForeground(team, foreground, NativeMacAppearances(error = true))
        assertEquals("Original A", merged.name(foreground)); assertNull(merged.get(foreground).color)
        assertFalse(merged.error); assertEquals("Picker B", merged.get("mac", "default").name)
    }
    @Test fun missingOrMismatchedOwnerCannotOverlayForegroundAppearance() {
        val team = NativeTeamScope("login", "user", "a", 1)
        val row = NativeCredentialStore.PairedMac("route", "mac", "Original", "default", "user", "a")
        val selected = NativeMacAppearances(mapOf(mac to NativeMacAppearance(name = "Selected")))
        val original = NativeMacAppearances(mapOf(mac to NativeMacAppearance(name = "Original override")))
        for ((owner, record) in listOf(null to row, team to null, team.copy(userId = "other") to row,
            team.copy(teamId = "other") to row, team to row.copy(accountUserId = null)))
            assertSame(selected, selected.withForeground(owner, record, original))
    }

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

    @Test fun colorAssignmentDistinguishesBuildsAndDeduplicatesSavedDiscoveryRows() {
        val saved = listOf(NativeCredentialStore.PairedMac("a", "a", "A", "default"),
            NativeCredentialStore.PairedMac("b", "a", "A debug", "debug"))
        val discovered = listOf(IrohV2Computer("r", "e", "a", "default", "A", emptyList()),
            IrohV2Computer("r2", "e2", "b", "default", "B", emptyList()))
        val colors = NativeMacColorSlots().select("login", null, saved, discovered)
        assertEquals(3, colors.size)
        assertNotEquals(colors[saved[0].colorIdentity], colors[saved[1].colorIdentity])
        assertEquals(colors[saved[0].colorIdentity], colors[discovered[0].colorIdentity])
        assertEquals(colors, NativeMacColorSlots().select("login", null, saved.reversed(), discovered.reversed()))
        assertEquals(NativeMacAppearance.paletteSlot("same Mac"), NativeMacAppearance.paletteSlot("same Mac"))
        assertTrue(NativeMacAppearance.paletteSlot("🖥️".repeat(100)) in 0..7)
    }

    @Test fun discoveryInsertionsDisappearancesAndReturnsNeverRecolorExistingInstances() {
        val owner = NativeMacColorSlots()
        val first = NativeCredentialStore.PairedMac("route-z", "z", "Z", "default")
        val earlier = NativeCredentialStore.PairedMac("route-a", "a", "A", "default")
        val original = owner.select("login", null, listOf(first), emptyList())
        val added = owner.select("login", null, listOf(earlier, first), emptyList())
        assertEquals(original[first.colorIdentity], added[first.colorIdentity])
        assertNotEquals(added[first.colorIdentity], added[earlier.colorIdentity])
        assertEquals(added, owner.select("login", null, emptyList(), emptyList()))
        assertEquals(added, owner.select("login", null, listOf(first), emptyList()))
        assertEquals(1, original.size) // Published snapshots are immutable.
        val many = owner.select("login", null, (1..12).map {
            NativeCredentialStore.PairedMac("route-$it", "mac-$it", "Mac $it", "nightly")
        }, emptyList())
        assertEquals(14, many.values.distinct().size) // Only rendering wraps to the eight-color palette.
        assertEquals(original[first.colorIdentity], many[first.colorIdentity])
    }

    @Test fun colorScopeSurvivesRefreshButResetsOnTeamUserLoginAndSignOut() {
        val owner = NativeMacColorSlots()
        val team = NativeTeamScope("login", "user", "team", 1)
        val z = NativeCredentialStore.PairedMac("z", "z", "Z", "default")
        val a = NativeCredentialStore.PairedMac("a", "a", "A", "default")
        owner.select("login", team, listOf(z), emptyList())
        val colors = owner.select("login", team.copy(generation = 2), listOf(a), emptyList())
        assertEquals(0, colors[z.colorIdentity]); assertEquals(1, colors[a.colorIdentity])
        for (scope in listOf(team.copy(teamId = "other"), team.copy(userId = "other"), team.copy(login = "new-login"))) {
            val fresh = owner.select(scope.login, scope, listOf(a), emptyList())
            assertEquals(mapOf(a.colorIdentity to 0), fresh)
            owner.select(scope.login, scope, listOf(z), emptyList())
        }
        assertTrue(owner.select(null, null, listOf(z), emptyList()).isEmpty())
        assertEquals(mapOf(z.colorIdentity to 0), owner.select("login", team, listOf(z), emptyList()))
        assertTrue(owner.select("different", team, listOf(a), emptyList()).isEmpty())
        owner.clear()
        assertEquals(mapOf(a.colorIdentity to 0), owner.select("login", team, listOf(a), emptyList()))
    }

    @Test fun colorIdentityNormalizesUuidAndTagWithoutCollapsingLegacyOrNamedBuilds() {
        val uuid = "123E4567-E89B-12D3-A456-426614174000"
        assertEquals(nativeMacColorIdentity(uuid, " nightly "), nativeMacColorIdentity(uuid.lowercase(), "nightly"))
        assertEquals(nativeMacColorIdentity(uuid, null), nativeMacColorIdentity(uuid, "  "))
        assertNotEquals(nativeMacColorIdentity(uuid, null), nativeMacColorIdentity(uuid, "default"))
        assertNotEquals(nativeMacColorIdentity(uuid, "nightly"), nativeMacColorIdentity(uuid, "Nightly"))
        assertEquals("host\u001fnightly", nativeMacColorIdentity("host", "nightly").colorSeed)
        assertEquals("host", nativeMacColorIdentity("host", null).colorSeed)
    }

    @Test fun teamTransitionRetainsOnlyAStillVisibleForegroundInstance() {
        val owner = NativeMacColorSlots()
        val team = NativeTeamScope("login", "user", "team", 1)
        val a = NativeCredentialStore.PairedMac("a", "a", "A", "default")
        val z = NativeCredentialStore.PairedMac("z", "z", "Z", "nightly")
        val first = owner.select("login", team, listOf(a, z), emptyList())
        val retained = owner.select("login", team.copy(teamId = "other"), listOf(z), emptyList(), z.colorIdentity)
        assertEquals(mapOf(z.colorIdentity to first[z.colorIdentity]), retained)
        val added = owner.select("login", team.copy(teamId = "other"), listOf(a, z), emptyList(), z.colorIdentity)
        assertEquals(2, added[a.colorIdentity]); assertEquals(first[z.colorIdentity], added[z.colorIdentity])
        val removed = owner.select("login", team.copy(teamId = "third"), listOf(a), emptyList(), z.colorIdentity)
        assertEquals(mapOf(a.colorIdentity to 0), removed)
        val newUser = owner.select("login", team.copy(userId = "other"), listOf(z), emptyList(), z.colorIdentity)
        assertEquals(mapOf(z.colorIdentity to 0), newUser)
    }
}
