package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeTicketPairingRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val owner = NativeTeamScope("fixture-login", "fixture-user", "fixture-team", 1)
    private val peer = "a".repeat(64)
    private val json = """{"version":1,"workspaceID":"work","terminalID":"term","macDeviceID":"fixture-mac",
        "macDisplayName":"Fixture Mac","macUserID":"fixture-user","auth_token":"synthetic-runtime-token",
        "routes":[{"id":"ts","kind":"tailscale","endpoint":{"type":"host_port","host":"100.64.0.7","port":58465}},
        {"id":"iroh","kind":"iroh","endpoint":{"type":"peer","id":"$peer"}}]}"""
    private fun ticket() = MobileAttachTicketCodec.decodeJson(json).getOrThrow()

    @Test fun inAppChooserOffersExactTailscaleOnlyAndWaitsForConnect() {
        val session = NativeTicketPairing(); session.propose(ticket(), owner, null, NativePairingEntry.IN_APP)
        val proposal = session.pending.value!!
        var shown by mutableStateOf(true)
        var chosen: NativeTicketPairingRoutes.Choice? = null
        compose.setContent { CmuxTheme { if (shown) NativeTicketPairingConfirmation(proposal,
            onDismiss = { shown = false }, onConnect = { chosen = it; shown = false }) } }
        compose.onNodeWithText("Fixture Mac").assertIsDisplayed()
        compose.onNodeWithText("100.64.0.7:58465").assertIsDisplayed()
        compose.onNodeWithText("synthetic-runtime-token").assertDoesNotExist()
        compose.runOnIdle { assertNull(chosen) }
        compose.onNodeWithText("Native connection").assertDoesNotExist()
        compose.onNodeWithText("cmux will send your account session to this Mac over Tailscale. Continue only if this address came from your Mac.").assertIsDisplayed()
        capture("ticket-tailscale-confirmation")
        compose.onNodeWithTag("ticket.connect").performClick()
        compose.runOnIdle { assertTrue(chosen?.pairing is PairingCode.Tailscale) }
        compose.onNodeWithText("Connect to this Mac?").assertDoesNotExist()
    }

    @Test fun externalMixedTicketCannotOfferTailscaleConfirmation() {
        val session = NativeTicketPairing(); session.propose(ticket(), owner, null, NativePairingEntry.EXTERNAL_LINK)
        val proposal = session.pending.value!!
        var chosen: NativeTicketPairingRoutes.Choice? = null
        compose.setContent { CmuxTheme { NativeTicketPairingConfirmation(proposal,
            onDismiss = { session.dismiss() }, onConnect = { chosen = it }) } }
        compose.onNodeWithText("100.64.0.7:58465").assertDoesNotExist()
        compose.onNodeWithText("Native connection").assertIsDisplayed()
        compose.onNodeWithText("cmux will verify this Mac in your account and selected team before connecting.").assertIsDisplayed()
        capture("ticket-native-confirmation")
        compose.runOnIdle { assertNull(chosen) }
        compose.onNodeWithTag("ticket.connect").performClick()
        compose.runOnIdle { assertTrue(chosen?.pairing is PairingCode.Iroh) }
    }

    @Test fun externalAuthorizedAddressShowsReuseAndRejectsRevokedConfirmation() {
        val state = org.json.JSONObject().put("task_session", owner.login).put("refresh_token", "synthetic-refresh")
        val grants = TailscaleGrantStore({ state }, { it(state) })
        val raw = PairingCode.Tailscale(listOf(PairingCode.Route("100.64.0.7", 58465)), owner.userId)
        val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), owner.userId, owner.teamId,
            TailscaleGrantStore.source(raw), "fixture-mac", "default", raw.routes.single())
        grants.save(owner, grant) { true }
        val target = NativeComputerTarget("fixture-mac", "default", "Fixture Mac")
        val settings = NativeMacConnectionPreferences(mapOf(NativeMacIdentity("fixture-mac", "default") to
            NativeMacConnectionPreference(NativeMacConnectionMethod.TAILSCALE)))
        val directory = NativeComputersState(owner, ready = true,
            computers = listOf(IrohV2Computer("record", peer, "fixture-mac", "default", "Fixture Mac", emptyList())))
        val routes = NativeExternalTicketRoutes(owner, grants, { emptyList() }, { directory }, { settings }, { true })
        val session = NativeTicketPairing(); session.propose(ticket(), owner, null, NativePairingEntry.EXTERNAL_LINK, routes)
        val proposal = session.pending.value!!
        var failure: Throwable? = null
        compose.setContent { CmuxTheme { NativeTicketPairingConfirmation(proposal, session::dismiss) { choice ->
            failure = runCatching { session.select(proposal, choice, owner, directory) }.exceptionOrNull()
        } } }
        compose.onNodeWithText("100.64.0.7:58465").assertIsDisplayed()
        compose.onNodeWithText("Native connection").assertDoesNotExist()
        compose.onNodeWithText("cmux will use this Mac’s previously authorized Tailscale address and saved connection settings.").assertIsDisplayed()
        capture("ticket-authorized-tailscale-confirmation")
        compose.runOnIdle { grants.removeRoute(owner, target, grant) { true } }
        compose.onNodeWithTag("ticket.connect").performClick()
        compose.runOnIdle { assertNotNull(failure); assertNull(session.resumeCode()) }
    }

    @Test fun cancelDoesNotInvokeConnect() {
        val session = NativeTicketPairing(); session.propose(ticket(), owner, null)
        val proposal = session.pending.value!!
        var shown by mutableStateOf(true)
        var connects = 0
        compose.setContent { CmuxTheme { if (shown) NativeTicketPairingConfirmation(proposal,
            onDismiss = { session.dismiss(); shown = false }, onConnect = { connects++ }) } }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, connects); assertNull(session.pending.value) }
    }

    @Test fun savedTextFieldStateDropsTicketDraftButRestoresPublicCode() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            var draft by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = NativePairingDraftSaver) { mutableStateOf("") }
            CmuxTheme { androidx.compose.material3.OutlinedTextField(draft, { draft = it },
                modifier = androidx.compose.ui.Modifier.testTag("ticket.draft")) }
        }
        val url = "cmux-ios://attach?v=1&payload=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        compose.onNodeWithTag("ticket.draft").performTextInput(url)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ticket.draft").assertTextEquals("")
        val public = "cmux-ios://attach?v=2&r=100.64.0.7:58465"
        compose.onNodeWithTag("ticket.draft").performTextInput(public)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ticket.draft").assertTextEquals(public)
    }

    @Test fun unconfirmedLaunchTicketSurvivesActivityRecreationOnlyInViewModelMemory() {
        val url = "cmux-ios://attach?v=1&payload=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        lateinit var original: NativeLaunchRoutesViewModel
        compose.activityRule.scenario.onActivity { activity ->
            original = ViewModelProvider(activity)[NativeLaunchRoutesViewModel::class.java]
            original.routes = NativeLaunchRoutes.incoming(url, null); original.initialized = true
            assertEquals(url, original.routes.pairing)
            assertNull(NativeLaunchRoutes.decode(original.routes.encode()).pairing)
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            val retained = ViewModelProvider(activity)[NativeLaunchRoutesViewModel::class.java]
            assertSame(original, retained); assertEquals(url, retained.routes.pairing)
            assertTrue(retained.initialized)
        }
    }

    @Test fun ticketContextRoundTripsThroughKeystoreAndForgetRemovesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ticket-runtime-${UUID.randomUUID()}"
        val store = NativeCredentialStore(context, name)
        try {
            val computer = IrohV2Computer("record", peer, "fixture-mac", "default", "Fixture Mac", emptyList())
            val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(computer, owner),
                computer.deviceId, computer.name, computer.buildTag), owner)
            store.update { it.put("task_session", owner.login).put("refresh_token", "synthetic-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(mac))) }
            val saved = store.rememberAttachTicket(owner, mac, ticket(), null) { true }
            val ciphertext = context.getSharedPreferences(name, 0).getString("state", null)!!
            assertFalse(ciphertext.contains("synthetic-runtime-token")); assertFalse(ciphertext.contains("workspace"))
            val reloaded = NativeCredentialStore(context, name)
            val read = reloaded.attachTicket(owner, reloaded.pairedMacs().single())!!
            assertEquals("work", read.workspaceId); assertEquals("term", read.terminalId)
            assertEquals("synthetic-runtime-token", read.tokenFor("workspace.list", org.json.JSONObject(), 0))
            reloaded.forgetMac(saved.code, owner) { true }
            assertTrue(reloaded.pairedMacs().isEmpty()); assertFalse(reloaded.load()!!.has(NativeAttachTicketStore.KEY))
        } finally { store.clear(); context.deleteSharedPreferences(name) }
    }

    @Test fun directoryEnrichmentPersistsWithoutRenewingTicketOrWritingAnUnchangedSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "directory-runtime-${UUID.randomUUID()}"
        val store = NativeCredentialStore(context, name)
        try {
            val public = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=fixture-user"
            val raw = PairingCodeParser.parse(public).getOrThrow() as PairingCode.Tailscale
            val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), owner.userId, owner.teamId,
                TailscaleGrantStore.source(raw), "fixture-mac", "default", raw.routes.single())
            store.update { it.put("task_session", owner.login).put("refresh_token", "synthetic-refresh") }
            TailscaleGrantStore(store::load, store::update).save(owner, grant) { true }
            val mac = store.rememberAuthenticatedMac(NativeCredentialStore.PairedMac(public, "fixture-mac", "Fixture Mac", "default"), owner) { true }
            val ticketRow = store.rememberAttachTicket(owner, mac, ticket(), null) { true }
            val computer = IrohV2Computer("record", peer, "fixture-mac", "default", "Fixture Mac", emptyList())
            val snapshot = NativeComputersState(owner, ready = true, computers = listOf(computer))
            assertFalse(store.retainNativeDirectory(owner, snapshot) { false })
            assertTrue(store.retainNativeDirectory(owner, snapshot) { true })
            val revision = store.revisions.value
            assertFalse(store.retainNativeDirectory(owner, snapshot) { true })
            assertEquals(revision, store.revisions.value)
            val reloaded = NativeCredentialStore(context, name)
            val upgraded = reloaded.pairedMacs().single()
            assertEquals(ticketRow.copy(nativeRouteCode = PairingCodeParser.computer(computer, owner)), upgraded)
            assertEquals("synthetic-runtime-token", reloaded.attachTicket(owner, upgraded)!!.tokenFor("workspace.list", org.json.JSONObject(), 0))
            assertFalse(reloaded.retainNativeDirectory(owner, snapshot.copy(computers = emptyList())) { true })
            assertEquals(upgraded, reloaded.pairedMacs().single())
        } finally { store.clear(); context.deleteSharedPreferences(name) }
    }

    @Test fun authenticatedLegacyBuildAdoptionSurvivesKeystoreReloadWithoutDuplicateComputerOrStaleTicket() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "legacy-adoption-${UUID.randomUUID()}"
        val store = NativeCredentialStore(context, name)
        try {
            val public = "cmux-ios://attach?v=3&i=$peer&d=fixture-mac&ub=fixture-user&t=fixture-team"
            val old = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(public, "fixture-mac", "Older Mac"), owner)
            store.update { it.put("task_session", owner.login).put("refresh_token", "synthetic-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(old))).put("computer_selection", old.origin) }
            val ticketRow = store.rememberAttachTicket(owner, old, ticket(), null) { true }
            val learned = store.rememberAuthenticatedMac(ticketRow.copy(instanceTag = "default", name = "Verified Mac"), owner,
                expected = ticketRow) { true }
            val restored = NativeCredentialStore(context, name)
            assertEquals(listOf(learned), restored.pairedMacs())
            assertEquals(old.origin, learned.origin); assertEquals("default", learned.instanceTag)
            assertEquals(old.origin, restored.load()!!.getString("computer_selection"))
            assertNull(learned.ticketRevision); assertNull(restored.attachTicket(owner, learned))
            assertNotNull(NativeComputerTarget.from(learned, owner))
            assertEquals(learned, restored.rememberAuthenticatedMac(learned, owner, expected = learned) { true })
        } finally { store.clear(); context.deleteSharedPreferences(name) }
    }

    @Test fun legacyAppearanceUpgradeResumesAfterKeystoreAcknowledgementFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "legacy-appearance-${UUID.randomUUID()}"
        val scopedOwner = owner.copy(teamId = name)
        val store = NativeCredentialStore(context, name)
        val file = android.util.AtomicFile(File(context.noBackupFilesDir, "computer-appearance/" +
            NativeMacAppearanceStore.scopeFile(context.packageName, NativeAccount.PROJECT_ID, scopedOwner.userId, scopedOwner.teamId)))
        val appearance = NativeMacAppearanceStore.create(context, scopedOwner)
        try {
            val public = "cmux-ios://attach?v=3&i=$peer&d=fixture-mac&ub=fixture-user&t=$name"
            val old = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(public, "fixture-mac", "Old Mac"), scopedOwner)
            store.update { it.put("task_session", scopedOwner.login).put("refresh_token", "synthetic-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(old))) }
            val custom = NativeMacAppearance("Studio", "palette:2", "🚀")
            appearance.update(NativeMacIdentity(old.deviceId, null), { true }) { custom }
            val learned = store.rememberAuthenticatedMac(old.copy(instanceTag = "default"), scopedOwner, old) { true }
            val restored = NativeCredentialStore(context, name)
            assertThrows(IllegalStateException::class.java) {
                NativePairingAppearanceUpgrades.reconcile(scopedOwner, restored::load, { error("Interrupted acknowledgement") }, appearance) { true }
            }
            appearance.reload()
            assertEquals(custom, appearance.state.value.get(learned))
            assertEquals(1, NativePairingAppearanceUpgrades.pending(restored.load(), scopedOwner).size)
            appearance.update(NativeMacIdentity(old.deviceId, "default"), { true }) { NativeMacAppearance() }
            NativePairingAppearanceUpgrades.reconcile(scopedOwner, restored::load, restored::update, appearance) { true }
            appearance.reload()
            assertEquals(NativeMacAppearance(), appearance.state.value.get(learned))
            assertEquals(NativeMacAppearance(), appearance.state.value.get(old))
            assertTrue(NativePairingAppearanceUpgrades.pending(NativeCredentialStore(context, name).load(), scopedOwner).isEmpty())
        } finally {
            store.clear(); context.deleteSharedPreferences(name); file.delete(); appearance.reload()
        }
    }

    @Test fun legacyRawBuildAndGrantCommitTogetherAndDiscardOldTicketAcrossKeystoreReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "legacy-raw-build-${UUID.randomUUID()}"
        val store = NativeCredentialStore(context, name)
        try {
            val public = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=fixture-user"
            val pairing = PairingCodeParser.parse(public).getOrThrow() as PairingCode.Tailscale
            val old = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(public, "fixture-mac", "Old Mac"), owner)
            val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), owner.userId, owner.teamId,
                TailscaleGrantStore.source(pairing), old.deviceId, null, pairing.routes.single())
            store.update { it.put("task_session", owner.login).put("refresh_token", "synthetic-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(old))).put("computer_selection", old.origin) }
            TailscaleGrantStore(store::load, store::update).save(owner, grant) { true }
            val ticketRow = store.rememberAttachTicket(owner, old, ticket(), null) { true }
            val learned = store.rememberAuthenticatedMac(old.copy(instanceTag = "default"), owner, expected = ticketRow) { true }
            val restored = NativeCredentialStore(context, name)
            assertEquals(listOf(learned), restored.pairedMacs()); assertEquals(old.origin, learned.origin)
            assertEquals(grant.copy(build = "default"), TailscaleGrantStore(restored::load, restored::update).find(owner, grant.source))
            assertNull(learned.ticketRevision); assertNull(restored.attachTicket(owner, learned))
            assertEquals(old.origin, restored.load()!!.getString("computer_selection"))
        } finally { store.clear(); context.deleteSharedPreferences(name) }
    }

    @Test fun backgroundBuildRefreshPreservesAnotherSelectedComputerAcrossKeystoreReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "background-build-${UUID.randomUUID()}"
        val store = NativeCredentialStore(context, name)
        try {
            val code = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=fixture-user"
            val pairing = PairingCodeParser.parse(code).getOrThrow() as PairingCode.Tailscale
            val old = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(code, "fixture-mac", "Mac"), owner)
            val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), owner.userId, owner.teamId,
                TailscaleGrantStore.source(pairing), old.deviceId, null, pairing.routes.single())
            store.update { it.put("task_session", owner.login).put("refresh_token", "synthetic-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(old)))
                .put("computer_selection", "other-origin").put("pairing_code", "other-computer") }
            TailscaleGrantStore(store::load, store::update).save(owner, grant) { true }
            store.refreshAuthenticatedMac(old.copy(instanceTag = "default"), owner, old) { true }
            val restored = NativeCredentialStore(context, name)
            assertEquals("default", restored.pairedMacs().single().instanceTag)
            assertEquals(old.origin, restored.pairedMacs().single().origin)
            assertEquals(grant.copy(build = "default"), TailscaleGrantStore(restored::load, restored::update).find(owner, grant.source))
            assertEquals("other-origin", restored.load()!!.getString("computer_selection"))
            assertEquals("other-computer", restored.load()!!.getString("pairing_code"))
        } finally { store.clear(); context.deleteSharedPreferences(name) }
    }

    @Test fun confirmedLegacyGrantAndUnscopedHistorySurviveAtomicKeystoreUpgrade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "confirmed-legacy-${UUID.randomUUID()}"
        val store = NativeCredentialStore(context, name)
        try {
            val code = "cmux-ios://attach?v=2&r=mac.tail.ts.net%3A58465&ub=fixture-user"
            val old = NativeCredentialStore.PairedMac(code, "fixture-mac", "Old Mac")
            val pairing = PairingCodeParser.parse(code).getOrThrow() as PairingCode.Tailscale
            val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), owner.userId, owner.teamId,
                TailscaleGrantStore.source(pairing), old.deviceId, null, PairingCode.Route("100.64.0.7", 58465))
            store.update { it.put("task_session", owner.login).put("refresh_token", "synthetic-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(old))).put("computer_selection", old.origin) }
            TailscaleGrantStore(store::load, store::update).save(owner, grant) { true }
            val replacement = grant.copy(id = UUID.randomUUID().toString(), build = "default", route = PairingCode.Route("100.64.0.8", 58465))
            var activated = false
            val upgrade = NativeConfirmedTailscaleUpgrade(owner, old, grant, replacement, {}, { activated = true })
            val result = upgrade.commit(store::update, old.copy(instanceTag = "default"))
            val restored = NativeCredentialStore(context, name)
            assertTrue(activated); assertEquals(listOf(result), restored.pairedMacs())
            assertEquals(old.origin, result.origin); assertEquals(owner.userId, result.accountUserId)
            assertEquals(old.origin, restored.load()!!.getString("computer_selection"))
            assertEquals(replacement, TailscaleGrantStore(restored::load, restored::update).find(owner, grant.source))
        } finally { store.clear(); context.deleteSharedPreferences(name) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        val folder = File(instrumentation.targetContext.filesDir, "test-captures").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
