package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

class SocketMobileRpcAuthorityTest {
    private class Authority : MobileSocketAuthority {
        var current = true
        var started = 0
        var closed = false
        var established = 0
        var notify: () -> Unit = {}
        var rejectEstablished = false
        override fun start(onInvalidated: () -> Unit) { started++; notify = onInvalidated }
        override fun validate(socket: Socket?) {
            check(current && !closed)
            if (socket != null) { established++; check(!rejectEstablished); check(socket.isConnected) }
        }
        override fun close() { closed = true }
        fun loseTunnel() { current = false; notify() }
    }
    private fun transport(server: ServerSocket, authority: Authority) = SocketMobileRpcTransport(
        PairingCode.Route("127.0.0.1", server.localPort), SocketFactory.getDefault(), authority)

    @Test fun validConnectionExchangesBytesAndClosesObserver() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val authority = Authority()
            transport(server, authority).use { connection ->
                connection.connect(); connection.connect()
                server.accept().use { peer ->
                    peer.soTimeout = 2000
                    connection.write(byteArrayOf(7)); assertEquals(7, peer.getInputStream().read())
                    peer.getOutputStream().write(byteArrayOf(8))
                    assertArrayEquals(byteArrayOf(8), connection.read())
                    assertEquals(1, authority.started)
                    assertTrue(authority.established >= 4)
                }
            }
            assertTrue(authority.closed)
        }
    }

    @Test fun invalidInitialProofPreventsEvenOpeningATcpConnection() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            server.soTimeout = 100
            val authority = Authority().apply { current = false }
            transport(server, authority).use { connection ->
                assertTrue(runCatching { connection.connect() }.isFailure)
                assertTrue(runCatching { server.accept().close() }.exceptionOrNull() is java.net.SocketTimeoutException)
                assertTrue(authority.closed)
            }
        }
    }

    @Test fun establishedEndpointMismatchClosesBeforeAnyPayload() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val authority = Authority().apply { rejectEstablished = true }
            transport(server, authority).use { connection ->
                assertTrue(runCatching { connection.connect() }.isFailure)
                server.accept().use { peer -> peer.soTimeout = 2000; assertEquals(-1, peer.getInputStream().read()) }
                assertTrue(authority.closed)
            }
        }
    }

    @Test fun freshWriteValidationRejectsCredentialsBeforeQueuedNetworkCallback() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val authority = Authority()
            transport(server, authority).use { connection ->
                connection.connect()
                server.accept().use { peer ->
                    peer.soTimeout = 2000
                    // No callback yet: the synchronous snapshot must still stop this frame.
                    authority.current = false
                    assertTrue(runCatching { connection.write("fixture-credential".toByteArray()) }.isFailure)
                    assertEquals(-1, peer.getInputStream().read())
                    authority.current = true
                    assertTrue(runCatching { connection.connect() }.isFailure)
                    assertTrue(authority.closed)
                }
            }
        }
    }

    @Test fun lossCallbackInterruptsBlockedReadAndReturningTunnelCannotReviveIt() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val authority = Authority()
            transport(server, authority).use { connection ->
                connection.connect()
                server.accept().use { peer ->
                    val read = async(Dispatchers.IO) { runCatching { connection.read() } }
                    authority.loseTunnel()
                    assertTrue(withTimeout(2000) { read.await() }.isFailure)
                    peer.soTimeout = 2000; assertEquals(-1, peer.getInputStream().read())
                    authority.current = true
                    assertTrue(runCatching { connection.write(byteArrayOf(9)) }.isFailure)
                }
            }
        }
    }
}
