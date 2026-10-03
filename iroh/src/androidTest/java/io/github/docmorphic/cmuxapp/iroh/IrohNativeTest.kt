package io.github.docmorphic.cmuxapp.iroh

import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class IrohNativeTest {
    @Before fun initialize() {
        IrohRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test fun nativeIdentitySignsOfficialWorkerVectors() {
        val fixture = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("signing.json").bufferedReader().use { it.readText() })
        val seed = fixture.getString("secretSeedHex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        SecretKey.fromBytes(seed).use { key ->
            key.`public`().use { public ->
                assertEquals(fixture.getJSONObject("device").getString("endpointId"),
                    public.toBytes().joinToString("") { "%02x".format(it) })
                for (prefix in listOf("enrollment", "request", "http")) {
                    val bytes = fixture.getString(prefix + "Canonical").toByteArray()
                    key.sign(bytes).use { signature ->
                        assertArrayEquals(Base64.getUrlDecoder().decode(fixture.getString(prefix + "Signature")),
                            signature.toBytes())
                        public.verify(bytes, signature)
                        assertThrows(IrohException::class.java) { public.verify(bytes + 0.toByte(), signature) }
                    }
                }
            }
        }
        seed.fill(0)
    }

    @Test fun localQuicAuthenticatesPeerAndTransfersIndependentStreams() = runBlocking {
        withTimeout(30_000) {
            val alpn = "cmux/irx/1".toByteArray()
            val options = EndpointOptions(preset = presetMinimal(), bindAddr = "127.0.0.1:0", alpns = listOf(alpn),
                portMappingEnabled = false)
            val server = Endpoint.bind(options)
            try {
                val client = Endpoint.bind(options)
                try {
                    val responsesRead = CompletableDeferred<Unit>()
                    val receiver = async {
                        checkNotNull(server.acceptNext()).use { incoming ->
                            incoming.accept().use { accepting ->
                                accepting.connect().use { connection ->
                                    client.id().use { expected -> connection.remoteId().use { actual ->
                                        assertArrayEquals(expected.toBytes(), actual.toBytes())
                                    } }
                                    assertArrayEquals(alpn, connection.alpn())
                                    repeat(2) { index ->
                                        connection.acceptBi().use { stream ->
                                            stream.recv().use { recv ->
                                                assertEquals("request-$index", recv.readToEnd(1024u).decodeToString())
                                            }
                                            stream.send().use { send ->
                                                send.writeAll("response-$index".toByteArray()); send.finish()
                                            }
                                        }
                                    }
                                    responsesRead.await()
                                }
                            }
                        }
                    }
                    server.id().use { serverId ->
                        EndpointAddr(serverId, null, server.boundSockets()).use { address ->
                            client.connect(address, alpn).use { connection ->
                                connection.remoteId().use { actual -> assertArrayEquals(serverId.toBytes(), actual.toBytes()) }
                                repeat(2) { index ->
                                    connection.openBi().use { stream ->
                                        stream.send().use { send ->
                                            send.writeAll("request-$index".toByteArray()); send.finish()
                                        }
                                        stream.recv().use { recv ->
                                            assertEquals("response-$index", recv.readToEnd(1024u).decodeToString())
                                        }
                                    }
                                }
                                responsesRead.complete(Unit)
                                receiver.await()
                                connection.close(0L, "test complete".toByteArray())
                            }
                        }
                    }
                } finally {
                    shutdown(client)
                }
            } finally {
                shutdown(server)
                options.destroy()
            }
        }
    }

    private suspend fun shutdown(endpoint: Endpoint) {
        try {
            withContext(NonCancellable) { withTimeout(5_000) { endpoint.shutdown() } }
        } finally {
            endpoint.close()
        }
    }
}
