package io.github.docmorphic.cmuxapp

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import io.github.docmorphic.cmuxapp.iroh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class NativeDirectBackendTest {
    /** Production backend, encrypted identity, native transport and local QUIC admission;
     * no control server is started and the account authority closure is a fixture.
     */
    @Test fun directBackendNeedsNoRelayMetadataAndCannotReuseRetiredIntent() = runBlocking<Unit> {
        withTimeout(20_000) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val folder = File(base.noBackupFilesDir, "direct-backend-fixture-${UUID.randomUUID()}").apply { mkdirs() }
            val context = object : ContextWrapper(base) {
                override fun getApplicationContext() = this
                override fun getNoBackupFilesDir() = folder
            }
            IrohRuntime.initialize(context)
            val scope = IrohAccountScope("test", "fixture", "team", "user", base.packageName, "debug")
            val key = IrohInstallationStore(context).loadOrCreate(scope)
            val request = IrohV2SignedRequests(IrohV2AndroidDevice.descriptor(key.identity(), key.endpointId, "fixture", "Fixture",
                IrohMobileWireProfile.IOS_COMPATIBILITY), key::sign)
            val control = IrohV2ControlSession(request, { error("No control request expected") }, { true },
                origin = "https://must-not-contact.invalid/".toHttpUrl())
            var settingsJson: String? = null
            val preferences = NativeMacConnectionStore({ settingsJson }, { settingsJson = it })
            val backend = NativeIrohBackend(key, control, { true }, MutableStateFlow(IrxProbeActivity(false)),
                NativePrivatePathStore({ null }, {}), preferences)
            val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().first { !it.isLoopbackAddress && !it.isLinkLocalAddress }.hostAddress!!
            val options = EndpointOptions(preset = presetMinimal(), bindAddr = "$address:0", relayMode = RelayMode.disabled(),
                alpns = listOf(IrxWire.ALPN.toByteArray()), portMappingEnabled = false,
                deferNatTraversalUntilAuthorized = true, initialMaxConcurrentBiStreams = 1uL, initialMaxConcurrentUniStreams = 0uL)
            val host = Endpoint.bind(options)
            try {
                val peer = host.id().use { it.toBytes().joinToString("") { byte -> "%02x".format(byte) } }
                val mac = IrohV2Computer("fixture-record", peer, "fixture-mac", "default", "Fixture", emptyList())
                val target = NativeComputerTarget.from(mac)
                val socket = host.boundSockets().single { it.startsWith("$address:") }
                preferences.update(target, { true }) { NativeMacConnectionPreference(NativeMacConnectionMethod.DIRECT,
                    listOf(NativeDirectAddress(socket))) }
                val intent = preferences.state.value.intent(mac)
                coroutineScope {
                    val done = CompletableDeferred<Unit>()
                    val server = async {
                        checkNotNull(host.acceptNext()).use { incoming -> incoming.accept().use { accepting -> accepting.connect().use { connection ->
                            connection.acceptBi().use { stream -> stream.recv().use { receive -> stream.send().use { send ->
                                IrxWire.read { receive.read(it.toUInt()) }; IrxWire.read { receive.read(it.toUInt()) }
                                send.writeAll(IrxWire.encode(JSONObject().put("v", 1).put("session", "backend-fixture")
                                    .put("keepaliveIntervalMs", 5000).put("keepaliveDeadlineMs", 2000)))
                                assertEquals("ping", IrxWire.read { receive.read(it.toUInt()) }!!.getString("fixture"))
                                send.writeAll(IrxWire.encode(JSONObject().put("fixture", "pong"))); done.await()
                            } } }
                        } } }
                    }
                    val transport = backend.transport(mac, { true }, intent)
                    try {
                        transport.connect(); transport.write(MobileFrameCodec.encode(JSONObject().put("fixture", "ping").toString().toByteArray()))
                        assertEquals("pong", JSONObject(MobileFrameDecoder().feed(transport.read()!!).single().decodeToString()).getString("fixture"))
                        assertEquals(IrxEndpointStatus(true, null), backend.endpointStatus())
                        preferences.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.IROH) }
                        assertTrue(runCatching { transport.write("stale".toByteArray()) }.isFailure)
                    } finally { transport.close(); done.complete(Unit) }
                    server.await()
                }
                preferences.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT, addresses = emptyList()) }
                assertTrue(runCatching { backend.transport(mac, { true }, preferences.state.value.intent(mac)) }.isFailure)
            } finally {
                backend.close()
                withContext(NonCancellable) {
                    backend.awaitClosed()
                    try { host.shutdown() } finally { host.close(); options.destroy(); folder.deleteRecursively() }
                }
            }
        }
    }
}
