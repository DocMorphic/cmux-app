package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.WeakHashMap

internal data class MacCompatibilityKey(val owner: NativeTeamScope, val identity: NativeMacIdentity)

/** Tracks authenticated wires, including all pooled foreground and background borrowers. */
internal class NativeMacCompatibilityGate(
    private val isCurrent: (NativeTeamScope) -> Boolean,
    initial: NativeMacCompatibilityPolicy = NativeMacCompatibilityPolicy.baked
) {
    private data class Host(val key: MacCompatibilityKey, val version: String?)
    private val lock = Any()
    private var policy = initial
    private val wires = WeakHashMap<MobileRpcClient, Host>()
    private val observed = linkedMapOf<MacCompatibilityKey, Host>()
    private val mutableWarnings = MutableStateFlow<Map<MacCompatibilityKey, MacCompatibilityViolation>>(emptyMap())
    val warnings = mutableWarnings.asStateFlow()

    /** Invoke only after transport/account authorization and exact expected-host checks. */
    fun admit(owner: NativeTeamScope, client: MobileRpcClient, status: JSONObject) {
        val device = (status.opt("mac_device_id") as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
        require(device != null) { "The Mac did not provide its device identity." }
        val tag = (status.opt("mac_instance_tag") as? String)?.takeIf { it.isNotBlank() }
        val host = Host(MacCompatibilityKey(owner, NativeMacIdentity(canonicalMacDeviceId(device), tag)),
            (status.opt("mac_app_version") as? String)?.take(1024))
        val failure = synchronized(lock) {
            check(isCurrent(owner) && !client.isClosed) { "Account or connection changed" }
            prune()
            observed[host.key] = host
            while (observed.size > 256) observed.remove(observed.keys.first())
            publish()
            policy.violation(tag, host.version)?.let(::MacUpdateRequired).also {
                if (it == null) wires[client.compatibilityWire] = host
            }
        }
        if (failure != null) {
            client.retireForCompatibility(failure)
            throw failure
        }
    }

    /** Remote refresh rechecks each wire, never a sibling Mac/build or a retired account. */
    fun replace(next: NativeMacCompatibilityPolicy) {
        val retired = synchronized(lock) {
            policy = next
            prune()
            publish()
            wires.entries.mapNotNull { (client, host) ->
                next.violation(host.key.identity.buildTag, host.version)?.let { client to MacUpdateRequired(it) }
            }.also { entries -> entries.forEach { wires.remove(it.first) } }
        }
        retired.forEach { (client, reason) -> client.retireForCompatibility(reason) }
    }

    fun reconcile() = synchronized(lock) { prune(); publish() }
    private fun prune() {
        wires.entries.removeAll { it.key.isClosed || !isCurrent(it.value.key.owner) }
        observed.keys.removeAll { !isCurrent(it.owner) }
    }
    private fun publish() {
        mutableWarnings.value = observed.mapNotNull { (key, host) ->
            policy.violation(key.identity.buildTag, host.version)?.let { key to it }
        }.toMap()
    }
}

/** Cache decoding is identical to network decoding; a corrupt response never replaces good state. */
internal class NativeMacPolicyCache(private val read: () -> String?, private val write: (String) -> Unit) {
    var policy = runCatching { read()?.let(NativeMacCompatibilityPolicy::decode) }.getOrNull()
        ?: NativeMacCompatibilityPolicy.baked
        private set
    fun accept(payload: String): NativeMacCompatibilityPolicy? {
        val next = NativeMacCompatibilityPolicy.decode(payload) ?: return null
        policy = next
        runCatching { write(payload) }
        return next
    }
}
