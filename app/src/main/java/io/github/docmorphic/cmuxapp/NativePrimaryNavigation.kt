package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val navigationAccent = Color(0xFF76B9FF)
private val navigationText = Color(0xFFE5E7EB)
private val pill = RoundedCornerShape(40.dp)
private fun Modifier.navigationSurface() = shadow(8.dp, pill).clip(pill)
    .background(Brush.verticalGradient(listOf(Color(0xF22A2D34), Color(0xF21B1D22))))
    .border(0.7.dp, Color(0xFF42464F), pill)

/** iOS 26 primary-tab structure, with Android focus/IME and accessibility semantics. */
@Composable
internal fun NativePrimaryNavigation(
    notificationTab: Boolean, unreadCount: Int, search: NativeSearchState,
    onTab: (Boolean) -> Unit, onBeginSearch: () -> Unit, onEdit: (String, Long) -> Unit,
    onSubmit: () -> Unit, onCancel: () -> Unit,
    sidebar: Boolean = false, onNewTask: (() -> Unit)? = null,
    cloudTab: Boolean = false, onCloud: (() -> Unit)? = null
) {
    val scope = if (notificationTab) NativeSearchScope.NOTIFICATIONS else NativeSearchScope.WORKSPACES
    val label = if (notificationTab) "Search notifications" else "Search workspaces"
    val query = search.text(scope)
    val active = !cloudTab && search.active == scope
    Row(Modifier.fillMaxWidth().padding(horizontal = if (sidebar) 8.dp else 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(if (sidebar) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            val generation = search.generation
            LaunchedEffect(scope, generation) {
                withFrameNanos { }
                focus.requestFocus()
                keyboard?.show()
            }
            TextField(query, { onEdit(it, generation) }, modifier = Modifier.weight(1f)
                .heightIn(min = 62.dp).navigationSurface().focusRequester(focus),
                placeholder = { Text(label, fontSize = 15.sp) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_primary_search), null, Modifier.size(20.dp)) },
                singleLine = true, shape = pill,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent, cursorColor = navigationAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }))
            if (sidebar) IconButton(onClick = onCancel) { Icon(painterResource(R.drawable.ic_primary_close), "Cancel search") }
            else NavigationCircle(R.drawable.ic_primary_close, "Cancel search", onCancel)
        } else {
            Row(Modifier.weight(1f).then(if (sidebar) Modifier else Modifier.navigationSurface().padding(4.dp))) {
                PrimaryTab("Workspaces", "Workspaces", R.drawable.ic_primary_workspaces, !notificationTab && !cloudTab,
                    0, Modifier.weight(1f), sidebar) { onTab(false) }
                PrimaryTab("Notifications", if (unreadCount > 0) "Notifications ($unreadCount)" else "Notifications",
                    R.drawable.ic_feed_bell, notificationTab && !cloudTab, unreadCount, Modifier.weight(1f), sidebar) { onTab(true) }
                onCloud?.let { PrimaryTab("Cloud", "Cloud", R.drawable.ic_workspace_cloud, cloudTab, 0, Modifier.weight(1f), sidebar, it) }
            }
            if (!cloudTab && sidebar) {
                IconButton(onClick = onBeginSearch, modifier = Modifier.semantics {
                    stateDescription = if (query.isBlank()) label else "$label: $query"
                }) { Icon(painterResource(R.drawable.ic_primary_search), "Search", tint = if (query.isNotBlank()) navigationAccent else navigationText) }
                if (onNewTask != null) IconButton(onClick = onNewTask) {
                    Icon(painterResource(R.drawable.ic_primary_compose), "New Task", tint = navigationText)
                }
            } else if (!cloudTab) NavigationCircle(R.drawable.ic_primary_search, "Search", onBeginSearch,
                accent = query.isNotBlank(), description = if (query.isBlank()) label else "$label: $query")
        }
    }
}

@Composable
private fun PrimaryTab(label: String, spokenLabel: String, icon: Int, active: Boolean,
    unreadCount: Int, modifier: Modifier, sidebar: Boolean = false, action: () -> Unit) {
    Column(modifier.height(58.dp).clip(if (sidebar) RoundedCornerShape(8.dp) else pill)
        .background(if (active && !sidebar) Color(0xFF3B3F48) else Color.Transparent)
        .selectable(active, role = Role.Tab, onClick = action).clearAndSetSemantics {
            text = AnnotatedString(spokenLabel); selected = active; role = Role.Tab
            onClick { action(); true }
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box {
            Icon(painterResource(icon), null, Modifier.size(23.dp), tint = if (active) navigationAccent else navigationText)
            if (unreadCount > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 13.dp, y = (-5).dp)
                .background(Color(0xFFE84F58), CircleShape).defaultMinSize(minWidth = 17.dp)
                .padding(horizontal = 4.dp, vertical = 1.dp), contentAlignment = Alignment.Center) {
                Text(if (unreadCount > 99) "99+" else unreadCount.toString(), color = Color.White,
                    fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (active) navigationAccent else navigationText, fontSize = 11.sp,
            lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun NavigationCircle(icon: Int, label: String, action: () -> Unit,
    modifier: Modifier = Modifier, accent: Boolean = false, description: String? = null, enabled: Boolean = true) {
    Box(modifier.size(62.dp).navigationSurface().clickable(enabled = enabled, role = Role.Button, onClick = action)
        .semantics { contentDescription = label; description?.let { stateDescription = it } },
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(25.dp), tint = (if (accent) navigationAccent else navigationText).copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
internal fun NativeTaskComposerButton(modifier: Modifier = Modifier, enabled: Boolean, onClick: () -> Unit) =
    NavigationCircle(R.drawable.ic_primary_compose, "New Task", onClick, modifier, enabled = enabled)
