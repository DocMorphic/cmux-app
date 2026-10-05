package io.github.docmorphic.cmuxapp.iroh

import java.net.URI

/** Remote addressing hints, independent of the credentials used to bind our endpoint. */
internal class IrxDialTarget private constructor(
    val peerBytes: ByteArray,
    val relayUrl: String?,
    val directAddresses: List<String>
) {
    companion object {
        fun create(mode: IrxEndpointPathMode, peerHex: String, relayUrl: String?,
                   directAddresses: List<String>): IrxDialTarget {
            val directOnly = mode == IrxEndpointPathMode.DIRECT_ONLY
            require(directAddresses.size <= if (directOnly) 16 else 8)
            require(!directOnly || directAddresses.isNotEmpty()) { "Direct mode needs an enabled address" }
            require(peerHex.matches(Regex("[0-9a-f]{64}")))
            // A Mac can have usable private addresses without publishing a relay.
            // A supplied hint still has to satisfy the existing HTTPS contract.
            val relay = if (directOnly) null else relayUrl?.also { require(URI(it).scheme == "https") }
            return IrxDialTarget(peerHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                relay, directAddresses.toList())
        }
    }
}
