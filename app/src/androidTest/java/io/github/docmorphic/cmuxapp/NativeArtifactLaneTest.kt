package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import io.github.docmorphic.cmuxapp.iroh.IrohRuntime
import io.github.docmorphic.cmuxapp.iroh.IrxClientSession
import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Local protocol fixture; saved cmux credentials are neither read nor cleared. */
class NativeArtifactLaneTest {
    @Test fun mintedCapabilityStreamsBoundedRawFileAndLeavesControlUsable() = runBlocking<Unit> {
        IrohRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        withTimeout(20_000) {
            val options = EndpointOptions(preset = presetMinimal(), bindAddr = "127.0.0.1:0",
                alpns = listOf(IrxWire.ALPN.toByteArray()), portMappingEnabled = false,
                initialMaxConcurrentBiStreams = 1uL, initialMaxConcurrentUniStreams = 0uL)
            val phoneOptions = options.copy(initialMaxConcurrentBiStreams = 0uL)
            val host = Endpoint.bind(options)
            val phone = Endpoint.bind(phoneOptions)
            val finished = CompletableDeferred<Unit>()
            val content = ByteArray(130123) { (it % 251).toByte() }
            try {
                val server = async {
                    checkNotNull(host.acceptNext()).use { incoming -> incoming.accept().use { accepting ->
                        accepting.connect().use { connection -> connection.acceptBi().use { control ->
                            control.recv().use { reader -> control.send().use { writer ->
                                IrxWire.read { reader.read(it.toUInt()) }; IrxWire.read { reader.read(it.toUInt()) }
                                writer.writeAll(IrxWire.encode(JSONObject().put("v", 1).put("session", "artifact-fixture")
                                    .put("keepaliveIntervalMs", 5000).put("keepaliveDeadlineMs", 2000)))
                                connection.setMaxConcurrentBiStreams(16uL)
                                val mint = checkNotNull(IrxWire.read { reader.read(it.toUInt()) })
                                assertEquals("mobile.terminal.artifact.fetch", mint.getString("method"))
                                val params = mint.getJSONObject("params")
                                assertEquals("workspace", params.getString("workspace_id")); assertEquals("surface", params.getString("surface_id"))
                                assertEquals("/fixture.bin", params.getString("path")); assertEquals("iroh_artifact_v1", params.getString("transport"))
                                val descriptor = JSONObject().put("resource_id", "artifact:fixture-capability")
                                    .put("total_size", content.size).put("expires_at", "2030-01-01T00:00:00Z")
                                writer.writeAll(MobileFrameCodec.encode(JSONObject().put("id", mint.getString("id"))
                                    .put("ok", true).put("result", descriptor).toString().toByteArray()))
                                connection.acceptBi().use { artifact -> artifact.recv().use { request -> artifact.send().use { output ->
                                    val lane = checkNotNull(IrxWire.read { request.read(it.toUInt()) })
                                    assertEquals("artifact", lane.getString("lane")); assertEquals("artifact:fixture-capability", lane.getString("resource"))
                                    assertEquals(0L, lane.getLong("offset")); assertFalse(lane.has("path"))
                                    output.writeAll(content); output.finish()
                                    val status = checkNotNull(IrxWire.read { reader.read(it.toUInt()) })
                                    assertEquals("mobile.host.status", status.getString("method"))
                                    writer.writeAll(MobileFrameCodec.encode(JSONObject().put("id", status.getString("id"))
                                        .put("ok", true).put("result", JSONObject().put("healthy", true)).toString().toByteArray()))
                                    finished.await()
                                } } }
                            } }
                        } }
                    } }
                }
                host.id().use { id -> EndpointAddr(id, null, host.boundSockets()).use { address ->
                    val connection = phone.connect(address, IrxWire.ALPN.toByteArray())
                    val transport = IrxMobileRpcTransport({ IrxClientSession.admit(connection, id.toBytes()) }, { true },
                        MutableStateFlow(IrxProbeActivity(false)))
                    MobileRpcClient(transport, { "fixture-token" }).use { client ->
                        client.connect()
                        val rpc = ArtifactRpc(client, setOf("terminal.artifact.v1"))
                        val transfer = ArtifactContentTransfer(rpc, ArtifactAuthorization.Terminal("workspace", "surface"))
                        val received = ByteArrayOutputStream()
                        transfer.stream("/fixture.bin", ArtifactMetadata(content.size.toLong(), ArtifactKind.BINARY, null), content.size.toLong()) { bytes, offset ->
                            assertTrue(bytes.size <= 64 * 1024); received.write(bytes); assertEquals(received.size().toLong(), offset)
                        }
                        assertArrayEquals(content, received.toByteArray())
                        assertTrue(client.hostStatus().getBoolean("healthy")); assertFalse(client.isClosed)
                        finished.complete(Unit); server.await()
                    }
                } }
            } finally {
                finished.complete(Unit)
                withContext(NonCancellable) {
                    try { withTimeout(5000) { phone.shutdown(); host.shutdown() } }
                    finally { phone.close(); host.close(); phoneOptions.destroy(); options.destroy() }
                }
            }
        }
    }
}
