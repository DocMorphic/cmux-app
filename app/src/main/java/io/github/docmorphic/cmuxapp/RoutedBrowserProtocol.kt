package io.github.docmorphic.cmuxapp

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

internal object RoutedBrowserProtocol {
    const val OPEN = 1
    const val PREPARE = 2
    const val SNAPSHOT = 3
    const val FOREGROUND = 4
    const val RETIRE = 100
    const val CONTEXT = 101
    const val EXTRA = "browser_request"

    fun panes(workspace: NativeWorkspace) = nativePanePickerRows(workspace)
    fun context(workspace: NativeWorkspace, modes: Boolean = false, linkedPanel: String? = null, creationEnabled: Boolean = false) = Bundle().apply {
        putBoolean("creation_enabled", creationEnabled); putBoolean("modes", modes); putString("linked_panel", linkedPanel)
        putString("workspace", workspace.title)
        putString("panes", JSONArray().also { rows -> panes(workspace).forEach {
            rows.put(JSONObject().put("kind", it.kind).put("id", it.id).put("title", it.title).put("simulator", it.simulator))
        } }.toString())
    }
    fun panes(bundle: Bundle): List<NativePanePickerRow> = JSONArray(bundle.getString("panes") ?: "[]").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).let { row -> NativePanePickerRow(row.getString("kind"), row.getString("id"), row.getString("title"), row.optBoolean("simulator")) } }
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
