package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogWindowProvider

/** Shell colors follow system appearance; terminal and streamed pixels retain their own colors. */
internal fun cmuxDarkColorScheme() = darkColorScheme(
    primary = Color(0xFF76B9FF), onPrimary = Color(0xFF081421),
    primaryContainer = Color(0xFF253440), onPrimaryContainer = Color(0xFFE5E7EB),
    secondary = Color(0xFF76B9FF), onSecondary = Color(0xFF081421),
    secondaryContainer = Color(0xFF25272B), onSecondaryContainer = Color(0xFFE1E3E8),
    background = Color(0xFF0B0C0E), onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF0B0C0E), onSurface = Color(0xFFF4F5F7),
    surfaceVariant = Color(0xFF49454F), onSurfaceVariant = Color(0xFF9B9FA8),
    surfaceContainerLowest = Color(0xFF0B0C0E), surfaceContainerLow = Color(0xFF14161A),
    surfaceContainer = Color(0xFF191B1F), surfaceContainerHigh = Color(0xFF24262B), surfaceContainerHighest = Color(0xFF303238),
    outline = Color(0xFF74777E), outlineVariant = Color(0xFF292C31),
    error = Color(0xFFFF9999), onError = Color(0xFF401010),
    errorContainer = Color(0xFF402626), onErrorContainer = Color(0xFFFFAAAA),
)

internal fun cmuxLightColorScheme() = lightColorScheme(
    primary = Color(0xFF007AFF), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2F0FF), onPrimaryContainer = Color(0xFF003566),
    secondary = Color(0xFF007AFF), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5EA), onSecondaryContainer = Color(0xFF1C1C1E),
    background = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFF2F2F7), onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF636366),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFE5E5EA),
    outline = Color(0xFF8E8E93), outlineVariant = Color(0xFFD1D1D6),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFDE7E7), onErrorContainer = Color(0xFF8C1D18),
)

internal object NativeAppColors {
    val dark: Boolean @Composable get() = MaterialTheme.colorScheme.background.luminance() < .5f
    val page: Color @Composable get() = MaterialTheme.colorScheme.background
    val panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
    val text: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val accent: Color @Composable get() = MaterialTheme.colorScheme.primary
    val line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
    val error: Color @Composable get() = MaterialTheme.colorScheme.error
    val errorContainer: Color @Composable get() = MaterialTheme.colorScheme.errorContainer
    val onErrorContainer: Color @Composable get() = MaterialTheme.colorScheme.onErrorContainer
    val warning: Color @Composable get() = if (dark) Color(0xFFFFBC70) else Color(0xFF8A4B00)
    val success: Color @Composable get() = if (dark) Color(0xFF69CE8D) else Color(0xFF237A3B)
    val control: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
    val onControl: Color @Composable get() = MaterialTheme.colorScheme.onSecondaryContainer
    val selected: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
}

private fun Context.activity(): Activity? {
    var value = this
    while (value is ContextWrapper) {
        if (value is Activity) return value
        val next = value.baseContext
        if (next === value) return null
        value = next
    }
    return value as? Activity
}

@Composable
internal fun NativeSystemBarAppearance(dark: Boolean, darkNavigation: Boolean = dark) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: LocalContext.current.activity()?.window
    val latest by rememberUpdatedState(NativeBarAppearance(dark, darkNavigation))
    fun apply() { window?.let {
        WindowCompat.getInsetsController(it, view).apply {
            isAppearanceLightStatusBars = !latest.darkStatus
            isAppearanceLightNavigationBars = !latest.darkNavigation
        }
    } }
    SideEffect { apply() }
    DisposableEffect(view, window) {
        // Child dialogs/media can take focus without recomposing their parent window.
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) apply() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener) }
    }
}
