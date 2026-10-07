package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Android expanded width and noncompact height. A landscape phone still uses a stack. */
internal fun usesWorkspaceSidebar(widthDp: Int, heightDp: Int): Boolean = widthDp >= 840 && heightDp >= 480

internal data class WorkspaceShellChrome(val split: Boolean = false, val sidebarVisible: Boolean = false,
    val toggleSidebar: () -> Unit = {})
internal val LocalWorkspaceShellChrome = compositionLocalOf { WorkspaceShellChrome() }

/** Move the existing composition, including AndroidViews, instead of recreating the active renderer. */
@Composable
internal fun NativeWorkspaceShell(owner: Any?, hasDetail: Boolean, allowSplit: Boolean = true,
    modifier: Modifier = Modifier, widthDp: Int = LocalConfiguration.current.screenWidthDp,
    heightDp: Int = LocalConfiguration.current.screenHeightDp,
    onSearchBack: (() -> Unit)? = null, onSidebarHidden: (() -> Unit)? = null,
    sidebar: @Composable ColumnScope.() -> Unit, detail: @Composable ColumnScope.() -> Unit) {
    key(owner) {
        val currentSidebar by rememberUpdatedState(sidebar)
        val currentDetail by rememberUpdatedState(detail)
        val sidebarState = rememberSaveableStateHolder()
        val sidebarContent = remember { movableContentOf {
            sidebarState.SaveableStateProvider("sidebar") { Column(Modifier.fillMaxSize()) { currentSidebar() } }
        } }
        val detailContent = remember { movableContentOf { Column(Modifier.fillMaxSize()) { currentDetail() } } }
        var showSidebar by rememberSaveable { mutableStateOf(true) }
        val split = allowSplit && usesWorkspaceSidebar(widthDp, heightDp)
        val visible = split && showSidebar
        val chrome = WorkspaceShellChrome(split, visible) { showSidebar = !visible }
        val hidden by rememberUpdatedState(onSidebarHidden)
        LaunchedEffect(split, visible, hasDetail) {
            if ((split && !visible) || (!split && hasDetail)) hidden?.invoke()
        }
        CompositionLocalProvider(LocalWorkspaceShellChrome provides chrome) {
            if (split) Row(modifier.testTag("workspace.shell.split")) {
                if (visible) {
                    Box(Modifier.width(380.dp).fillMaxHeight().testTag("workspace.shell.sidebar")) { sidebarContent() }
                    VerticalDivider(color = Color(0xFF292C31))
                }
                Box(Modifier.weight(1f).fillMaxHeight().testTag("workspace.shell.detail")) {
                    if (hasDetail) detailContent() else NativeWorkspaceSelectionPlaceholder()
                }
            } else Box(modifier.testTag("workspace.shell.stack")) {
                if (hasDetail) detailContent() else sidebarContent()
            }
            // Register when search starts, after any already-mounted detail handler.
            // A permanently registered disabled callback could precede a later-opened pane.
            if (visible && onSearchBack != null) BackHandler { onSearchBack() }
        }
    }
}

@Composable
internal fun NativeWorkspaceSidebarToggle() {
    val chrome = LocalWorkspaceShellChrome.current
    if (chrome.split) IconButton(onClick = chrome.toggleSidebar) {
        Icon(painterResource(R.drawable.ic_workspace_sidebar),
            if (chrome.sidebarVisible) "Hide sidebar" else "Show sidebar", tint = Color(0xFF76B9FF))
    }
}

/** Only replace the workspace-level back control; nested file/browser navigation stays intact. */
@Composable
internal fun NativeWorkspaceBackControl(compact: @Composable () -> Unit) {
    val chrome = LocalWorkspaceShellChrome.current
    if (!chrome.split) compact() else if (!chrome.sidebarVisible) NativeWorkspaceSidebarToggle()
}

@Composable
internal fun NativeWorkspaceSelectionPlaceholder() {
    Box(Modifier.fillMaxSize().testTag("workspace.shell.placeholder"), contentAlignment = Alignment.Center) {
        Text("Select a workspace", color = Color(0xFF9B9FA8))
        Box(Modifier.align(Alignment.TopStart)) { NativeWorkspaceBackControl {} }
    }
}
