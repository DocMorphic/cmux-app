package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefTranslationPreferencesTest {
    private val owner = NativeChiefTranslationPreferenceOwner("user:one", "team", "mac", "installation")
    private val pair = NativeChiefAutoTranslation(translationLanguage("es"), translationLanguage("en"))
    private fun store(values: MutableMap<String, String>) = NativeChiefTranslationPreferenceStore(values::get,
        { key, value -> if (value == null) values.remove(key) else values[key] = value },
        { tag -> tag.takeIf { it in setOf("en", "es", "fr") }?.let { translationLanguage(it) } })

    @Test fun accountTeamDeviceInstallationAndConversationAreIndependentScopes() {
        val values = mutableMapOf<String, String>(); val store = store(values)
        val first = store.scoped(owner, "chief-main") { true }; first.save(pair)
        for (different in listOf(owner.copy(account = "user:two"), owner.copy(team = "other"),
            owner.copy(device = "other"), owner.copy(installation = "other"))) assertNull(store.scoped(different, "chief-main") { true }.load())
        assertNull(store.scoped(owner, "other") { true }.load())
        assertEquals(pair, store.scoped(owner, "chief-main") { true }.load())
        val second = store.scoped(owner, "other") { true }; second.save(pair.copy(source = translationLanguage("fr")))
        first.save(null)
        assertNull(first.load()); assertEquals("fr", second.load()!!.source.code)
    }

    @Test fun encodedScopesCannotCollideAndPersistOnlyTheLanguagePair() {
        val values = mutableMapOf<String, String>(); val store = store(values)
        store.scoped(owner.copy(account = "user:a:b", team = "c"), "d") { true }.save(pair)
        store.scoped(owner.copy(account = "user:a", team = "b:c"), "d") { true }.save(pair)
        assertEquals(2, values.size)
        assertTrue(values.keys.all { it.length == 67 && it.startsWith("v1:") })
        for (json in values.values.map(::JSONObject)) assertEquals(setOf("version", "source", "target"), json.keys().asSequence().toSet())
    }

    @Test fun retiredOwnerCannotLoadWriteOrClearPreferences() {
        val values = mutableMapOf<String, String>(); var current = true
        val preferences = store(values).scoped(owner, "chief-main") { current }
        preferences.save(pair); val before = values.toMap(); current = false
        assertThrows(IllegalStateException::class.java) { preferences.load() }
        assertThrows(IllegalStateException::class.java) { preferences.save(pair.copy(source = translationLanguage("fr"))) }
        assertThrows(IllegalStateException::class.java) { preferences.save(null) }
        assertEquals(before, values)
    }

    @Test fun malformedOrUnknownLanguagesDoNotRestoreAnAutomaticTranslation() {
        var stored: String? = null
        val preferences = NativeChiefTranslationPreferenceStore({ stored }, { _, value -> stored = value },
            { tag -> tag.takeIf { it in setOf("es", "en") }?.let { translationLanguage(it) } }).scoped(owner, "chief-main") { true }
        for (raw in listOf("invalid", "{}", "{\"version\":2,\"source\":\"es\",\"target\":\"en\"}",
            "{\"version\":1,\"source\":\"und\",\"target\":\"en\"}")) {
            stored = raw; assertNull(preferences.load())
        }
        preferences.save(pair); assertEquals(pair, preferences.load())
    }
}
