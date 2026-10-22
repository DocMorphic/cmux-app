package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Each window owns its icon appearance. Alerts inherit the surface behind their system bars. */
internal data class NativeBarAppearance(val darkStatus: Boolean, val darkNavigation: Boolean = darkStatus)
internal val LocalNativeBarAppearance = compositionLocalOf<NativeBarAppearance?> { null }

private class NativeDialogEnvironment(val context: Context, val configuration: Configuration,
    val density: Density, val direction: LayoutDirection) {
    @Composable fun Content(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
            LocalDensity provides density, LocalLayoutDirection provides direction, content = content)
    }
}

@Composable
private fun dialogEnvironment(): NativeDialogEnvironment {
    val context = LocalContext.current
    val configuration = Configuration(LocalConfiguration.current)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return remember(context, configuration, density, direction) { NativeDialogEnvironment(context, configuration, density, direction) }
}

private fun dialogSlot(environment: NativeDialogEnvironment, content: (@Composable () -> Unit)?): (@Composable () -> Unit)? {
    if (content == null) return null
    return { environment.Content(content) }
}

@Composable
internal fun NativeDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(),
    darkSystemBars: Boolean = NativeAppColors.dark, content: @Composable () -> Unit) {
    val appearance = NativeBarAppearance(darkSystemBars)
    val environment = dialogEnvironment()
    // Pinned Compose 1.9.1 keys its window by Density but show() by Unit. Its
    // constructor uses Density only for an 8dp shadow: font scale is irrelevant
    // there. Keep that key stable while restoring the real scale for all content.
    val windowDensity = remember(environment.density.density) { Density(environment.density.density, 1f) }
    CompositionLocalProvider(LocalDensity provides windowDensity) {
        androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
            environment.Content {
                NativeSystemBarAppearance(appearance.darkStatus, appearance.darkNavigation)
                CompositionLocalProvider(LocalNativeBarAppearance provides appearance, content = content)
            }
        }
    }
}

@Composable
internal fun NativeAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null, title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null, shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation, properties: DialogProperties = DialogProperties()) {
    val appearance = LocalNativeBarAppearance.current ?: NativeBarAppearance(NativeAppColors.dark)
    val environment = dialogEnvironment()
    val windowDensity = remember(environment.density.density) { Density(environment.density.density, 1f) }
    CompositionLocalProvider(LocalDensity provides windowDensity) {
        androidx.compose.material3.AlertDialog(onDismissRequest, confirmButton = {
            environment.Content {
                NativeSystemBarAppearance(appearance.darkStatus, appearance.darkNavigation)
                confirmButton()
            }
        }, modifier, dialogSlot(environment, dismissButton), dialogSlot(environment, icon),
            dialogSlot(environment, title), dialogSlot(environment, text), shape, containerColor, iconContentColor,
            titleContentColor, textContentColor, tonalElevation, properties)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeModalBottomSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(), sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = BottomSheetDefaults.ExpandedShape, containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor), tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit) {
    val parent = LocalNativeBarAppearance.current ?: NativeBarAppearance(NativeAppColors.dark)
    // The status bar shows the dimmed parent; navigation rests on the sheet container.
    val appearance = NativeBarAppearance(parent.darkStatus, containerColor.luminance() < .5f)
    val environment = dialogEnvironment()
    val windowDensity = remember(environment.density.density) { Density(environment.density.density, 1f) }
    CompositionLocalProvider(LocalDensity provides windowDensity) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest, modifier, sheetState, sheetMaxWidth, shape,
            containerColor, contentColor, tonalElevation, scrimColor, dialogSlot(environment, dragHandle), contentWindowInsets, properties) {
            environment.Content {
                NativeSystemBarAppearance(appearance.darkStatus, appearance.darkNavigation)
                CompositionLocalProvider(LocalNativeBarAppearance provides appearance) { content() }
            }
        }
    }
}
