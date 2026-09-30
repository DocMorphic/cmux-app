package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Pure generated socket data; does not access account stores, terminal sessions, or WebViews. */
class BrowserSocksPlatformTest {
    @Test fun androidAsyncProxyPreservesRequestAndResponseAfterClientHalfClose() = runBlocking<Unit> {
        val payload = ByteArray(131_099) { (it * 13).toByte() }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { echo ->
            val server = async(Dispatchers.IO) {
                echo.accept().use { peer ->
                    peer.soTimeout = 5000
                    val bytes = peer.getInputStream().readBytes()
                    assertArrayEquals(payload, bytes)
                    peer.getOutputStream().write(bytes + byteArrayOf(0, -1, 42)); peer.shutdownOutput()
                }
            }
            val backend = BrowserTunnelBackend { host, port, use ->
                assertEquals("mac-only.invalid", host); assertEquals(3000, port)
                NioBrowserSocket.direct.use("127.0.0.1", echo.localPort, use)
            }
            BrowserSocksProxy.start(backend).use { proxy ->
                try {
                    withTimeout(10_000) { withContext(Dispatchers.IO) {
                        Socket("127.0.0.1", proxy.port).use { client ->
                            client.soTimeout = 5000
                            val name = "mac-only.invalid".toByteArray()
                            client.getOutputStream().write(byteArrayOf(5, 1, 0, 5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(11, -72))
                            client.getOutputStream().write(payload); client.shutdownOutput()
                            val response = client.getInputStream().readBytes()
                            val prefix = byteArrayOf(5, 0, 5, 0, 0, 1, 0, 0, 0, 0, 0, 0)
                            assertArrayEquals(prefix + payload + byteArrayOf(0, -1, 42), response)
                        }
                    }; server.await() }
                } finally { proxy.stop() }
                assertFalse(proxy.isListening); assertEquals(0, proxy.activeConnectionCount)
            }
        }
    }
}
