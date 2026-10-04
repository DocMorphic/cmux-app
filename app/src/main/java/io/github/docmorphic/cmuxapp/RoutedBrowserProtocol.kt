package io.github.docmorphic.cmuxapp

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

internal object RoutedBrowserProtocol {
    const val OPEN = 1
    const val PREPARE = 2
    const val SNAPSHOT = 3
    const val FOREGROUND = 4
    const val DEBUG_LOGS = 5
    const val CUSTOMIZE = 6
    const val CANCEL_CUSTOMIZE = 7
    const val SIDEBAR = 8
    const val SIDEBAR_SELECT = 9
    const val SIDEBAR_STATE = 10
    const val SIDEBAR_SORT = 11
    const val SIDEBAR_NOTIFICATION = 12
    const val CANCEL_NOTIFICATION = 13
    const val SIDEBAR_MUTATION = 14
    const val CANCEL_MUTATION = 15
    const val SIDEBAR_GROUP_MENU = 16
    const val RETIRE = 100
    const val CONTEXT = 101
    const val EXTRA = "browser_request"

    fun panes(workspace: NativeWorkspace, browserState: NativeBrowserPickerState = NativeBrowserPickerState()) = nativePanePickerRows(workspace, browserState)
    fun browserState(bundle: Bundle) = NativeBrowserPickerState(bundle.getBoolean("browser_support_known"), bundle.getBoolean("browser_streaming", true))
    fun context(workspace: NativeWorkspace, modes: Boolean = false, linkedPanel: String? = null, creationEnabled: Boolean = false,
        sshPicker: SshPickerPresentation? = null, browserState: NativeBrowserPickerState = NativeBrowserPickerState(), customizationEnabled: Boolean = false,
        sidebarAvailable: Boolean = false) = Bundle().apply {
        putBoolean("sidebar_available", sidebarAvailable)
        putBoolean("browser_support_known", browserState.known); putBoolean("browser_streaming", browserState.streaming)
        putString("ssh_picker", sshPicker?.encode())
        putBoolean("creation_enabled", creationEnabled); putBoolean("modes", modes); putString("linked_panel", linkedPanel)
        putString("workspace", workspace.title)
        putString("workspace_id", workspace.id)
        putBundle("customization", if (customizationEnabled && sshPicker == null) RoutedWorkspaceCustomizationProtocol.draft(WorkspaceCustomizationDraft.from(workspace)) else null)
        putString("panes", JSONArray().also { rows -> panes(workspace, browserState).forEach {
            rows.put(JSONObject().put("kind", it.kind).put("id", it.id).put("title", it.title).put("simulator", it.simulator).put("fallback_browser", it.fallbackBrowser).put("surface_kind", it.surfaceKind))
        } }.toString())
    }
    fun panes(bundle: Bundle): List<NativePanePickerRow> = JSONArray(bundle.getString("panes") ?: "[]").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).let { row -> NativePanePickerRow(row.getString("kind"), row.getString("id"), row.getString("title"), row.optBoolean("simulator"), row.optBoolean("fallback_browser"), row.optString("surface_kind").takeIf { it.isNotEmpty() }) } }
    }
    fun snapshot(value: LocalBrowserSnapshot) = Bundle().apply {
        putString("url", value.url); putString("title", value.title); putString("address", value.address)
        putBoolean("editing", value.editing); putBoolean("loading", value.loading); putFloat("progress", value.progress)
        putBoolean("back", value.canGoBack); putBoolean("forward", value.canGoForward); putString("error", value.error)
    }
    fun snapshot(value: Bundle) = LocalBrowserSnapshot(
        url = value.getString("url"), title = value.getString("title"), address = value.getString("address").orEmpty(),
        editing = value.getBoolean("editing"), loading = value.getBoolean("loading"), progress = value.getFloat("progress").coerceIn(0f, 1f),
        canGoBack = value.getBoolean("back"), canGoForward = value.getBoolean("forward"), error = value.getString("error"))
}
