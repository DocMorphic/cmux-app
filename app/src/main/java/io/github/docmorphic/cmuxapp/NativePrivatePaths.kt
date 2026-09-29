package io.github.docmorphic.cmuxapp

import android.content.Context
import android.util.AtomicFile
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest

/** Numeric coordinates are route hints only; the enrolled peer key still authenticates the Mac. */
internal object NativePrivateAddress {
    fun parse(raw: String): String {
        val value = raw.trim()
        require(value.length <= 90)
        val bracketed = value.startsWith("[")
        val split = if (bracketed) value.indexOf("]:") else value.indexOf(':')
        require(split > 0)
        val host = value.substring(if (bracketed) 1 else 0, split).trim()
        val portText = value.substring(split + if (bracketed) 2 else 1)
        require(portText.isNotEmpty() && portText.all { it in '0'..'9' })
        val port = portText.toIntOrNull() ?: error("Invalid port")
        require(port in 1..65535 && host.length <= 64)
        if (':' in host) {
            require(bracketed && host.all { it in "0123456789abcdefABCDEF:." })
        } else {
            val parts = host.split('.')
            require(parts.size == 4 && parts.all {
                it.isNotEmpty() && it.all { c -> c in '0'..'9' } &&
                    it.toIntOrNull() in 0..255 && it.toInt().toString() == it
            })
        }
        // HttpUrl parses IPv6 locally. The strict numeric grammar above prevents DNS resolution.
        val canonical = "http://${if (':' in host) "[$host]" else host}/".toHttpUrl().host
        val bytes = InetAddress.getByName(canonical).address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            require(bytes[0] != 0 && bytes[0] != 127 && bytes[0] < 224 &&
                !(bytes[0] == 169 && bytes[1] == 254))
        } else {
            require(bytes.size == 16 && bytes.any { it != 0 } &&
                !(bytes.take(15).all { it == 0 } && bytes[15] == 1) && bytes[0] != 255 &&
                !(bytes[0] == 254 && bytes[1] and 192 == 128) && canonical != "fd00:ec2::254")
        }
        return "${if (':' in canonical) "[$canonical]" else canonical}:$port"
    }

    fun lines(text: String): List<String> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size in 1..8)
        return lines.map(::parse)
    }
}

internal data class NativePrivatePath(val deviceId: String, val buildTag: String,
    val name: String, val addresses: List<String>, val enabled: Boolean = false) {
    override fun toString() = "NativePrivatePath(enabled=$enabled, addressCount=${addresses.size})"
}

/** One atomic, no-backup file per complete enrolled identity. No network synchronization. */
internal class NativePrivatePathStore(private val read: () -> String?, private val write: (String) -> Unit) {
    fun load(): List<NativePrivatePath> = synchronized(lock) {
        val array = JSONArray(read() ?: "[]")
        require(array.length() <= 64)
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val addresses = item.getJSONArray("addresses")
            require(addresses.length() in 1..8)
            NativePrivatePath(item.getString("deviceId"), item.getString("buildTag"), item.getString("name"),
                (0 until addresses.length()).map { NativePrivateAddress.parse(addresses.getString(it)) }, item.getBoolean("enabled"))
        }.also { paths -> require(paths.map { it.deviceId to it.buildTag }.distinct().size == paths.size) }
    }

    fun upsert(path: NativePrivatePath) = synchronized(lock) {
        require(path.deviceId.isNotBlank() && path.deviceId.length <= 128 && path.buildTag.length <= 64 && path.name.length <= 256)
        require(path.addresses.size in 1..8)
        val validated = path.copy(addresses = path.addresses.map(NativePrivateAddress::parse))
        val remaining = load().filterNot { it.deviceId == path.deviceId && it.buildTag == path.buildTag }
        require(remaining.size < 64)
        save(remaining + validated)
    }

    fun remove(deviceId: String, buildTag: String) = synchronized(lock) {
        save(load().filterNot { it.deviceId == deviceId && it.buildTag == buildTag })
    }
    fun reset() = synchronized(lock) { save(load().map { it.copy(enabled = false) }) }
    // Unreadable optional hints must not prevent an otherwise authorized relay connection.
    fun addresses(mac: IrohV2Computer): List<String> = try {
        load().singleOrNull { it.enabled && it.deviceId == mac.deviceId && it.buildTag == mac.buildTag }?.addresses.orEmpty()
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
      catch (_: Exception) { emptyList() }

    private fun save(paths: List<NativePrivatePath>) {
        write(JSONArray(paths.map { path -> JSONObject().put("deviceId", path.deviceId)
            .put("buildTag", path.buildTag).put("name", path.name)
            .put("addresses", JSONArray(path.addresses)).put("enabled", path.enabled) }).toString())
    }

    companion object {
        private val lock = Any()
        internal fun identityFile(identity: JSONObject): String {
            val fields = listOf("environment", "projectId", "teamId", "userId", "deviceId", "appNamespace", "buildTag")
            val bytes = JSONArray(fields.map { identity.getString(it) }).toString().toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + ".json"
        }
        fun create(context: Context, identity: JSONObject): NativePrivatePathStore {
            val root = File(context.noBackupFilesDir, "cmux-iroh-v2/local-paths")
            val file = AtomicFile(File(root, identityFile(identity)))
            return NativePrivatePathStore(read = {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) file.openRead().use {
                    require(it.channel.size() <= 128 * 1024)
                    it.readBytes().toString(Charsets.UTF_8)
                } else null
            }, write = { text ->
                check(root.isDirectory || root.mkdirs())
                val stream = file.startWrite()
                try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                catch (failure: Throwable) { file.failWrite(stream); throw failure }
            })
        }
    }
}
