package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.io.IOException
import java.util.Locale

internal class MacBuildNotSupported : IOException(
    "This cmux Mac build is not supported by this Android app. Use the stable, nightly, or RC Mac release, then pair again.")

/** The distributed iOS audience, explicitly selected for both consumer Android build variants. */
internal class NativeMacBuildAudience private constructor() {
    fun allowsTag(tag: String?): Boolean = normalized(tag) in setOf("default", "nightly", "rc")

    fun allows(tag: String?, namespace: String? = null): Boolean = allowsTag(tag) &&
        (namespace == null || namespace == "legacy" || officialNamespace(namespace))

    // Upstream retains legacy rows for authenticated tag enrichment, without allowing unknown live builds.
    fun allowsSavedTag(tag: String?) = tag == null || allowsTag(tag)
    fun allowsPush(tuple: PhonePushTuple) = allows(tuple.macInstanceTag,
        tuple.macBuildID?.lowercase(Locale.ROOT)?.let { "mac:$it" })
    fun requireTag(tag: String?) { if (!allowsTag(tag)) throw MacBuildNotSupported() }

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
            return status.get(name) as? String ?: throw MacBuildNotSupported()
        }
        if (!allowsAuthenticated(field("mac_instance_tag"), field("mac_client_namespace"), field("mac_app_version"),
                locallyAuthorizedTailscale)) throw MacBuildNotSupported()
    }

    fun presence(state: NativeMacPresenceState) = state.copy(instances = state.instances.filterKeys { allowsTag(it.buildTag) })

    companion object {
        val consumer = NativeMacBuildAudience()
        private fun normalized(tag: String?) = tag?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        private fun officialNamespace(value: String) = value == "mac:com.cmuxterm.app" ||
            value == "mac:com.cmuxterm.app.nightly" || value.startsWith("mac:com.cmuxterm.app.nightly.") ||
            value == "mac:com.cmuxterm.app.rc" || value.startsWith("mac:com.cmuxterm.app.rc.")
    }
}
