package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Preserve the open wire kind and host identity, including kinds newer than this client. */
internal data class NativeSurface(val id: String, val kind: String, val title: String,
    val isFocused: Boolean = false, val filePath: String? = null, val todoJson: String? = null,
    val simulator: NativeSimulator? = null) {
    val label get() = when (kind) {
        "terminal" -> "Terminal"
        "browser" -> "Browser"
        "simulatorStream" -> "Simulator"
        "markdown" -> "Markdown"
        "filePreview" -> "File Preview"
        "todo" -> "Todo"
        "rightSidebarTool" -> "Sidebar Tool"
        "customSidebar" -> "Custom Sidebar"
        "agentSession" -> "Agent Session"
        "project" -> "Project"
        "extensionBrowser" -> "Extension Browser"
        "cloudVMLoading" -> "Cloud VM"
        else -> "Other Surface"
    }
    val displayTitle get() = title.ifBlank { label }
    val isPanelFile get() = kind in setOf("filePreview", "markdown") && filePath?.let(::validArtifactPath) == true
    val explainer get() = when (kind) {
        "agentSession" -> "This agent session is running in cmux on your Mac."
        "project" -> "This pane browses the project's files on your Mac."
        "customSidebar" -> "This panel is drawn by a sidebar extension on your Mac."
        "rightSidebarTool" -> "This tool lives in the right sidebar on your Mac."
        "extensionBrowser" -> "This extension view opens in cmux on your Mac."
        "cloudVMLoading" -> "This Cloud VM is still starting up on your Mac."
        "todo" -> "This checklist is open in cmux on your Mac."
        "simulatorStream" -> "This Simulator is running in cmux on your Mac."
        "filePreview", "markdown" -> "This view is rendered by cmux on your Mac."
        else -> "This surface is open in cmux on your Mac."
    }
    companion object {
        fun read(value: JSONObject): NativeSurface? {
            val id = (value.opt("surface_id") as? String)?.takeIf { it.isNotBlank() } ?: return null
            val kind = (value.opt("kind") as? String)?.takeIf { it.isNotBlank() } ?: return null
            return NativeSurface(id, kind, value.opt("title") as? String ?: "", value.optBoolean("is_focused"),
                value.opt("file_path") as? String, value.optJSONObject("todo")?.toString())
        }
    }
}
