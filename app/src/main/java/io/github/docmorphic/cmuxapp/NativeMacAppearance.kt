package io.github.docmorphic.cmuxapp

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class NativeMacIdentity(val deviceId: String, val buildTag: String?)
internal data class NativeMacAppearance(val name: String? = null, val color: String? = null, val icon: String? = null) {
    fun displayName(fallback: String) = name ?: fallback.ifBlank { "Mac" }
    companion object {
        val symbols = listOf("desktopcomputer", "macbook", "laptopcomputer", "server.rack", "terminal", "display",
            "bolt.fill", "star.fill", "heart.fill", "flame.fill")
        val emojis = listOf("💻", "🖥️", "⚡️", "🔥", "⭐️", "🚀", "🐧", "🍎", "🎮", "👾")
        val symbolLabels = symbols.zip(listOf("Desktop computer", "MacBook", "Laptop", "Server", "Terminal", "Display",
            "Lightning", "Star", "Heart", "Flame")).toMap()
        fun name(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.length <= 256 && it.none(Char::isISOControl)) { "Use a name of up to 256 characters." }
        }
        fun color(value: String?): String? = value?.let {
            require(it in (0..7).map { n -> "palette:$n" } || Regex("#[0-9a-fA-F]{6}").matches(it)) {
                "Use a color in #RRGGBB format."
            }
            it.uppercase().replace("PALETTE:", "palette:")
        }
        fun icon(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it in symbols || (it.length <= 64 && it.any { c -> c.code > 127 } && it.none(Char::isISOControl))) {
                "Choose an icon or enter an emoji (up to 64 characters)."
            }
        }
        /** Same wrapping djb2 scalar hash as MachineAvatarPalette; callers prefer distinct assigned slots. */
        fun paletteSlot(machineId: String): Int {
            var hash = 5381L
            machineId.codePoints().forEach { hash = hash * 33 + it }
            return (((hash % 8) + 8) % 8).toInt()
        }
    }
}

internal data class NativeMacAppearances(val values: Map<NativeMacIdentity, NativeMacAppearance> = emptyMap(),
    val error: Boolean = false) {
    fun get(deviceId: String, buildTag: String?) = values[NativeMacIdentity(deviceId, buildTag)] ?: NativeMacAppearance()
    fun get(mac: NativeCredentialStore.PairedMac) = get(mac.deviceId, mac.instanceTag)
    fun name(mac: NativeCredentialStore.PairedMac) = get(mac).displayName(mac.name)
}

internal fun nativeMacColorIndices(saved: List<NativeCredentialStore.PairedMac>, discovered: List<IrohV2Computer>) =
    (saved.map { it.deviceId } + discovered.map { it.deviceId }).filter { it.isNotBlank() }.distinct().sorted()
        .mapIndexed { index, device -> device to index % 8 }.toMap()

/** Appearance never modifies PairedMac, pairing origins, discovery records, or RPC destinations. */
internal class NativeMacAppearanceStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val mutableState = MutableStateFlow(NativeMacAppearances())
    val state = mutableState.asStateFlow()
    init { reload() }

    fun reload() = synchronized(lock) {
        mutableState.value = try { NativeMacAppearances(load()) } catch (_: Exception) { NativeMacAppearances(error = true) }
    }

    fun update(identity: NativeMacIdentity, permits: () -> Boolean,
               transform: (NativeMacAppearance) -> NativeMacAppearance) = synchronized(lock) {
        check(permits()) { "Account or team changed. Reopen Computer Details." }
        require(identity.deviceId.isNotBlank() && identity.deviceId.length <= 128 && (identity.buildTag?.length ?: 0) <= 64)
        val values = load().toMutableMap()
        val candidate = transform(values[identity] ?: NativeMacAppearance())
        val validated = NativeMacAppearance(NativeMacAppearance.name(candidate.name), NativeMacAppearance.color(candidate.color),
            NativeMacAppearance.icon(candidate.icon))
        if (validated == NativeMacAppearance()) values.remove(identity) else values[identity] = validated
        require(values.size <= 128) { "Too many saved computer appearances." }
        check(permits()) { "Account or team changed. Reopen Computer Details." }
        save(values)
    }

    fun removeComputer(target: NativeComputerTarget, permits: () -> Boolean) = synchronized(lock) {
        check(permits()) { "Account session changed" }
        val values = load().filterKeys {
            canonicalMacDeviceId(it.deviceId) != canonicalMacDeviceId(target.deviceId) || it.buildTag != target.buildTag
        }
        check(permits()) { "Account session changed" }
        save(values)
    }

    private fun save(values: Map<NativeMacIdentity, NativeMacAppearance>) {
        write(JSONArray(values.map { (key, value) -> JSONObject().put("deviceId", key.deviceId)
            .put("buildTag", key.buildTag ?: JSONObject.NULL).put("name", value.name ?: JSONObject.NULL)
            .put("color", value.color ?: JSONObject.NULL).put("icon", value.icon ?: JSONObject.NULL) }).toString())
        mutableState.value = NativeMacAppearances(values)
    }

    private fun load(): Map<NativeMacIdentity, NativeMacAppearance> {
        val array = JSONArray(read() ?: "[]")
        require(array.length() <= 128)
        val result = mutableMapOf<NativeMacIdentity, NativeMacAppearance>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            fun text(key: String): String? = if (item.isNull(key)) null else item.get(key) as String
            val identity = NativeMacIdentity(item.get("deviceId") as String, text("buildTag"))
            require(identity.deviceId.isNotBlank() && identity.deviceId.length <= 128 && (identity.buildTag?.length ?: 0) <= 64)
            require(identity !in result)
            result[identity] = NativeMacAppearance(NativeMacAppearance.name(text("name")),
                NativeMacAppearance.color(text("color")), NativeMacAppearance.icon(text("icon")))
        }
        return result
    }

    companion object {
        private val lock = Any()
        private val stores = mutableMapOf<String, NativeMacAppearanceStore>()
        internal fun scopeFile(app: String, project: String, user: String, team: String): String {
            require(listOf(app, project, user, team).all { it.isNotBlank() })
            return MessageDigest.getInstance("SHA-256").digest(JSONArray(listOf(app, project, user, team)).toString().toByteArray())
                .joinToString("") { "%02x".format(it) } + ".json"
        }
        fun create(context: Context, team: NativeTeamScope): NativeMacAppearanceStore = synchronized(lock) {
            val root = File(context.noBackupFilesDir, "computer-appearance")
            val file = AtomicFile(File(root, scopeFile(context.packageName, NativeAccount.PROJECT_ID, team.userId, team.teamId)))
            stores.getOrPut(file.baseFile.absolutePath) {
                NativeMacAppearanceStore(read = {
                    if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) file.openRead().use {
                        require(it.channel.size() <= 128 * 1024); it.readBytes().decodeToString()
                    } else null
                }, write = { text ->
                    check(root.isDirectory || root.mkdirs())
                    val output = file.startWrite()
                    try { output.write(text.toByteArray()); file.finishWrite(output) }
                    catch (failure: Throwable) { file.failWrite(output); throw failure }
                })
            }
        }
    }
}
