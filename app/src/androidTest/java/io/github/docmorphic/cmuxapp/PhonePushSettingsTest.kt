package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhonePushSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun unconfiguredBuildExplainsAvailabilityAndCannotGrantConsent() {
        var changed = false
        compose.setContent { MaterialTheme {
            PhonePushSettingsContent(PhonePushSetupState(PhonePushSetupStage.UNCONFIGURED, false, false, false, emptyList()),
                false, null, { changed = true }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("push.status").assertTextEquals("Push service is not configured in this build")
        compose.onNodeWithTag("push.enabled").assertIsNotEnabled().assertIsOff()
        assertFalse(changed)
    }
    @Test fun phoneConsentAndMacPairingAreSeparateExplicitActions() {
        var consentRequested = false; var paired: PhonePushSetupMac? = null
        val mac = PhonePushSetupMac("fixture", "Fixture Mac", PhonePushSetupStage.PAIR)
        compose.setContent { MaterialTheme {
            PhonePushSettingsContent(PhonePushSetupState(PhonePushSetupStage.PAIR, true, true, true, listOf(mac)),
                false, null, { consentRequested = true }, {}, {}, {}, { paired = it }, {})
        } }
        compose.onNodeWithText("Pair Notification Helper").performClick()
        assertEquals(mac, paired); assertFalse(consentRequested)
        compose.onNodeWithTag("push.enabled").performClick()
        assertTrue(consentRequested)
    }
    @Test fun pendingAttemptCanBeCancelledAndPairingDoesNotClaimDelivery() {
        var cancelled: String? = null
        val mac = PhonePushSetupMac("fixture", "Fixture Mac", PhonePushSetupStage.ENROLLING, "pending-id")
        compose.setContent { MaterialTheme {
            PhonePushSettingsContent(PhonePushSetupState(PhonePushSetupStage.ENROLLING, true, true, true, listOf(mac)),
                false, "Pairing started", {}, {}, {}, {}, {}, { cancelled = it.attempt })
        } }
        compose.onNodeWithText("Cancel Pairing").performClick()
        assertEquals("pending-id", cancelled)
        compose.onNodeWithText("Helper paired · delivery not yet verified").assertDoesNotExist()
        compose.onNodeWithTag("push.message").assertTextEquals("Pairing started")
    }
    @Test fun macControlsReflectAuthorityAndDisableWhileUnconfirmed() {
        val status = PhoneMacPushStatus(true, PhoneMacPushMode.AWAY, true, "suppressed_mac_active", "healthy")
        var change: PhoneMacPushChange? = null
        val state = androidx.compose.runtime.mutableStateOf(PhoneMacPushState(status, true, loading = false, stale = false))
        compose.setContent { MaterialTheme {
            PhoneMacPushSettingsContent("Fixture Mac", state.value, { change = it }, {})
        } }
        compose.onNodeWithTag("push.mac.enabled").assertIsOn().performClick()
        assertEquals(PhoneMacPushChange.Enabled(false), change)
        compose.onNodeWithTag("push.mac.hidden").assertIsOn()
        compose.onNodeWithTag("push.mac.mode.onlyWhenAway").assertIsSelected()
        compose.onNodeWithTag("push.mac.admission").assertTextEquals("Alerts are paused while you’re active on the Mac.")
        compose.runOnIdle { state.value = state.value.copy(stale = true, error = "Couldn’t confirm that change.") }
        compose.onNodeWithTag("push.mac.enabled").assertIsNotEnabled().assertIsOn()
        compose.onNodeWithText("Last confirmed settings").assertExists()
        compose.onNodeWithTag("push.mac.admission").assertDoesNotExist()
        compose.onNodeWithText("Refresh Mac Settings").assertIsEnabled()
    }

}
