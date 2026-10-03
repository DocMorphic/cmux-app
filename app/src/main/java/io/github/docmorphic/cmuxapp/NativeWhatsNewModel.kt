package io.github.docmorphic.cmuxapp

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal enum class WhatsNewChannel(val token: String) {
    DEV("dev"), BETA("beta"), INTERNAL("internal"), PROD("prod"), DEMO("demo");
    fun permits(tokens: List<String>?): Boolean = tokens?.contains(token) ?: (this in setOf(DEV, BETA, INTERNAL))
}

/** Matches the iOS notice comparator, independently of the strict host compatibility grammar. */
internal object WhatsNewVersion {
    fun compare(left: String, right: String): Int {
        fun parts(value: String) = value.split('.').filter(String::isNotEmpty).map { it.trim().toLongOrNull() ?: 0L }
        val a = parts(left); val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val comparison = (a.getOrNull(i) ?: 0L).compareTo(b.getOrNull(i) ?: 0L)
            if (comparison != 0) return comparison
        }
        return 0
    }
    fun within(version: String, min: String?, max: String?) =
        (min == null || compare(version, min) >= 0) && (max == null || compare(version, max) <= 0)
}

internal data class WhatsNewFeature(val title: String, val detail: String, val symbol: String? = null)
internal sealed interface WhatsNewBody {
    data class Features(val rows: List<WhatsNewFeature>) : WhatsNewBody
    data object Pairing : WhatsNewBody
    data class Web(val url: String) : WhatsNewBody
}
internal enum class WhatsNewKind { ENTRY, ANNOUNCEMENT }
internal data class WhatsNewPage(
    val id: String, val title: String, val body: WhatsNewBody, val releaseLabel: String? = null,
    val kind: WhatsNewKind = WhatsNewKind.ENTRY, val channels: List<String>? = null,
    val minVersion: String? = null, val maxVersion: String? = null, val requiredPairing: Boolean = false
) {
    val key: String get() = "${kind.name.lowercase(Locale.ROOT)}:$id"
}
internal data class WhatsNewTranslation(val title: String, val releaseLabel: String?, val features: List<WhatsNewFeature>)
internal data class WhatsNewAnnouncement(
    val id: String, val minVersion: String, val maxVersion: String,
    val title: String? = null, val releaseLabel: String? = null, val features: List<WhatsNewFeature>? = null,
    val nativeEntryId: String? = null, val webUrl: String? = null, val channels: List<String>? = null,
    val localizations: Map<String, WhatsNewTranslation> = emptyMap()
) {
    fun localized(languages: List<String>): WhatsNewAnnouncement {
        val available = localizations.keys.sorted()
        fun normalized(language: String) = language.replace('_', '-').lowercase(Locale.ROOT)
        val match = (languages + "en").firstNotNullOfOrNull { language ->
            val wanted = normalized(language)
            available.firstOrNull { normalized(it) == wanted }
                ?: available.firstOrNull { normalized(it) == wanted.substringBefore('-') }
                ?: available.firstOrNull { normalized(it).substringBefore('-') == wanted.substringBefore('-') }
        } ?: return this
        val content = localizations.getValue(match)
        return copy(title = content.title, releaseLabel = content.releaseLabel, features = content.features)
    }
}
internal data class WhatsNewRemote(
    val visibleEntryIds: List<String>, val entryChannels: Map<String, List<String>> = emptyMap(),
    val announcements: List<WhatsNewAnnouncement> = emptyList()
) {
    companion object {
        /** One bad announcement is skipped; malformed visibility must never become a retraction. */
        fun decode(raw: String): WhatsNewRemote {
            require(raw.length <= 1_048_576) { "Notice payload too large" }
            val root = JSONObject(raw)
            val visible = root.getJSONArray("visibleEntryIds").strings()
            val channels = root.optionalObject("entryChannels")?.let { map ->
                map.keys().asSequence().associateWith { map.getJSONArray(it).strings() }
            }.orEmpty()
            val values = root.optJSONArray("announcements")
            val announcements = (0 until (values?.length() ?: 0)).mapNotNull { index ->
                try { values!!.getJSONObject(index).announcement() } catch (_: Exception) { null }
            }.distinctBy { it.id }
            return WhatsNewRemote(visible, channels, announcements)
        }
        private fun JSONObject.announcement(): WhatsNewAnnouncement {
            val localized = optionalObject("localizations")?.let { map ->
                map.keys().asSequence().associateWith { key ->
                    val value = map.getJSONObject(key)
                    WhatsNewTranslation(value.text("title"), value.optionalText("releaseLabel"), value.getJSONArray("features").features())
                }
            }.orEmpty()
            return WhatsNewAnnouncement(text("id").also { require(it.isNotBlank()) }, text("minVersion"), text("maxVersion"),
                optionalText("title"), optionalText("releaseLabel"), optionalArray("features")?.features(),
                optionalText("nativeEntryId"), optionalText("webUrl"), optionalArray("channels")?.strings(), localized)
        }
        private fun JSONArray.features() = (0 until length()).map { i ->
            val item = getJSONObject(i)
            WhatsNewFeature(item.text("title"), item.text("detail"), item.optionalText("symbol"))
        }
        private fun JSONArray.strings() = (0 until length()).map { get(it) as String }
        private fun JSONObject.text(name: String) = get(name) as String
        private fun JSONObject.optionalText(name: String): String? = if (!has(name) || isNull(name)) null else text(name)
        private fun JSONObject.optionalArray(name: String): JSONArray? = if (!has(name) || isNull(name)) null else getJSONArray(name)
        private fun JSONObject.optionalObject(name: String): JSONObject? = if (!has(name) || isNull(name)) null else getJSONObject(name)
    }
}

/** Feed origin is trusted build configuration, never taken from remote notice content. */
internal class WhatsNewWebPolicy(apiBaseUrl: String?) {
    private val api = apiBaseUrl?.let { runCatching { URI(it) }.getOrNull() }
    val originKey: String = api?.takeIf { it.host != null && it.scheme != null }?.let {
        "${it.scheme.lowercase(Locale.ROOT)}://${it.host.lowercase(Locale.ROOT)}:${it.port.takeIf { p -> p >= 0 } ?: "default"}"
    } ?: "none"
    private val hosts = setOf("cmux.com", "www.cmux.com") + listOfNotNull(api?.host?.lowercase(Locale.ROOT))
    fun allows(raw: String): Boolean {
        val url = try { URI(raw) } catch (_: Exception) { return false }
        val host = url.host?.lowercase(Locale.ROOT) ?: return false
        if (host !in hosts || url.rawUserInfo != null) return false
        return when (url.scheme?.lowercase(Locale.ROOT)) {
            "https" -> true
            "http" -> host == "localhost" || host == "127.0.0.1"
            else -> false
        }
    }
}
