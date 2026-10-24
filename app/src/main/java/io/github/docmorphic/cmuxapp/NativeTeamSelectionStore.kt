package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Encrypted presentation preference; no login, membership or connection authority. */
internal object NativeTeamSelectionStore {
    const val KEY = "auth_team_selection"
    private data class Saved(val environment:String,val selected:String?)
    fun normalized(value:String?)=value?.let(NativeChiefTextPolicy::trim)?.takeIf(String::isNotEmpty)
    private fun record(value:JSONObject?):Saved? {
        if(value?.opt("version")!=1)return null
        val environment=(value.opt("environment") as? String)?.takeIf(String::isNotEmpty) ?: return null
        val raw=value.opt("selected")
        if(raw!=null && raw!==JSONObject.NULL && raw !is String)return null
        return Saved(environment,normalized(raw as? String))
    }
    private fun saved(root:JSONObject?):Saved? = if(root?.has(KEY)==true)record(root.optJSONObject(KEY))
        else record(root?.optJSONObject(NativeAccountProfileCache.KEY))
    fun read(root:JSONObject?,environment:String):String?=saved(root)?.takeIf{it.environment==environment}?.selected
    fun hasForeignEnvironment(root:JSONObject?,environment:String)=saved(root)?.let{it.environment!=environment}==true
    /** Migrate before pruning the old card, even when a new receipt already owns the tokens. */
    fun migrate(root:JSONObject) {
        if(root.has(KEY))return
        record(root.optJSONObject(NativeAccountProfileCache.KEY))?.let{write(root,it.environment,it.selected)}
    }
    fun write(root:JSONObject,environment:String,selected:String?) {
        // An explicit nil record also prevents a legacy card from resurrecting a cleared choice.
        root.put(KEY,JSONObject().put("version",1).put("environment",environment)
            .put("selected",normalized(selected) ?: JSONObject.NULL))
    }
    fun clear(root:JSONObject){root.remove(KEY)}
}
