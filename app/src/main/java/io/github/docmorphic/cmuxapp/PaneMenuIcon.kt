package io.github.docmorphic.cmuxapp

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Decorative glyphs: the menu label and selected semantics provide the spoken name. */
@Composable
internal fun PaneMenuIcon(@DrawableRes resource: Int) {
    Icon(painterResource(resource), contentDescription = null, modifier = Modifier.size(20.dp))
}

@DrawableRes
internal fun NativePanePickerRow.menuIcon(): Int = when {
    simulator -> R.drawable.ic_menu_phone
    kind == "terminal" -> R.drawable.ic_computer_terminal
    kind == "browser" -> R.drawable.ic_workspace_globe
    else -> when (surfaceKind) {
        "todo" -> R.drawable.ic_menu_checklist
        "markdown" -> R.drawable.ic_workspace_file_text
        "filePreview" -> R.drawable.ic_menu_file_search
        "browser" -> R.drawable.ic_workspace_globe
        "agentSession" -> R.drawable.ic_menu_chat
        "project" -> R.drawable.ic_workspace_hammer
        "customSidebar" -> R.drawable.ic_menu_sidebar
        "rightSidebarTool" -> R.drawable.ic_workspace_wrench
        "extensionBrowser" -> R.drawable.ic_menu_extension
        "cloudVMLoading" -> R.drawable.ic_workspace_cloud
        else -> R.drawable.ic_menu_other
    }
}

@DrawableRes
internal fun SshPaneAction.menuIcon(): Int = when (this) {
    SshPaneAction.NEW_TAB -> R.drawable.ic_menu_tab_add
    SshPaneAction.SPLIT_RIGHT -> R.drawable.ic_menu_split_right
    SshPaneAction.SPLIT_DOWN -> R.drawable.ic_menu_split_down
}
