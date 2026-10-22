package io.github.docmorphic.cmuxapp

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

internal data class NativeChiefTranslationConfiguration(val preferenceOwner: NativeChiefTranslationPreferenceOwner?)
internal class NativeChiefTranslationPlatformHost(val download: NativeChiefTranslationDownload,
    val settings: () -> PendingIntent?) : AutoCloseable {
    override fun close() = download.close()
}

/** One retained engine; foreground Activities only attach to its request state. */
internal suspend fun installNativeChiefTranslation(session: NativeChiefTranscriptSession, context: Context,
    configuration: NativeChiefTranslationConfiguration, locale: Locale) {
    if (Build.VERSION.SDK_INT < 31 || session.translations.value != null) return
    val download = NativeChiefTranslationDownload {
        try { session.admitCapturedAction(); true } catch (_: IllegalStateException) { false }
    }
    var engine: NativeChiefPlatformTranslator? = null
    var installed = false
    try {
        engine = NativeChiefPlatformTranslator.create(context, download::request) ?: return
        currentCoroutineContext().ensureActive()
        session.admitCapturedAction()
        val app = context.applicationContext
        val storage = app.getSharedPreferences("native_chief_translation", Context.MODE_PRIVATE)
        val preferences = configuration.preferenceOwner?.let { owner ->
            NativeChiefTranslationPreferenceStore({ key -> storage.getString(key, null) }, { key, value ->
                storage.edit().let { editor -> if (value == null) editor.remove(key) else editor.putString(key, value) }.apply()
            }, NativeChiefTranslationLanguages::fromTag).scoped(owner, session.conversationId) {
                try { session.admitCapturedAction(); true } catch (_: IllegalStateException) { false }
            }
        }
        val translator = engine
        val host = NativeChiefTranslationPlatformHost(download) {
            session.admitCapturedAction(); translator.modelSettingsIntent()
        }
        session.installTranslations(translator, NativeChiefTranslationLanguages.deviceLanguage(locale), preferences, host)
        installed = true
    } finally {
        if (!installed) { download.close(); engine?.close() }
    }
}
