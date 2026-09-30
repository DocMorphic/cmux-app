package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.util.Locale

/** A borrowed connection remains inside this callback, including the entire relay lifetime. */
internal fun interface BrowserTunnelBackend {
    suspend fun use(host: String, port: Int, connected: suspend (BrowserTunnelLane) -> Unit)
}

/** Byte-based equivalent of upstream CmxLoopbackHost; never resolves DNS names. */
internal object BrowserLoopbackHost {
    fun matches(host: String): Boolean {
        val normalized = host.trim { it.isWhitespace() || Character.isSpaceChar(it) }.lowercase(Locale.ROOT)
            .removeSurrounding("[", "]").removeSuffix(".")
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        ipv4(normalized)?.let { return self(it ushr 24) }
        val literal = normalized.substringBefore('%')
        if (':' !in literal || literal.any { it !in "0123456789abcdef:." }) return false
        val canonical = "http://[$literal]/".toHttpUrlOrNull()?.host ?: return false
        // HttpUrl has already validated this as a numeric address; getByName cannot perform DNS.
        val bytes = InetAddress.getByName(canonical).address.map { it.toInt() and 255 }
        if (bytes.size == 4) return self(bytes[0].toLong())
        if (bytes.size != 16) return false
        if (bytes.take(15).all { it == 0 } && bytes[15] <= 1) return true
        return bytes.take(10).all { it == 0 } &&
            ((bytes[10] == 255 && bytes[11] == 255) || (bytes[10] == 0 && bytes[11] == 0)) && self(bytes[12].toLong())
    }
    private fun self(first: Long) = first == 127L || first == 0L
    private fun ipv4(host: String): Long? {
        val parts = host.takeWhile { it !in "\t\n\u000b\u000c\r " }.split('.')
        if (parts.size !in 1..4) return null
        val numbers = parts.mapIndexed { index, part ->
            val radix = when { part.startsWith("0x") -> 16; part.startsWith('0') && part.length > 1 -> 8; else -> 10 }
            val digits = if (radix == 16) part.drop(2) else part
            if ((digits.isEmpty() && !(part == "0x" && index < parts.lastIndex)) ||
                digits.any { it !in "0123456789abcdef" || it.digitToIntOrNull(radix) == null }) return null
            // Darwin accumulates unsigned long values, then truncates a one-part address
            // to 32 bits. Do not delegate to Java's different numeric-name resolver.
            digits.fold(0uL) { value, digit -> value * radix.toUInt() + digit.digitToInt(radix).toUInt() }
        }
        if (numbers.size == 1) return numbers[0].toUInt().toLong()
        var result = 0L
        for (index in numbers.indices) {
            val bits = if (index == numbers.lastIndex) (5 - numbers.size) * 8 else 8
            if (numbers[index] >= (1uL shl bits)) return null
            result = (result shl bits) or numbers[index].toLong()
        }
        return result
    }
}

/** A Mac's advertised exit policy, with fallback only before a connection has been handed out. */
internal class MacBrowserRouter(private val mac: BrowserTunnelBackend,
    private val direct: BrowserTunnelBackend = NioBrowserSocket.direct) : BrowserTunnelBackend {
    @Volatile var allowsNonLoopbackHosts = false
    override suspend fun use(host: String, port: Int, connected: suspend (BrowserTunnelLane) -> Unit) {
        BrowserTunnelProtocol.connect(host, port)
        if (BrowserLoopbackHost.matches(host)) return mac.use(host, port, connected)
        if (!allowsNonLoopbackHosts) return direct.use(host, port, connected)
        var handedOut = false
        try {
            mac.use(host, port) { lane -> handedOut = true; connected(lane) }
        } catch (failure: BrowserTunnelProtocol.OpenFailure) {
            currentCoroutineContext().ensureActive()
            if (handedOut || failure.status != BrowserTunnelProtocol.Status.DENIED) throw failure
            direct.use(host, port, connected)
        }
    }
}

/** Bounds Mac feature lanes without queueing behind browser traffic or borrowing another Mac. */
internal class MacBrowserLaneBackend(private val provider: () -> MobileRpcClient?, maximumLanes: Int = 32) : BrowserTunnelBackend {
    init { require(maximumLanes in 1..32) }
    private val slots = Semaphore(maximumLanes)
    override suspend fun use(host: String, port: Int, connected: suspend (BrowserTunnelLane) -> Unit) {
        if (!slots.tryAcquire()) throw BrowserTunnelProtocol.OpenFailure(BrowserTunnelProtocol.Status.BUSY)
        try {
            // The provider must freshly validate the bound owner and browser.tunnel.v1 capability.
            val client = provider() ?: throw BrowserTunnelProtocol.OpenFailure(BrowserTunnelProtocol.Status.FAILED)
            if (!client.useBrowserTunnel(host, port, connected))
                throw BrowserTunnelProtocol.OpenFailure(BrowserTunnelProtocol.Status.FAILED)
        } finally { slots.release() }
    }
}
