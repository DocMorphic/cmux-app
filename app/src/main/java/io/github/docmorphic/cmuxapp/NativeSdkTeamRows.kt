package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** CurrentUser.listTeams' successful-response projection, separate from transport failure. */
internal object NativeSdkTeamRows {
    fun decode(value:JSONObject):List<NativeTeam> {
        val items=value.optJSONArray("items") ?: return emptyList()
        if((0 until items.length()).any{items.opt(it) !is JSONObject})return emptyList()
        return (0 until items.length()).map{index->val row=items.getJSONObject(index)
            NativeTeam(row.opt("id") as? String ?: "",row.opt("display_name") as? String ?: "")}
    }
}
