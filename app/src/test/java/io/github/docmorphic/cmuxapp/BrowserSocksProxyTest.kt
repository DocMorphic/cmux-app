package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class BrowserSocksProxyTest {
    private fun socket(port: Int) = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
    private fun request(host: String, port: Int): ByteArray = host.toByteArray().let {
        byteArrayOf(5, 1, 0, 3, it.size.toByte()) + it + byteArrayOf((port shr 8).toByte(), port.toByte())
    }
    private fun read(socket: Socket, count: Int): ByteArray {
        val result = ByteArray(count); var offset = 0
        while (offset < count) { val n = socket.getInputStream().read(result, offset, count - offset); check(n > 0); offset += n }
        return result
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) delay(5) }

    @Test fun fragmentedPipelinedRequestsPreserveBinaryBodyAndBothHalfCloses() = runBlocking<Unit> {
        val data = ByteArray(196_613) { (it * 31).toByte() }
        val seen = mutableListOf<String>()
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { echo ->
            val server = launch(Dispatchers.IO) {
                repeat(3) { echo.accept().use { peer ->
                    peer.soTimeout = 5000
                    val incoming = peer.getInputStream().readBytes()
                    assertArrayEquals(data, incoming)
                    peer.getOutputStream().write(incoming + byteArrayOf(0, -1, 42))
                    peer.shutdownOutput()
                } }
            }
            val backend = BrowserTunnelBackend { host, port, use ->
                synchronized(seen) { seen += "$host:$port" }
                NioBrowserSocket.direct.use("127.0.0.1", echo.localPort, use)
            }
            BrowserSocksProxy.start(backend).use { proxy ->
                val requests = listOf(request("only-on-mac.invalid", 3000),
                    byteArrayOf(5, 1, 0, 1, 127, 9, 8, 7, 0, 80),
                    byteArrayOf(5, 1, 0, 4) + ByteArray(15) + byteArrayOf(1, 1, -69))
                for ((index, connect) in requests.withIndex()) withContext(Dispatchers.IO) {
                    socket(proxy.port).use { client ->
                        val handshake = byteArrayOf(5, 2, 2, 0) + connect
                        handshake.toList().chunked(index + 1).forEach { client.getOutputStream().write(it.toByteArray()) }
                        client.getOutputStream().write(data); client.shutdownOutput()
                        assertArrayEquals(byteArrayOf(5, 0), read(client, 2))
                        assertArrayEquals(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0), read(client, 10))
                        assertArrayEquals(data + byteArrayOf(0, -1, 42), client.getInputStream().readBytes())
                    }
                }
                server.join(); until { proxy.activeConnectionCount == 0 }
                assertEquals(listOf("only-on-mac.invalid:3000", "127.9.8.7:80", "0:0:0:0:0:0:0:1:443"), seen)
                proxy.stop()
            }
        }
    }

    @Test fun unsupportedAuthenticationCommandsAndAddressesNeverOpenExit() = runBlocking<Unit> {
        val opens = AtomicInteger()
        BrowserSocksProxy.start(BrowserTunnelBackend { _, _, _ -> opens.incrementAndGet(); error("Unexpected exit") }).use { proxy ->
            withContext(Dispatchers.IO) { socket(proxy.port).use { client ->
                client.getOutputStream().write(byteArrayOf(5, 1, 2))
                assertArrayEquals(byteArrayOf(5, -1), read(client, 2)); assertEquals(-1, client.getInputStream().read())
            } }
            for ((raw, status) in listOf(byteArrayOf(5, 2, 0, 1) to 7, byteArrayOf(5, 1, 0, 7) to 8,
                request("", 80) to 4, request("localhost", 0) to 4)) withContext(Dispatchers.IO) {
                socket(proxy.port).use { client ->
                    client.getOutputStream().write(byteArrayOf(5, 1, 0) + raw)
                    assertArrayEquals(byteArrayOf(5, 0), read(client, 2))
                    assertEquals(status, read(client, 10)[1].toInt())
                }
            }
            assertEquals(0, opens.get()); proxy.stop()
        }
    }

    @Test fun nativeOpenFailuresAreSocksFailuresNeverSuccess() = runBlocking<Unit> {
        for ((failure, expected) in listOf(BrowserTunnelProtocol.Status.DENIED to 2,
            BrowserTunnelProtocol.Status.NETWORK_UNREACHABLE to 3, BrowserTunnelProtocol.Status.HOST_UNREACHABLE to 4,
            BrowserTunnelProtocol.Status.UNRESOLVED to 4, BrowserTunnelProtocol.Status.REFUSED to 5,
            BrowserTunnelProtocol.Status.TIMED_OUT to 6, BrowserTunnelProtocol.Status.BUSY to 1,
            BrowserTunnelProtocol.Status.FAILED to 1)) {
            BrowserSocksProxy.start(BrowserTunnelBackend { _, _, _ -> throw BrowserTunnelProtocol.OpenFailure(failure) }).use { proxy ->
                withContext(Dispatchers.IO) { socket(proxy.port).use { client ->
                    client.getOutputStream().write(byteArrayOf(5, 1, 0) + request("localhost", 3000))
                    read(client, 2); assertEquals(expected, read(client, 10)[1].toInt())
                    assertEquals(-1, client.getInputStream().read())
                } }
                proxy.stop()
            }
        }
    }

    @Test fun capacityIncludesIncompleteHandshakesAndTimedOutSlotsReturn() = runBlocking<Unit> {
        BrowserSocksProxy.start(BrowserTunnelBackend { _, _, _ -> fail("No completed handshake") },
            maximumConnections = 1, handshakeTimeoutMillis = 150).use { proxy ->
            socket(proxy.port).use { first ->
                until { proxy.activeConnectionCount == 1 }
                withContext(Dispatchers.IO) { socket(proxy.port).use { overflow -> assertEquals(-1, overflow.getInputStream().read()) } }
                withContext(Dispatchers.IO) { assertEquals(-1, first.getInputStream().read()) }
                until { proxy.activeConnectionCount == 0 }
                socket(proxy.port).use { next ->
                    until { proxy.activeConnectionCount == 1 }
                    proxy.stop()
                    withContext(Dispatchers.IO) { assertEquals(-1, next.getInputStream().read()) }
                }
                assertFalse(proxy.isListening); assertEquals(0, proxy.activeConnectionCount)
            }
        }
    }

    @Test fun stoppingProxyCancelsBackendLifetimeAndParkedRelay() = runBlocking<Unit> {
        val closed = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val lane = object : BrowserTunnelLane {
            override suspend fun read(maximumBytes: Int): ByteArray? { entered.complete(Unit); awaitCancellation() }
            override suspend fun write(bytes: ByteArray) {}
            override suspend fun finishSending() {}
            override fun close() { closed.complete(Unit) }
        }
        val released = CompletableDeferred<Unit>()
        BrowserSocksProxy.start(BrowserTunnelBackend { _, _, use -> try { use(lane) } finally { released.complete(Unit) } }).use { proxy ->
            socket(proxy.port).use { client ->
                withContext(Dispatchers.IO) {
                    client.getOutputStream().write(byteArrayOf(5, 1, 0) + request("localhost", 80))
                    read(client, 2); assertEquals(0, read(client, 10)[1].toInt())
                }
                withTimeout(3000) { entered.await(); proxy.stop(); closed.await(); released.await() }
                withContext(Dispatchers.IO) { assertEquals(-1, client.getInputStream().read()) }
            }
        }
    }
}
