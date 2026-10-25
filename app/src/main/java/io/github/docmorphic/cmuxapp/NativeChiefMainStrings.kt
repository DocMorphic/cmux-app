package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.util.Locale

/** Original iOS Bundle.main misses these ShellUI keys and uses source defaults.
 * Scope the lookup only; keep the Activity owner, layout direction and UI locale. */
private val chiefMainFallbackStrings = setOf(
    R.string.chief_question_cancel,
    R.string.chief_home_empty_message,
    R.string.chief_home_empty,
    R.string.chief_home_ask,
    R.string.chief_home_no_messages,
    R.string.chief_home_ready_message,
    R.string.chief_home_ready_title,
    R.string.chief_home_recent,
    R.string.chief_home_search,
    R.string.chief_home_title,
    R.string.chief_home_loading,
    R.string.chief_question_instructions,
    R.string.chief_question_needs_input,
    R.string.chief_question_other_hint,
    R.string.chief_question_other,
    R.string.chief_question_select_all,
    R.string.chief_question_title,
    R.string.chief_home_refresh,
    R.string.chief_question_send,
    R.string.chief_home_unavailable,
    R.string.chief_home_working
)

@Composable
internal fun nativeChiefMainString(@StringRes id: Int): String {
    if (id !in chiefMainFallbackStrings) return stringResource(id)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val defaults = remember(context, configuration) {
        context.createConfigurationContext(Configuration(configuration).apply { setLocale(Locale.ENGLISH) })
    }
    return defaults.getString(id)
}

/** Foundation's source String(format:) uses ASCII digits with the localized format. */
@Composable
internal fun nativeChiefHomeUnread(count: Long): String = NativeChiefUnreadFormat.positive(
    count, stringResource(R.string.chief_home_unread_one), stringResource(R.string.chief_home_unread_other))
