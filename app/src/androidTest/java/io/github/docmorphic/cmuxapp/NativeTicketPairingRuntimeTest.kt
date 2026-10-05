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
