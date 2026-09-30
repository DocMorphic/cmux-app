package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import org.json.JSONArray
import org.json.JSONObject

/** Navigation only. File bytes, metadata caches and RPC clients never enter Android task state. */
internal class ArtifactNavigationState {
    var destinations by mutableStateOf<List<ArtifactDestination>>(emptyList())
    var selectedPath by mutableStateOf<String?>(null)
    var sessionScope by mutableStateOf(true)
    var searchText by mutableStateOf("")
    var grid by mutableStateOf(false)
    var filter by mutableStateOf(ArtifactFilter.ALL)
    var sort by mutableStateOf(ArtifactSort.RECENT)
    var folded by mutableStateOf(emptySet<String>())
    fun open(destination: ArtifactDestination) {
        destinations = destinations + destination
        selectedPath = (destination as? ArtifactDestination.Preview)?.initialPath
    }
    fun back() { destinations = destinations.dropLast(1); selectedPath = null }
    fun clearRoutes() { destinations = emptyList(); selectedPath = null }
    fun matches(terminal: ArtifactAuthorization.Terminal, session: ArtifactAuthorization.Session?) =
        destinations.all { it.authorization == terminal || session != null && it.authorization == session }
}

internal class TerminalFilesState {
    var showing by mutableStateOf(false)
    var path by mutableStateOf<String?>(null)
    var gallery = ArtifactNavigationState()
    var direct = ArtifactNavigationState()
    fun closeGallery() { showing = false; gallery = ArtifactNavigationState() }
    fun closePath() { path = null; direct = ArtifactNavigationState() }
    fun openPath(value: String) { direct = ArtifactNavigationState(); path = value }
}

internal class TerminalFilesMemory {
    private var login: String? = null
    private var scope: String? = null
    private var surface: String? = null
    private var state = TerminalFilesState()
    fun bind(login: String, key: NativeWorkspaceTabKey, surface: String): TerminalFilesState {
        if (this.login != login || scope != key.encoded || this.surface != surface) {
            this.login = login; scope = key.encoded; this.surface = surface; state = TerminalFilesState()
        }
        return state
    }
    fun clear() { login = null; scope = null; surface = null; state = TerminalFilesState() }
    fun retainLogin(login: String?) { if (this.login != login) clear() }
    fun encode(): String = runCatching {
        val value = JSONObject().put("version", 1).put("login", checkNotNull(login)).put("scope", checkNotNull(scope))
            .put("surface", checkNotNull(surface)).put("showing", state.showing).put("path", state.path ?: JSONObject.NULL)
            .put("gallery", encodeNavigation(state.gallery)).put("direct", encodeNavigation(state.direct))
        if (value.toString().length > MAX_ENCODED) {
            // Preserve the owning sheet when an unusually large pager cannot fit in task state.
            value.getJSONObject("gallery").put("routes", JSONArray())
            value.getJSONObject("direct").put("routes", JSONArray())
        }
        value.toString().takeIf { it.length <= MAX_ENCODED }.orEmpty()
    }.getOrDefault("")
    companion object {
        private const val MAX_ENCODED = 98_304
        private fun JSONObject.text(key: String, limit: Int = 4096): String = (get(key) as? String)?.also {
            require(it.isNotBlank() && it.length <= limit && '\u0000' !in it)
        } ?: error("Invalid saved Files state")
        private fun encodeNavigation(state: ArtifactNavigationState): JSONObject {
            val routes = state.destinations.mapIndexed { index, destination ->
                val value = JSONObject()
                when (val scope = destination.authorization) {
                    is ArtifactAuthorization.Terminal -> value.put("workspace", scope.workspaceId).put("terminal", scope.surfaceId)
                    is ArtifactAuthorization.Session -> value.put("session", scope.sessionId)
                    is ArtifactAuthorization.Panel -> error("Panel is not a Files route")
                }
                when (destination) {
                    is ArtifactDestination.Folder -> value.put("folder", destination.item.path)
                    is ArtifactDestination.Preview -> value.put("selected", if (index == state.destinations.lastIndex)
                        state.selectedPath ?: destination.initialPath else destination.initialPath)
                        .put("files", JSONArray(destination.files.map { JSONArray(listOf(it.path, it.kind.name, it.displayName)) }))
                }
                value
            }
            return JSONObject().put("routes", JSONArray(routes)).put("session_scope", state.sessionScope)
                .put("query", state.searchText.take(4096)).put("grid", state.grid).put("filter", state.filter.name)
                .put("sort", state.sort.name).put("folded", JSONArray(state.folded.sorted().take(32)))
        }
        private fun decodeNavigation(value: JSONObject): ArtifactNavigationState = ArtifactNavigationState().also { state ->
            state.sessionScope = value.get("session_scope") as Boolean
            state.grid = value.get("grid") as Boolean
            state.searchText = (value.get("query") as String).also { require(it.length <= 4096) }
            state.filter = ArtifactFilter.valueOf(value.text("filter")); state.sort = ArtifactSort.valueOf(value.text("sort"))
            val folded = value.getJSONArray("folded"); require(folded.length() <= 32)
            state.folded = (0 until folded.length()).map { folded.getString(it).also { require(it.length <= 4096) } }.toSet()
            val routes = value.getJSONArray("routes"); require(routes.length() <= 2000)
            state.destinations = (0 until routes.length()).map { index ->
                val route = routes.getJSONObject(index)
                val authorization = if (route.has("session")) ArtifactAuthorization.Session(route.text("session"))
                    else ArtifactAuthorization.Terminal(route.text("workspace"), route.text("terminal"))
                fun path(value: String): String = value.also {
                    require(it.length <= 4096 && if (authorization is ArtifactAuthorization.Terminal) validTerminalArtifactPath(it) else validArtifactPath(it))
                }
                if (route.has("folder")) ArtifactDestination.Folder(ArtifactItem(path(route.text("folder")), ArtifactKind.DIRECTORY), authorization)
                else {
                    val files = route.getJSONArray("files"); require(files.length() in 1..2000)
                    val items = (0 until files.length()).map {
                        val item = files.getJSONArray(it)
                        ArtifactItem(path(item.getString(0)), ArtifactKind.valueOf(item.getString(1)).also { require(it != ArtifactKind.DIRECTORY) },
                            item.getString(2).also { require(it.length <= 4096 && '\u0000' !in it) })
                    }
                    require(items.distinctBy { it.path }.size == items.size)
                    val selected = path(route.text("selected")); require(items.any { it.path == selected })
                    ArtifactDestination.Preview(items, selected, authorization)
                }
            }
            state.selectedPath = (state.destinations.lastOrNull() as? ArtifactDestination.Preview)?.initialPath
        }
        fun decode(encoded: String): TerminalFilesMemory = runCatching {
            require(encoded.length <= MAX_ENCODED)
            val value = JSONObject(encoded); require(value.opt("version") == 1)
            TerminalFilesMemory().also { memory ->
                memory.login = value.text("login"); memory.scope = value.text("scope", 16384); memory.surface = value.text("surface")
                memory.state.showing = value.get("showing") as Boolean
                memory.state.path = if (value.isNull("path")) null else value.text("path").also { require(validTerminalArtifactPath(it)) }
                memory.state.gallery = decodeNavigation(value.getJSONObject("gallery"))
                memory.state.direct = decodeNavigation(value.getJSONObject("direct"))
            }
        }.getOrElse { TerminalFilesMemory() }
        val saver = Saver<TerminalFilesMemory, String>(save = { it.encode() }, restore = ::decode)
    }
}
