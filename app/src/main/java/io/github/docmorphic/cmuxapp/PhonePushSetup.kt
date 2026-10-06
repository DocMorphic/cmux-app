package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

internal data class PhonePushSetupProvider(val configured: Boolean, val allowed: Boolean,
    val background: Boolean, val grant: PhoneFcmTokenGrant?, val token: PhoneFcmTokenSnapshot?, val cleaningUp: Boolean)
internal enum class PhonePushSetupStage(val text: String) {
    UNCONFIGURED("Push service is not configured in this build"), SIGN_IN("Sign in to set up push alerts"),
    PERMISSION("Allow notifications in Android settings"), BACKGROUND("Enable background notifications first"),
    DISABLED("Push alerts are off"), CLEANUP("Finishing previous push setup"), TOKEN("Registering this phone"),
    CONNECT("Connect a Mac to set up push alerts"), PAIR("Pair your Mac’s notification helper"),
    ENROLLING("Pairing notification helper"), PAIRED("Helper paired · delivery not yet verified"),
    RENEW("Phone registration changed · pair the helper again")
}
internal data class PhonePushSetupMac(val origin: String, val name: String, val stage: PhonePushSetupStage,
    val attempt: String? = null, val helperEpoch: String? = null)
internal data class PhonePushSetupState(val stage: PhonePushSetupStage, val enabled: Boolean,
    val canEnable: Boolean, val canPair: Boolean, val macs: List<PhonePushSetupMac>)

internal fun phonePushSetupState(state: JSONObject, team: NativeTeamScope?, provider: PhonePushSetupProvider,
    macs: List<NativeCredentialStore.PairedMac>, now: Long): PhonePushSetupState {
    val signedIn = team != null && state.optString("task_session") == team.login && state.optString("refresh_token").isNotBlank()
    val enabled = signedIn && provider.grant?.login == team.login
    val token = provider.token?.takeIf { enabled && it.grant == provider.grant }
    val keys = PhonePushKeyState(state)
    val pending = PhoneHelperEnrollmentState(state, { now }).pending()
    val receipts = state.optJSONArray(PhoneHelperEnrollmentState.RECEIPTS)
    val entries = if (!signedIn) emptyList() else macs.mapNotNull { mac ->
        val origin = keys.canonicalOrigin(team, mac.origin) ?: return@mapNotNull null
        val peer = keys.peer(team, origin)
        val attempt = pending.singleOrNull { it.origin == origin && it.team.login == team.login && it.team.teamId == team.teamId && it.matches(token) }
        val helper = PhonePushHelperState(state).binding(team, origin)
        val receipt = receipts?.let { array -> (0 until array.length()).mapNotNull(array::optJSONObject).singleOrNull {
            it.optString("login") == team.login && it.optString("team") == team.teamId && it.optString("helper_epoch") == helper?.epoch &&
                keys.canonicalOrigin(team, it.optString("origin")) == origin
        } }
        val stage = when {
            peer == null -> PhonePushSetupStage.CONNECT
            attempt != null -> PhonePushSetupStage.ENROLLING
            helper == null || receipt == null -> PhonePushSetupStage.PAIR
            token == null || receipt.optString("grant") != token.grant.epoch || receipt.optString("token_revision") != token.revision -> PhonePushSetupStage.RENEW
            else -> PhonePushSetupStage.PAIRED
        }
        PhonePushSetupMac(origin, mac.name, stage, attempt?.id, helper?.epoch)
    }.distinctBy { it.origin }
    val stage = when {
        !provider.configured -> PhonePushSetupStage.UNCONFIGURED
        !signedIn -> PhonePushSetupStage.SIGN_IN
        !provider.allowed -> PhonePushSetupStage.PERMISSION
        !provider.background -> PhonePushSetupStage.BACKGROUND
        !enabled -> PhonePushSetupStage.DISABLED
        provider.cleaningUp -> PhonePushSetupStage.CLEANUP
        token == null -> PhonePushSetupStage.TOKEN
        entries.isEmpty() || entries.all { it.stage == PhonePushSetupStage.CONNECT } -> PhonePushSetupStage.CONNECT
        entries.any { it.stage == PhonePushSetupStage.ENROLLING } -> PhonePushSetupStage.ENROLLING
        entries.any { it.stage == PhonePushSetupStage.RENEW } -> PhonePushSetupStage.RENEW
        entries.any { it.stage == PhonePushSetupStage.PAIR } -> PhonePushSetupStage.PAIR
        else -> PhonePushSetupStage.PAIRED
    }
    return PhonePushSetupState(stage, enabled, signedIn && provider.configured && provider.allowed && provider.background,
        enabled && provider.configured && provider.allowed && provider.background && token != null && !provider.cleaningUp, entries)
}

/** In-memory review only: never save an unconfirmed offer in UI saved state or include it in toString. */
internal class PhoneHelperOfferReview private constructor(
    private val raw: String, val team: NativeTeamScope, val origin: String, val endpoint: String, val fingerprint: String,
    val expiresAt: Long, private val native: PhonePushPeer, private val nativeEpoch: String,
    private val phone: PhonePushDescriptor, private val token: PhoneFcmTokenSnapshot
) {
    fun prepare(state: JSONObject, currentToken: PhoneFcmTokenSnapshot?, now: Long): PendingPhoneHelperEnrollment {
        val keys = PhonePushKeyState(state)
        check(currentToken == token && keys.peer(team, origin) == native && keys.peerEpoch(team, origin) == nativeEpoch &&
            keys.existingIdentity(team.login)?.descriptor() == phone) { "Push setup changed. Review a new offer." }
        return PhoneHelperEnrollmentState(state, { now }).prepare(raw, team, origin, token)
    }
    companion object {
        fun create(raw: String, state: JSONObject, team: NativeTeamScope, origin: String,
            token: PhoneFcmTokenSnapshot, now: Long): PhoneHelperOfferReview {
            val keys = PhonePushKeyState(state)
            val canonical = checkNotNull(keys.canonicalOrigin(team, origin))
            val native = checkNotNull(keys.peer(team, canonical)); val epoch = checkNotNull(keys.peerEpoch(team, canonical))
            val phone = checkNotNull(keys.existingIdentity(team.login))
            return PhoneHelperEnrollment(raw, team, canonical, native, epoch, phone, token, { true }, { now }).use { session ->
                session.begin()
                val offer = JSONObject(raw)
                val helper = PhonePushDescriptor.parse(offer.getJSONObject("helper"))
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(helper.publicKey))
                    .joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
                PhoneHelperOfferReview(raw, team, canonical, session.endpoint, fingerprint, offer.getLong("expiresAt"),
                    native, epoch, phone.descriptor(), token)
            }
        }
    }
}
