package io.github.docmorphic.cmuxapp

import android.icu.util.ULocale
import java.util.Locale

/** Use the system's likely-script data, as Foundation Locale.Language does. */
internal object NativeChiefTranslationLanguages {
    private val knownCodes by lazy { ULocale.getAvailableLocales().map { Locale.forLanguageTag(it.toLanguageTag()).language }.toSet() }
    fun fromTag(tag: String): NativeChiefTranslationLanguage? {
        val locale = ULocale.createCanonical(ULocale.forLanguageTag(tag).name)
        val canonicalCode = Locale.forLanguageTag(locale.toLanguageTag()).language
        if (canonicalCode.isEmpty() || canonicalCode == "und") return null
        // Foundation keeps these languageCode identifiers even though its
        // minimalIdentifier (and Android Locale) uses the modern alias.
        val suppliedCode = tag.substringBefore('-').lowercase(Locale.ROOT)
        val code = if (suppliedCode in setOf("iw", "in", "ji")) suppliedCode else canonicalCode
        val script = locale.script.takeIf { it.isNotEmpty() }
            ?: if (canonicalCode in knownCodes) ULocale.addLikelySubtags(locale).script.takeIf { it.isNotEmpty() } else null
        return NativeChiefTranslationLanguage(locale.toLanguageTag(), code, script)
    }
    fun deviceLanguage(preferred: Locale): NativeChiefTranslationLanguage {
        val language = fromTag(preferred.toLanguageTag()) ?: return fromTag("en")!!
        return if (language.code == "zh") fromTag(listOfNotNull(language.code, language.script).joinToString("-"))!!
        else fromTag(language.code)!!
    }
}
