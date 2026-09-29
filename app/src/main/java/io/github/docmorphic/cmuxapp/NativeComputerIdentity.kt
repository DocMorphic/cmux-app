package io.github.docmorphic.cmuxapp

/** Upstream CmxDeviceIDCanonicalization: only UUID case is canonicalized.
 * Non-UUID protocol identities remain opaque, including case and whitespace.
 */
internal fun canonicalMacDeviceId(value: String): String =
    if (Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value))
        value.lowercase(java.util.Locale.ROOT) else value
