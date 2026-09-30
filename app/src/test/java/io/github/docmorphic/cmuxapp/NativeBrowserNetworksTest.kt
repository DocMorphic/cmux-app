package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class NativeBrowserNetworksTest {
    private fun mac(id: String, build: String = "default") = NativeCredentialStore.PairedMac(
        "route-$id-$build", id, "Mac $id", build, "user", "team", "origin-$id-$build")
    private suspend fun until(check: () -> Boolean) = withTimeout(3_000) { while (!check()) delay(5) }
    private val emptyLane = object : BrowserTunnelLane {
        override suspend fun read(maximumBytes: Int): ByteArray? = null
        override suspend fun write(bytes: ByteArray) {}
        override suspend fun finishSending() {}
        override fun close() {}
    }
    private open inner class Access : MacBrowserAccess {
        var state = MacBrowserAvailability.AVAILABLE
        var calls = 0
        var fail = false
        var policy = false
        val opens = CopyOnWriteArrayList<String>()
        override suspend fun availability() = state
        override suspend fun listeningPorts(): BrowserTunnelProtocol.ListeningPorts {
            calls++
            if (fail) throw IOException("fixture offline")
            return BrowserTunnelProtocol.ListeningPorts(listOf(BrowserTunnelProtocol.ListeningPort(3000, "127.0.0.1")), policy)
        }
        override suspend fun use(host: String, port: Int, connected: suspend (BrowserTunnelLane) -> Unit) {
            check(state == MacBrowserAvailability.AVAILABLE)
            opens += host
            connected(emptyLane)
        }
    }
    private suspend fun open(port: Int, host: String): Int = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            val name = host.toByteArray()
            out.write(byteArrayOf(5, 1, 0, 5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(0, 80))
            assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
            val reply = ByteArray(10); input.readFully(reply)
            reply[1].toInt()
        }
    }

    @Test fun listingPolicyExpiresAfterTenSecondsLoopbackAlwaysRefreshesAndFailuresKeepPolicy() = runBlocking<Unit> {
        val access = Access()
        val direct = CopyOnWriteArrayList<String>()
        var now = 0L
        NativeMacBrowserNetwork(this, access, { true }, BrowserTunnelBackend { host, _, use ->
            direct += host; use(emptyLane)
        }, { now }).use { network ->
            val port = network.prepare()
            assertEquals(1, access.calls)
            assertEquals(0, open(port, "example.invalid"))
            assertEquals(listOf("example.invalid"), direct)
            now = 10_000_000_000L
            assertEquals(port, network.prepare()); assertEquals(1, access.calls)
            now++
            access.policy = true
            assertEquals(port, network.prepare()); assertEquals(2, access.calls)
            assertEquals(0, open(port, "build.lan")); assertEquals(listOf("build.lan"), access.opens)
            network.prepare(3000); assertEquals(3, access.calls)
            access.fail = true; access.state = MacBrowserAvailability.NOT_CONNECTED
            assertEquals(port, network.prepare(3000))
            assertTrue(network.listing!!.allowsNonLoopbackHosts)
            assertEquals(MacBrowserAvailability.NOT_CONNECTED, network.availability())
            assertTrue(network.availability().bindsBrowserToMac)
            assertNotEquals(0, open(port, "localhost"))
            assertNotEquals(0, open(port, "build.lan"))
            assertEquals(listOf("example.invalid"), direct)
            access.fail = false; access.state = MacBrowserAvailability.AVAILABLE
            assertEquals(port, network.prepare(3000))
            assertEquals(0, open(port, "localhost"))
        }
    }

    @Test fun retirementCancelsPendingListingAndClosesDirectTrafficWithoutReturningAProxy() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val left = CompletableDeferred<Unit>()
        val access = Access()
        val network = NativeMacBrowserNetwork(this, access, { true }, BrowserTunnelBackend { _, _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { left.complete(Unit) }
        })
        try {
            val port = network.prepare()
            val pending = async { runCatching { open(port, "example.invalid") } }
            withTimeout(3_000) { entered.await() }
            network.close()
            withTimeout(3_000) { left.await() }
            assertTrue(withTimeout(3_000) { pending.await() }.isFailure)
            assertTrue(runCatching { network.prepare() }.isFailure)
        } finally { network.close() }
        val listingEntered = CompletableDeferred<Unit>()
        val listing = object : Access() {
            override suspend fun listeningPorts(): BrowserTunnelProtocol.ListeningPorts {
                listingEntered.complete(Unit); awaitCancellation()
            }
        }
        val waiting = NativeMacBrowserNetwork(this, listing, { true })
        try {
            val pending = async { runCatching { waiting.prepare() } }
            listingEntered.await(); waiting.close()
            assertTrue(withTimeout(3_000) { pending.await() }.isFailure)
            assertNull(waiting.listing)
        } finally { waiting.close() }
    }

    @Test fun registryRetainsRoutesOnlyForSameAccountDeviceAndBuildAndRetiresOnTeamOrLoginChange() = runBlocking<Unit> {
        val seen = mutableListOf<NativeCredentialStore.PairedMac>()
        var allowed = true
        val registry = NativeBrowserNetworks(this, { mac, permits ->
            check(permits()); seen += mac; Access()
        }, { _, _ -> allowed })
        val team = NativeTeamScope("login", "user", "team", 1)
        val a = mac("a"); val b = mac("b"); val debug = mac("a", "debug")
        try {
            registry.retain("login", team, listOf(a, b, debug))
            val first = registry.network(a)!!; val second = registry.network(b)!!; val sibling = registry.network(debug)!!
            assertNotSame(first, second); assertNotSame(first, sibling)
            first.availability(); second.availability(); sibling.availability()
            assertEquals(listOf(a, b, debug), seen)
            val rerouted = a.copy(code = "new-route")
            registry.retain("login", team, listOf(rerouted, b, debug))
            assertSame(first, registry.network(rerouted)); assertNull(registry.network(a))
            first.availability(); assertEquals(rerouted, seen.last())
            val replacement = rerouted.copy(deviceId = "intruder")
            registry.retain("login", team, listOf(replacement, b, debug))
            assertTrue(runCatching { first.availability() }.isFailure)
            assertSame(second, registry.network(b))
            assertNotSame(first, registry.network(replacement))
            registry.retain("login", team.copy(generation = 2), listOf(b))
            assertTrue(runCatching { second.prepare() }.isFailure)
            val newer = registry.network(b)!!
            allowed = false
            assertNull(registry.network(b))
            assertTrue(runCatching { newer.availability() }.isFailure)
            allowed = true
            registry.retain("next-login", null, listOf(b))
            assertTrue(runCatching { newer.availability() }.isFailure)
            val final = registry.network(b)!!
            registry.retain(null, null, listOf(b))
            assertNull(registry.network(b)); assertTrue(runCatching { final.prepare() }.isFailure)
        } finally { registry.clear() }
    }

    private class FeedTransport(val mac: NativeCredentialStore.PairedMac) : MobileRpcTransport {
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        var capability = true
        override var supportsBrowserTunnels = true
        var statusGate: CompletableDeferred<Unit>? = null
        var listingGate: CompletableDeferred<Unit>? = null
        val listings = AtomicInteger()
        val opened = AtomicInteger()
        val closedLanes = AtomicInteger()
        override suspend fun connect() {}
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().toString(Charsets.UTF_8))
            val result = when (request.getString("method")) {
                "mobile.host.status" -> {
                    statusGate?.await()
                    JSONObject().put("mac_device_id", mac.deviceId).put("mac_instance_tag", mac.instanceTag)
                        .put("capabilities", JSONArray(if (capability) listOf(BrowserTunnelProtocol.CAPABILITY) else emptyList<String>()))
                }
                "mobile.workspace.list" -> JSONObject().put("workspaces", JSONArray())
                "notification.feed.list" -> JSONObject().put("revision", 1).put("notifications", JSONArray())
                else -> JSONObject()
            }
            incoming.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", result).toString().toByteArray()))
        }
        override suspend fun browserListeningPorts(): BrowserTunnelProtocol.ListeningPorts {
            listings.incrementAndGet(); listingGate?.await()
            return BrowserTunnelProtocol.ListeningPorts(emptyList(), false)
        }
        override suspend fun openBrowserTunnel(host: String, port: Int): BrowserTunnelLane {
            opened.incrementAndGet()
            return object : BrowserTunnelLane {
                override suspend fun read(maximumBytes: Int): ByteArray? = null
                override suspend fun write(bytes: ByteArray) {}
                override suspend fun finishSending() {}
                override fun close() { closedLanes.incrementAndGet() }
            }
        }
        override fun close() { incoming.close() }
    }

    @Test fun coordinatorRequiresFreshHostIdentityCapabilityAndNativeLaneRoute() = runBlocking<Unit> {
        val paired = mac("a")
        var transport = FeedTransport(paired).apply { statusGate = CompletableDeferred() }
        var allowed = true
        val coordinator = NativeFeedCoordinator(this, { MobileRpcClient(transport, { "fixture" }).also { it.connect() } }, { allowed })
        val access = coordinator.browserAccess(paired) { true }
        try {
            coordinator.updateMacs(listOf(paired))
            assertEquals(MacBrowserAvailability.NOT_CONNECTED, access.availability())
            assertTrue(runCatching { access.listeningPorts() }.isFailure)
            transport.statusGate!!.complete(Unit)
            until { coordinator.sources.value[paired.origin]?.availability == NativeFeedAvailability.CONNECTED }
            assertEquals(MacBrowserAvailability.AVAILABLE, access.availability())
            access.use("localhost", 3000) {}
            assertEquals(1, transport.opened.get())
            assertEquals(MacBrowserAvailability.NOT_CONNECTED, coordinator.browserAccess(paired.copy(instanceTag = "debug")) { true }.availability())
            allowed = false
            assertEquals(MacBrowserAvailability.NOT_CONNECTED, access.availability())
            allowed = true
            coordinator.pause()
            // Cached capabilities remain in the UI, but must not authorize a lane.
            assertTrue(BrowserTunnelProtocol.CAPABILITY in coordinator.sources.value[paired.origin]!!.capabilities)
            assertEquals(MacBrowserAvailability.NOT_CONNECTED, access.availability())
            transport = FeedTransport(paired).apply { capability = false }
            coordinator.updateMacs(listOf(paired))
            until { coordinator.sources.value[paired.origin]?.availability == NativeFeedAvailability.CONNECTED }
            assertEquals(MacBrowserAvailability.NEEDS_MAC_UPDATE, access.availability())
            assertTrue(runCatching { access.use("localhost", 3000) {} }.isFailure)
            assertEquals(0, transport.opened.get())
            coordinator.pause()
            transport = FeedTransport(paired).apply { supportsBrowserTunnels = false }
            coordinator.updateMacs(listOf(paired))
            until { coordinator.sources.value[paired.origin]?.availability == NativeFeedAvailability.CONNECTED }
            assertEquals(MacBrowserAvailability.ROUTE_WITHOUT_LANES, access.availability())
            assertTrue(runCatching { access.listeningPorts() }.isFailure)
            assertEquals(0, transport.listings.get())
        } finally { coordinator.close() }
    }

    @Test fun coordinatorCancelsOnlyRemovedOwnersRelayAndListingWithoutHoldingFeedMutex() = runBlocking<Unit> {
        val a = mac("a"); val b = mac("b")
        val ta = FeedTransport(a); val tb = FeedTransport(b)
        MobileRpcClient(ta, { "fixture" }).use { ca -> MobileRpcClient(tb, { "fixture" }).use { cb ->
            ca.connect(); cb.connect()
            val coordinator = NativeFeedCoordinator(this, { if (it == a) ca.lease {} else cb.lease {} }, { true })
            try {
                coordinator.updateMacs(listOf(a, b))
                until { coordinator.sources.value.values.count { it.availability == NativeFeedAvailability.CONNECTED } == 2 }
                val aa = coordinator.browserAccess(a) { true }; val ab = coordinator.browserAccess(b) { true }
                val enteredA = CompletableDeferred<Unit>(); val enteredB = CompletableDeferred<Unit>()
                val relayA = launch { aa.use("localhost", 3000) { enteredA.complete(Unit); awaitCancellation() } }
                val relayB = launch { ab.use("localhost", 3000) { enteredB.complete(Unit); awaitCancellation() } }
                enteredA.await(); enteredB.await()
                ta.listingGate = CompletableDeferred()
                val pending = async { runCatching { aa.listeningPorts() } }
                until { ta.listings.get() == 1 }
                withTimeout(1_000) { coordinator.refresh() }
                coordinator.updateMacs(listOf(b))
                withTimeout(1_000) { relayA.join() }
                assertTrue(withTimeout(1_000) { pending.await() }.isFailure)
                assertEquals(1, ta.closedLanes.get()); assertTrue(relayB.isActive)
                assertFalse(ca.isClosed); assertFalse(cb.isClosed)
                assertEquals("a", ca.hostStatus().getString("mac_device_id"))
                coordinator.pause()
                withTimeout(1_000) { relayB.join() }
                assertEquals(1, tb.closedLanes.get()); assertFalse(cb.isClosed)
            } finally { coordinator.close() }
        } }
    }
}
