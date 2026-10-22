package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class NativeChiefTranslationPreferenceOwner(val account: String, val team: String,
    val device: String, val installation: String?) {
    companion object {
        fun from(mac: NativeCredentialStore.PairedMac, login: String?): NativeChiefTranslationPreferenceOwner? {
            val account = mac.accountUserId?.takeIf(String::isNotBlank)?.let { "user:$it" }
                ?: login?.takeIf(String::isNotBlank)?.let { "login:$it" } ?: return null
            val device = canonicalMacDeviceId(mac.deviceId).takeIf(String::isNotBlank) ?: return null
            return NativeChiefTranslationPreferenceOwner(account, mac.accountTeamId.orEmpty(), device, mac.instanceTag)
        }
    }
}

/** Independent preference keys contain no message/draft/translated text or pairing credentials. */
internal class NativeChiefTranslationPreferenceStore(private val read: (String) -> String?,
    private val write: (String, String?) -> Unit, private val language: (String) -> NativeChiefTranslationLanguage?) {
    fun scoped(owner: NativeChiefTranslationPreferenceOwner, conversation: String,
        permits: () -> Boolean): NativeChiefTranslationPreferences {
        require(owner.account.isNotBlank() && owner.device.isNotBlank() && conversation.isNotBlank())
        val scope = JSONArray(listOf(owner.account, owner.team, owner.device, owner.installation, conversation)).toString()
        val key = "v1:" + MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun checkOwner() = check(permits()) { "Chief computer or account changed" }
        return object : NativeChiefTranslationPreferences {
            override fun load(): NativeChiefAutoTranslation? {
                checkOwner()
                val value = runCatching {
                    val json = read(key)?.let(::JSONObject) ?: return@runCatching null
                    if (json.optInt("version") != 1) return@runCatching null
                    val source = language(json.getString("source")) ?: return@runCatching null
                    val target = language(json.getString("target")) ?: return@runCatching null
                    NativeChiefAutoTranslation(source, target)
                }.getOrNull()
                checkOwner()
                return value
            }
            override fun save(value: NativeChiefAutoTranslation?) {
                checkOwner()
                val json = value?.let { JSONObject().put("version", 1).put("source", it.source.tag).put("target", it.target.tag).toString() }
                write(key, json)
            }
        }
    }
}
