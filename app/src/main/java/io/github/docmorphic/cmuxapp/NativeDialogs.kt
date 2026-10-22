package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Each window owns its icon appearance. Alerts inherit the surface behind their system bars. */
internal data class NativeBarAppearance(val darkStatus: Boolean, val darkNavigation: Boolean = darkStatus)
internal val LocalNativeBarAppearance = compositionLocalOf<NativeBarAppearance?> { null }

@Composable
internal fun NativeDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(),
    darkSystemBars: Boolean = NativeAppColors.dark, content: @Composable () -> Unit) {
    val appearance = NativeBarAppearance(darkSystemBars)
    androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
        NativeSystemBarAppearance(appearance.darkStatus, appearance.darkNavigation)
        CompositionLocalProvider(LocalNativeBarAppearance provides appearance, content = content)
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
    androidx.compose.material3.AlertDialog(onDismissRequest, confirmButton = {
        NativeSystemBarAppearance(appearance.darkStatus, appearance.darkNavigation)
        confirmButton()
    }, modifier, dismissButton, icon, title, text, shape, containerColor, iconContentColor,
        titleContentColor, textContentColor, tonalElevation, properties)
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
    androidx.compose.material3.ModalBottomSheet(onDismissRequest, modifier, sheetState, sheetMaxWidth, shape,
        containerColor, contentColor, tonalElevation, scrimColor, dragHandle, contentWindowInsets, properties) {
        NativeSystemBarAppearance(appearance.darkStatus, appearance.darkNavigation)
        CompositionLocalProvider(LocalNativeBarAppearance provides appearance) { content() }
    }
}
