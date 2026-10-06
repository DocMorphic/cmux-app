package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhonePushSetupTest {
    private fun fixture() = PhoneHelperEnrollmentTest.Context(JSONObject(javaClass.getResource("/push/helper-enrollment.json")!!.readText()))
    private fun provider(c: PhoneHelperEnrollmentTest.Context) = PhonePushSetupProvider(true, true, true, c.token.grant, c.token, false)
    private fun model(c: PhoneHelperEnrollmentTest.Context, provider: PhonePushSetupProvider = provider(c), team: NativeTeamScope? = c.team) =
        phonePushSetupState(c.state, team, provider, listOf(c.mac), c.now)
    private fun review(c: PhoneHelperEnrollmentTest.Context) = PhoneHelperOfferReview.create(c.offer.toString(), c.state,
        c.team, c.mac.origin, c.token, c.now)
    @Test fun readinessIdentifiesMissingConfigurationAccountPermissionAndConsentWithoutEnablingPush() {
        val c = fixture(); val p = provider(c)
        for ((value, stage) in listOf(
            p.copy(configured = false) to PhonePushSetupStage.UNCONFIGURED,
            p.copy(allowed = false) to PhonePushSetupStage.PERMISSION,
            p.copy(background = false) to PhonePushSetupStage.BACKGROUND,
            p.copy(grant = null, token = null) to PhonePushSetupStage.DISABLED,
            p.copy(cleaningUp = true) to PhonePushSetupStage.CLEANUP,
            p.copy(token = null) to PhonePushSetupStage.TOKEN
        )) {
            val result = model(c, value)
            assertEquals(stage, result.stage); assertFalse(result.canPair)
        }
        assertEquals(PhonePushSetupStage.SIGN_IN, model(c, team = null).stage)
        assertTrue(model(c, p.copy(grant = null, token = null)).canEnable)
    }
    @Test fun reviewedOfferIsPrivateAndNonmutatingUntilExplicitConfirmation() {
        val c = fixture(); val before = c.state.toString(); val value = review(c)
        assertEquals(before, c.state.toString())
        assertEquals(c.offer.getString("endpoint"), value.endpoint)
        assertEquals(64, value.fingerprint.replace(" ", "").length)
        assertFalse(value.toString().contains(c.offer.getString("secret")))
        assertFalse(value.toString().contains(c.token.token))
        val prepared = value.prepare(c.state, c.token, c.now)
        assertEquals(c.mac.origin, prepared.origin)
        assertFalse(c.state.has(PhonePushHelperState.KEY))
        assertEquals(PhonePushSetupStage.ENROLLING, model(c).stage)
        assertEquals(prepared.id, model(c).macs.single().attempt)
    }
    @Test fun confirmationRechecksExpiryTokenNativeEpochAndPhoneIdentity() {
        for (change in 0..4) {
            val c = fixture(); val value = review(c)
            var token: PhoneFcmTokenSnapshot? = c.token
            when (change) {
                0 -> c.now = value.expiresAt
                1 -> token = c.token.copy(revision = "new")
                2 -> token = null
                3 -> PhonePushKeyState(c.state).pin(c.team, c.mac.origin,
                    c.peer.copy(descriptor = PhonePushIdentity.generate().descriptor()))
                4 -> c.state.getJSONObject(PhonePushKeyState.KEY).put("identity", PhonePushIdentity.generate().wire())
            }
            assertTrue(runCatching { value.prepare(c.state, token, c.now) }.isFailure)
            assertFalse(c.state.has(PhoneHelperEnrollmentState.KEY))
            assertFalse(c.state.has(PhonePushHelperState.KEY))
        }
    }
    @Test fun pairedStateNeedsAReceiptForCurrentTokenAndNeverClaimsVerifiedDelivery() {
        val c = fixture(); val records = PhoneHelperEnrollmentState(c.state, { c.now })
        val attempt = records.prepare(c.offer.toString(), c.team, c.mac.origin, c.token,
            c.fixture.getJSONObject("begin").getString("requestID"))
        c.session().use {
            it.finish(c.fixture.getJSONObject("response"))
            records.confirm(attempt, c.token, it, c.fixture.getJSONObject("ack"))
        }
        assertEquals(PhonePushSetupStage.PAIRED, model(c).stage)
        assertTrue(model(c).stage.text.contains("not yet verified"))
        assertEquals(PhonePushSetupStage.RENEW, model(c, provider(c).copy(token = c.token.copy(revision = "new"))).stage)
        c.state.remove(PhoneHelperEnrollmentState.RECEIPTS)
        assertEquals(PhonePushSetupStage.PAIR, model(c).stage)
    }
    @Test fun staleAccountAndTokenCannotExposePendingAttemptAsPairing() {
        val c = fixture(); review(c).prepare(c.state, c.token, c.now)
        assertNull(model(c, provider(c).copy(token = c.token.copy(revision = "new"))).macs.single().attempt)
        val other = c.team.copy(login = "other")
        assertTrue(model(c, team = other).macs.isEmpty())
        assertFalse(model(c, team = other).canEnable)
    }
    @Test fun missingNativeExchangeRequiresConnectingAndForeignOfferCannotBeReviewed() {
        val c = fixture()
        val offer = JSONObject(c.offer.toString()); offer.getJSONObject("mac").put("macDeviceID", "other")
        assertTrue(runCatching { PhoneHelperOfferReview.create(offer.toString(), c.state, c.team, c.mac.origin, c.token, c.now) }.isFailure)
        c.state.remove(PhonePushKeyState.KEY)
        assertEquals(PhonePushSetupStage.CONNECT, model(c).stage)
        assertEquals(PhonePushSetupStage.CONNECT, model(c).macs.single().stage)
    }
}
