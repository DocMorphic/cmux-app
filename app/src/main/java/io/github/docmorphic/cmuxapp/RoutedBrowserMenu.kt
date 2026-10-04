package io.github.docmorphic.cmuxapp

/** One owner-scoped read used for publication and again when accepting a browser result. */
internal data class RoutedBrowserMenu(val workspace: NativeWorkspace, val creationEnabled: Boolean = false,
    val sshPicker: SshPickerPresentation? = null, val browserState: NativeBrowserPickerState = NativeBrowserPickerState(), val customizationEnabled: Boolean = false)
