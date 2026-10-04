package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Plain group creates can return a legacy list without a created ID. Task/spec creates remain strict. */
internal fun createdGroupWorkspace(response: JSONObject): NativeWorkspace? {
    val workspaces = parseAuthoritativeWorkspaces(response)
    if (!response.has("created_workspace_id") || response.isNull("created_workspace_id")) return null
    val id = response.opt("created_workspace_id") as? String
    require(!id.isNullOrBlank()) { "Mac returned an invalid created workspace ID." }
    return workspaces.singleOrNull { it.id == id } ?: error("Mac did not return the created workspace.")
}
