package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Discovery enriches the visible workspace. It never chooses an unrelated pane over a user's current pane. */
@Composable
internal fun NativeWorkspaceBrowserDiscovery(navigation: NativeWorkspaceTabNavigation, login: String?,
    workspaceKey: NativeWorkspaceTabKey?, client: MobileRpcClient?, ready: Boolean, enabled: Boolean,
    readListing: suspend (MobileRpcClient) -> NativeWorkspaceSnapshot, isCurrent: () -> Boolean,
    onInventory: (NativeWorkspaceSnapshot) -> Unit, onMissing: () -> Unit) {
    val currentScope by rememberUpdatedState(login to workspaceKey)
    val currentClient by rememberUpdatedState(client)
    val current by rememberUpdatedState(isCurrent)
    val read by rememberUpdatedState(readListing)
    val publish by rememberUpdatedState(onInventory)
    val missing by rememberUpdatedState(onMissing)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(login, workspaceKey, client, ready, enabled, lifecycle) {
        val expectedLogin = login ?: return@LaunchedEffect
        val key = workspaceKey ?: return@LaunchedEffect
        val active = client ?: return@LaunchedEffect
        if (!ready || !enabled) return@LaunchedEffect
        fun valid() = currentScope == (expectedLogin to key) && currentClient === active &&
            navigation.pending.value == null && current()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (valid()) {
                try {
                    val listing = read(active)
                    if (!valid()) return@repeatOnLifecycle
                    val workspace = listing.workspaces.singleOrNull { it.id == key.workspaceId }
                    if (workspace == null) {
                        if (!listing.accept()) throw NativeWorkspaceSnapshotSuperseded()
                        publish(listing)
                        missing(); return@repeatOnLifecycle
                    }
                    if (!listing.accept()) throw NativeWorkspaceSnapshotSuperseded()
                    publish(listing)
                    if (!valid()) return@repeatOnLifecycle
                    val browsers = withTimeoutOrNull(15_000) { parseWorkspaceBrowserPanels(active.browserPanels(workspace.id), workspace.id) }
                    if (!valid()) return@repeatOnLifecycle
                    if (browsers != null) {
                        if (!listing.accept()) throw NativeWorkspaceSnapshotSuperseded()
                        if (navigation.discover(expectedLogin, key, browsers)) publish(listing)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    // Failed discovery is not confirmed absence. Keep the last visible pane/cache.
                }
                delay(5_000)
            }
        }
    }
}
