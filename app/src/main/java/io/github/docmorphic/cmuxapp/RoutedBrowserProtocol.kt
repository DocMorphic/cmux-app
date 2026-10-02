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

    data class Pane(val kind: String, val id: String, val title: String)
    fun panes(workspace: NativeWorkspace) = workspace.terminals.map { Pane("terminal", it.id, it.title.ifBlank { "Terminal" }) } +
        workspace.browsers.map { Pane("browser", it.id, it.title.ifBlank { "Browser" }) } +
        workspace.macSurfaces.map { Pane("surface", it.id, it.displayTitle) }
    fun context(workspace: NativeWorkspace, modes: Boolean = false, linkedPanel: String? = null) = Bundle().apply {
        putBoolean("modes", modes); putString("linked_panel", linkedPanel)
        putString("workspace", workspace.title)
        putString("panes", JSONArray().also { rows -> panes(workspace).forEach {
            rows.put(JSONObject().put("kind", it.kind).put("id", it.id).put("title", it.title))
        } }.toString())
    }
    fun panes(bundle: Bundle): List<Pane> = JSONArray(bundle.getString("panes") ?: "[]").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).let { row -> Pane(row.getString("kind"), row.getString("id"), row.getString("title")) } }
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
