package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.io.IOException
import java.util.Locale

internal class MacBuildNotSupported(companion: Boolean = false) : IOException(
    "This cmux Mac build is not supported by this Android app. Use the stable, nightly, or RC Mac release" +
        (if (companion) ", or the cmux-app Mac notification companion" else "") + ", then pair again.")

/** The distributed iOS audience, explicitly selected for both consumer Android build variants. */
internal class NativeMacBuildAudience private constructor(private val companion: Boolean = false) {
    private fun companionTag(tag: String?) = companion && normalized(tag) == COMPANION_TAG
    fun allowsTag(tag: String?): Boolean = normalized(tag) in setOf("default", "nightly", "rc") || companionTag(tag)

    fun allows(tag: String?, namespace: String? = null): Boolean = if (companionTag(tag)) namespace == COMPANION_NAMESPACE
        else normalized(tag) in setOf("default", "nightly", "rc") &&
            (namespace == null || namespace == "legacy" || officialNamespace(namespace))

    // Upstream retains legacy rows for authenticated tag enrichment, without allowing unknown live builds.
    fun allowsSavedTag(tag: String?) = tag == null || allowsTag(tag)
    fun allowsPush(tuple: PhonePushTuple) = allows(tuple.macInstanceTag,
        tuple.macBuildID?.lowercase(Locale.ROOT)?.let { "mac:$it" })
    val unsupportedMessage get() = MacBuildNotSupported(companion).message
    fun requireTag(tag: String?) { if (!allowsTag(tag)) throw MacBuildNotSupported(companion) }

    /** Companion source is pinned to the 0.65 host API; the downloaded protocol floor may raise it. */
    fun violation(policy: NativeMacCompatibilityPolicy, tag: String?, version: String?): MacCompatibilityViolation? {
        if (!companionTag(tag)) return policy.violation(tag, version)
        val baseline = checkNotNull(MacNumericVersion.parse(COMPANION_MINIMUM))
        val floor = maxOf(baseline, policy.requirement()?.stable ?: baseline)
        val reported = version?.trim()?.takeIf { it.isNotEmpty() }
        val installed = reported?.substringBefore('+')?.let(MacNumericVersion::parse)
        return if (installed == null || installed < floor)
            MacCompatibilityViolation(false, reported?.take(128), floor.toString(), companion = true) else null
    }

    fun allowsAuthenticated(tag: String?, namespace: String?, version: String?, locallyAuthorizedTailscale: Boolean): Boolean {
        if (allows(tag, namespace)) return true
        if (normalized(tag) != null || !locallyAuthorizedTailscale) return false
        val numeric = version?.trim()?.let(MacNumericVersion::parse) ?: return false
        // This exception is evaluated before the separate version floor, which may still reject 0.64.17.
        return numeric >= checkNotNull(MacNumericVersion.parse("0.64.17")) &&
            numeric < checkNotNull(MacNumericVersion.parse("0.64.18"))
    }

    fun requireAuthenticated(status: JSONObject, locallyAuthorizedTailscale: Boolean) {
        fun field(name: String): String? {
            if (status.isNull(name)) return null
            return status.get(name) as? String ?: throw MacBuildNotSupported(companion)
        }
        if (!allowsAuthenticated(field("mac_instance_tag"), field("mac_client_namespace"), field("mac_app_version"),
                locallyAuthorizedTailscale)) throw MacBuildNotSupported(companion)
    }

    fun presence(state: NativeMacPresenceState) = state.copy(instances = state.instances.filterKeys { allowsTag(it.buildTag) })

    companion object {
        val consumer = NativeMacBuildAudience()
        // Explicit project configuration chosen for private Firebase + the isolated Mac companion.
        // Android debuggability never grants access to arbitrary development builds.
        val application = NativeMacBuildAudience(companion = true)
        const val COMPANION_TAG = "android-push"
        const val COMPANION_BUNDLE = "com.cmuxterm.app.debug.android.push"
        const val COMPANION_NAMESPACE = "mac:$COMPANION_BUNDLE"
        const val COMPANION_MINIMUM = "0.65.0"
        private fun normalized(tag: String?) = tag?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        private fun officialNamespace(value: String) = value == "mac:com.cmuxterm.app" ||
            value == "mac:com.cmuxterm.app.nightly" || value.startsWith("mac:com.cmuxterm.app.nightly.") ||
            value == "mac:com.cmuxterm.app.rc" || value.startsWith("mac:com.cmuxterm.app.rc.")
    }
}
