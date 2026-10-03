package io.github.docmorphic.cmuxapp

import java.util.Locale

/** Display-only translation of upstream MacBuildChannel; never a build admission policy. */
internal object NativeMacBuildLabel {
    fun label(bundleId: String?, tag: String?): String? {
        val meaningful = tag?.trim()?.takeIf { it.isNotEmpty() }
        val normalized = meaningful?.lowercase(Locale.ROOT)
        val bundle = bundleId.orEmpty().trim().lowercase(Locale.ROOT)
        val channel = when {
            bundle == "com.cmuxterm.app" -> "Stable"
            bundle.startsWith("com.cmuxterm.app.") -> when (bundle.removePrefix("com.cmuxterm.app.").split('.').firstOrNull { it.isNotEmpty() }) {
                "nightly" -> "Nightly"
                "rc" -> "RC"
                "staging" -> "Staging"
                "debug", "dev" -> "DEV"
                else -> null
            }
            bundle.startsWith("dev.cmux") -> "DEV"
            else -> null
        }
        if (channel != null) return if (meaningful == null || normalized == "default" ||
            normalized == channel.lowercase(Locale.ROOT)) channel else "DEV · $meaningful"
        if (meaningful == null) return null
        if (bundle.isEmpty()) when (normalized) {
            "default", "stable" -> return "Stable"
            "nightly" -> return "Nightly"
            "rc" -> return "RC"
            "staging" -> return "Staging"
            "dev" -> return "DEV"
        }
        return if (normalized != "default") "DEV · $meaningful" else null
    }
}
