package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeForegroundMacSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun originalMacControlsKeepTheirOwnerAcrossPickerChangesAndBlockAfterRevocation() {
        val owner = NativeTeamScope("login", "user", "original", 1)
        val selected = mutableStateOf(owner)
        val proof = mutableStateOf<NativeTeamScope?>(owner)
        NativeFixturePeer().use { peer ->
            peer.deviceId = "device"; peer.instanceTag = "default"
            peer.phonePushStatus = JSONObject().put("forwarding_enabled", true).put("mode", "onlyWhenAway")
                .put("hide_content", false).put("account_scope", "verified_same_account").put("api_origin", "https://cmux.com")
            peer.phonePushUpdate = { params ->
                val status = checkNotNull(peer.phonePushStatus)
                params.keys().forEach { key -> status.put(key, params.get(key)) }
                JSONObject()
            }
            val mac = NativeCredentialStore.PairedMac("original-route", "device", "Original", "default", "user", "original")
            MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).use { client ->
                runBlocking { client.connect() }
                compose.setContent { MaterialTheme {
                    val currentSelected = selected.value; val currentProof = proof.value
                    PhoneMacPushSettings(client, mac, owner, isCurrent = { wire, record, scope ->
                        nativeForegroundMacSettingsCurrent("login", scope, record, wire, client, mac, true,
                            mac.code, mac.code, listOf(mac), { it == currentSelected }, { true }, { _, _ -> currentProof })
                    }, onConnect = {}, displayName = "Original Alias")
                } }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("push.mac.enabled").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Original Alias").assertExists()
                compose.runOnIdle { selected.value = owner.copy(teamId = "picker", generation = 2) }
                compose.onNodeWithTag("push.mac.enabled").performClick()
                compose.waitUntil(10_000) { peer.phonePushStatus?.optBoolean("forwarding_enabled") == false }
                compose.waitUntil(10_000) { compose.onNodeWithTag("push.mac.enabled").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.Off }
                assertEquals(1, peer.requests.count { it.optString("method") == "phone_push.settings.update" })
                compose.runOnIdle { proof.value = null }
                compose.waitForIdle()
                compose.onNodeWithTag("push.mac.enabled").assertDoesNotExist()
                assertEquals(1, peer.requests.count { it.optString("method") == "phone_push.settings.update" })
                assertFalse(checkNotNull(peer.phonePushStatus).getBoolean("forwarding_enabled"))
            }
        }
    }
}
