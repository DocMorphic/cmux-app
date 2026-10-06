package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.staticCompositionLocalOf

/** Composition-only debug launch input; never stored in the catalog or acknowledgement ledger. */
internal const val SUPPRESS_WHATS_NEW_EXTRA = "CMUX_UITEST_SUPPRESS_WHATS_NEW"
internal val LocalSuppressWhatsNewLaunch = staticCompositionLocalOf { false }
