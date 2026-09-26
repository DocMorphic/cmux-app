package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/** Personal Mac helper pairing URL. The host must be a Tailscale IPv4 address. */
data class BridgePairing(val host: String, val port: Int, val token: String) {
    val baseUrl: String get() = "http://$host:$port"

    companion object {
        fun parse(text: String): BridgePairing {
            require(text.length <= 4096) { "Pairing URL is too long" }
            val uri = URI(text.trim())
            require(uri.scheme == "cmux-app" && uri.host == "pair") { "Expected a cmux-app helper pairing URL" }
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
                val pieces = part.split('=', limit = 2)
                require(pieces.size == 2) { "Malformed pairing URL" }
                pieces[0] to URLDecoder.decode(pieces[1], "UTF-8")
            }
            val host = query["host"].orEmpty()
            require(isTailscaleIPv4(host)) { "The Mac must use a Tailscale IPv4 address" }
            val port = query["port"]?.toIntOrNull() ?: error("Missing helper port")
            require(port in 1..65535) { "Invalid helper port" }
            val token = query["token"].orEmpty()
            require(Regex("^[0-9a-f]{64}$").matches(token)) { "Invalid pairing token" }
            return BridgePairing(host, port, token)
        }

        private fun isTailscaleIPv4(host: String): Boolean {
            val parts = host.split('.').map { it.toIntOrNull() ?: return false }
            return parts.size == 4 && parts.all { it in 0..255 } && parts[0] == 100 && parts[1] in 64..127
        }
    }
}

data class BridgeTerminal(val id: String, val title: String)
data class BridgeWorkspace(val id: String, val title: String, val terminals: List<BridgeTerminal>)

class BridgeClient(private val pairing: BridgePairing) {
    suspend fun workspaces(): List<BridgeWorkspace> = withContext(Dispatchers.IO) {
        val root = JSONObject(request("GET", "/v1/tree"))
        val result = mutableListOf<BridgeWorkspace>()
        val windows = root.optJSONArray("windows") ?: return@withContext result
        for (windowIndex in 0 until windows.length()) {
            val workspaces = windows.optJSONObject(windowIndex)?.optJSONArray("workspaces") ?: continue
            for (workspaceIndex in 0 until workspaces.length()) {
                val workspace = workspaces.optJSONObject(workspaceIndex) ?: continue
                val terminals = mutableListOf<BridgeTerminal>()
                val panes = workspace.optJSONArray("panes")
                if (panes != null) for (paneIndex in 0 until panes.length()) {
                    val surfaces = panes.optJSONObject(paneIndex)?.optJSONArray("surfaces") ?: continue
                    for (surfaceIndex in 0 until surfaces.length()) {
                        val surface = surfaces.optJSONObject(surfaceIndex) ?: continue
                        if (surface.optString("type") == "terminal") {
                            val id = surface.optString("id")
                            if (id.isNotBlank()) terminals += BridgeTerminal(id, surface.optString("title", "Terminal"))
                        }
                    }
                }
                val id = workspace.optString("id")
                if (id.isNotBlank()) result += BridgeWorkspace(id, workspace.optString("title", "Workspace"), terminals)
            }
        }
        result
    }

    suspend fun screen(surface: String): String = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(surface, "UTF-8")
        JSONObject(request("GET", "/v1/screen?surface=$encoded&lines=120")).getString("text")
    }

    suspend fun sendText(surface: String, text: String) = withContext(Dispatchers.IO) {
        request("POST", "/v1/input", JSONObject().put("surface", surface).put("text", text).toString())
    }

    suspend fun sendKey(surface: String, key: String) = withContext(Dispatchers.IO) {
        request("POST", "/v1/key", JSONObject().put("surface", surface).put("key", key).toString())
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val connection = URL(pairing.baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Authorization", "Bearer ${pairing.token}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val message = runCatching { JSONObject(response).optString("error") }.getOrNull().orEmpty()
                error(message.ifBlank { "Mac helper returned HTTP ${connection.responseCode}" })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
