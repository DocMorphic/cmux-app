package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NativeForegroundSessionAuthorityTest {
    private class Fixture(val server: MockWebServer, val account: NativeAccountTeams, val initial: NativeTeamScope) {
        val mac = NativeCredentialStore.PairedMac("fixture-route", "fixture-device", "Fixture Mac", "nightly",
            accountUserId = "user", accountTeamId = "one", stableOrigin = "fixture-origin", ticketRevision = "first")
        var saved = listOf(mac)
        val authority = NativeForegroundSessionAuthority(account) { saved }
        val clients = mutableListOf<MobileRpcClient>()
        suspend fun client(): Pair<MobileRpcClient, PoolTestTransport> {
            val wire = PoolTestTransport(MobileConnectionTransport.IROH)
            val client = MobileRpcClient(wire, { "fixture-access" })
            clients += client; client.connect()
            return client to wire
        }
        fun selection(team: String) {
            server.enqueue(MockResponse().setBody("{\"id\":\"user\"}"))
            user(team)
        }
        fun user(team:String)=server.enqueue(MockResponse().setBody(JSONObject().put("id","user")
            .put("selected_team",JSONObject().put("id",team)).toString()))
        fun profile(selected: String = "one", vararg teams: String = arrayOf("one", "two")) {
            user(selected)
            server.enqueue(MockResponse().setBody(JSONObject().put("items", JSONArray(teams.map {
                JSONObject().put("id", it).put("display_name", it)
            })).toString()))
        }
    }
    private suspend fun fixture(lock: Any = Any(), block: suspend Fixture.() -> Unit) {
        MockWebServer().use { server ->
            NativeAccountTeams({ "fixture-access" }, { "fixture-login" }, server.url("/api/v1/"), lock = lock).use { account ->
                val provisional = Fixture(server, account, NativeTeamScope("fixture-login", "user", "one", -1))
                provisional.profile()
                val fixture = Fixture(server, account, checkNotNull(account.refresh().scope))
                try { fixture.block() }
                finally { fixture.authority.clear(); fixture.clients.forEach { it.close() } }
            }
        }
    }

    @Test fun managementUsesOriginalBoundRecordAndConfirmedRevokeSurvivesRootStopButNotMembershipLoss() = runBlocking<Unit> {
        fixture {
            val record = mac.copy(code = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user")
            saved = listOf(record)
            val (client, _) = client(); authority.admit(initial, record, client)
            selection("two"); account.select("two")
            val management = checkNotNull(authority.computerManagement(record, client))
            assertTrue(management.available()); assertEquals(initial, management.team)
            assertNull(authority.computerManagement(record.copy(accountTeamId = "two", stableOrigin = "other"), client))
            val (replacement, _) = client(); assertNull(authority.computerManagement(record, replacement))
            val permit = management.beginRevoke()
            client.close(); authority.release(client)
            assertFalse(management.available()); assertTrue(runCatching { management.beginRevoke() }.isFailure)
            assertTrue(permit.current())
            profile("two", "two"); account.refresh()
            assertFalse(permit.current()); permit.close()
        }
    }

    @Test fun managementRecordRevisionCannotBeRevivedByRenameOrAnotherSameLocatorPairing() = runBlocking<Unit> {
        fixture {
            val record = mac.copy(code = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user")
            saved = listOf(record)
            val (client, _) = client(); authority.admit(initial, record, client)
            val management = checkNotNull(authority.computerManagement(record, client))
            val permit = management.beginRevoke()
            saved = listOf(record.copy(name = "Renamed")); assertTrue(management.available()); assertTrue(permit.current())
            saved = listOf(record.copy(nativeRouteCode = "changed"))
            assertFalse(management.available()); assertFalse(permit.current())
            assertNull(authority.computerManagement(record, client)); permit.close()
        }
    }

    @Test fun existingForegroundKeepsOriginalScopeAndRpcWhileSelectedTeamChanges() = runBlocking<Unit> {
        fixture {
            val (client, wire) = client()
            authority.admit(initial, mac, client)
            selection("two"); account.select("two")
            assertFalse(account.isCurrent(initial))
            assertSame(initial, authority.scopeFor(mac.code, client))
            assertTrue(runCatching { authority.admit(initial, mac, client) }.isFailure)
            assertSame(initial, authority.scopeFor(mac.code, client))
            coroutineScope {
                val request = async { client.workspaces() }
                wire.answer(withTimeout(2000) { wire.sent.receive() })
                assertEquals("mobile.workspace.list", withTimeout(2000) { request.await() }.getString("method"))
            }
            assertEquals(1, wire.connects.get()); assertEquals(0, wire.closes.get())
            selection("one"); account.select("one")
            assertSame(initial, authority.scopeFor(mac.code, client))
        }
    }

    @Test fun paneScopeRequiresExactBoundRecordAndVerifiedContinuationAfterTeamSelection() = runBlocking<Unit> {
        fixture {
            val (client, _) = client(); authority.admit(initial, mac, client)
            selection("two"); account.select("two")
            val sibling = mac.copy(accountTeamId = "two", stableOrigin = "sibling-origin")
            val context = nativeForegroundPaneContext(initial.login, account.state.value.scope, listOf(sibling),
                saved + sibling, mac.code, client, authority::scopeFor)
            assertTrue(context.foreground); assertEquals(initial, context.team); assertEquals(mac, context.mac)
            assertEquals(listOf(mac), context.macs)
            for (changed in listOf(sibling, mac.copy(deviceId = "other"), mac.copy(ticketRevision = "replacement"),
                mac.copy(nativeRouteCode = "different"))) assertNull(authority.scopeFor(changed, client))
            assertEquals(initial, authority.scopeFor(mac.copy(name = "Renamed"), client))
            profile("two", "two"); account.refresh()
            assertNull(authority.scopeFor(mac, client))
            val retired = nativeForegroundPaneContext(initial.login, account.state.value.scope, listOf(sibling),
                saved + sibling, mac.code, client, authority::scopeFor)
            assertFalse(retired.foreground); assertEquals(sibling, retired.mac)
        }
    }

    @Test fun lateReleaseAndQueriesForOtherHandlesCannotEraseReplacementBinding() = runBlocking<Unit> {
        fixture {
            val (old, _) = client(); val (replacement, _) = client()
            authority.admit(initial, mac, old); authority.admit(initial, mac, replacement)
            authority.release(old)
            assertNull(authority.scopeFor(mac.code, old)); assertNull(authority.scopeFor("other-route", replacement))
            assertSame(initial, authority.scopeFor(mac.code, replacement))
            authority.release(replacement); assertNull(authority.scopeFor(mac.code, replacement))
        }
    }

    @Test fun renameIsHarmlessButObservedRouteTicketAndVisibilityReplacementRetireBinding() = runBlocking<Unit> {
        fixture {
            val (client, _) = client()
            for (replacement in listOf(mac.copy(code = "changed-route"), mac.copy(ticketRevision = "changed"),
                mac.copy(accountTeamId = "two"))) {
                saved = listOf(mac); authority.admit(initial, mac, client)
                saved = listOf(mac.copy(name = "Renamed")); assertSame(initial, authority.scopeFor(mac.code, client))
                saved = listOf(replacement); assertNull(authority.scopeFor(mac.code, client))
                saved = listOf(mac); assertNull(authority.scopeFor(mac.code, client))
            }
            authority.admit(initial, mac, client)
            saved = listOf(mac, mac.copy(name = "Ambiguous")); authority.reconcile()
            saved = listOf(mac); assertNull(authority.scopeFor(mac.code, client))
            authority.admit(initial, mac, client); saved = emptyList(); authority.reconcile()
            saved = listOf(mac); assertNull(authority.scopeFor(mac.code, client))
        }
    }

    @Test fun membershipRemovalAndReadditionCannotResurrectForegroundWithoutNewVerifiedAdmission() = runBlocking<Unit> {
        fixture {
            val (client, _) = client(); authority.admit(initial, mac, client)
            profile("two", "two"); account.refresh(); authority.reconcile()
            assertNull(authority.scopeFor(mac.code, client))
            profile("one", "one", "two"); account.refresh()
            assertNull(authority.scopeFor(mac.code, client))
            authority.admit(checkNotNull(account.state.value.scope), mac, client)
            assertNotNull(authority.scopeFor(mac.code, client))
            account.clear(); authority.reconcile(); assertNull(authority.scopeFor(mac.code, client))
        }
    }

    @Test fun mismatchedOrClosedAdmissionCannotReplaceHealthyForegroundAndClosedHandleCannotRevive() = runBlocking<Unit> {
        fixture {
            val (client, _) = client(); authority.admit(initial, mac, client)
            val (closed, _) = client(); closed.close()
            assertTrue(runCatching { authority.admit(initial, mac, closed) }.isFailure)
            assertTrue(runCatching { authority.admit(initial, mac.copy(accountUserId = "other"), client) }.isFailure)
            assertTrue(runCatching { authority.admit(initial, mac.copy(instanceTag = "sibling"), client) }.isFailure)
            assertSame(initial, authority.scopeFor(mac.code, client))
            client.close(); assertNull(authority.scopeFor(mac.code, client))
            assertTrue(runCatching { authority.admit(initial, mac, client) }.isFailure)
        }
    }

    @Test fun delayedAdmissionCannotOverwriteNewForegroundBinding() = runBlocking<Unit> {
        fixture {
            val pause = AtomicBoolean(false)
            val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            val owner = NativeForegroundSessionAuthority(account) {
                if (pause.compareAndSet(true, false)) {
                    entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
                }
                saved
            }
            val (old, _) = client(); val (slow, _) = client(); val (new, _) = client()
            owner.admit(initial, mac, old)
            val failure = AtomicReference<Throwable?>()
            pause.set(true)
            val pending = thread(isDaemon = true) {
                try { owner.admit(initial, mac, slow) } catch (error: Throwable) { failure.set(error) }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                owner.admit(initial, mac, new)
            } finally { resume.countDown(); pending.join(5000) }
            assertFalse(pending.isAlive); assertTrue(failure.get() is IllegalStateException)
            assertSame(initial, owner.scopeFor(mac.code, new)); assertNull(owner.scopeFor(mac.code, slow))
            owner.clear()
        }
    }

    @Test fun releaseDoesNotWaitForReaderBlockedOnAccountCredentialLock() = runBlocking<Unit> {
        val credentials = Any()
        fixture(credentials) {
            val (client, _) = client(); authority.admit(initial, mac, client)
            val read = AtomicReference<NativeTeamScope?>(); val problem = AtomicReference<Throwable?>()
            val released = CountDownLatch(1)
            lateinit var reader: Thread; lateinit var releaser: Thread
            var finishedWhileCredentialsHeld = false
            synchronized(credentials) {
                reader = thread(isDaemon = true) {
                    try { read.set(authority.scopeFor(mac.code, client)) } catch (error: Throwable) { problem.set(error) }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (reader.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
                assertEquals(Thread.State.BLOCKED, reader.state)
                releaser = thread(isDaemon = true) {
                    try { authority.release(client) } catch (error: Throwable) { problem.set(error) }
                    finally { released.countDown() }
                }
                finishedWhileCredentialsHeld = released.await(2, TimeUnit.SECONDS)
            }
            reader.join(5000); releaser.join(5000)
            problem.get()?.let { throw it }
            assertFalse(reader.isAlive); assertFalse(releaser.isAlive)
            assertTrue("Foreground release waits for a nested credential monitor", finishedWhileCredentialsHeld)
            assertNull(read.get()); assertNull(authority.scopeFor(mac.code, client))
        }
    }

    @Test fun transportActivationFailureRestoresPreviousBindingAndOnlyClosesRejectedReservation() = runBlocking<Unit> {
        fixture {
            val reservations = mutableListOf<Pair<MobileRpcClient, java.util.concurrent.atomic.AtomicInteger>>()
            var reject = false
            val owner = NativeForegroundSessionAuthority(account, { saved }, { _, client ->
                val closes = java.util.concurrent.atomic.AtomicInteger(); reservations += client to closes
                object : NativeForegroundTransport {
                    override fun activate(stillCurrent: () -> Boolean) {
                        check(stillCurrent()); if (reject) error("Fixture activation rejection")
                    }
                    override fun close() { closes.incrementAndGet() }
                }
            })
            val (first, _) = client(); val (second, _) = client()
            owner.admit(initial, mac, first)
            reject = true
            assertTrue(runCatching { owner.admit(initial, mac, second) }.isFailure)
            assertSame(initial, owner.scopeFor(mac.code, first)); assertNull(owner.scopeFor(mac.code, second))
            assertEquals(0, reservations[0].second.get()); assertEquals(1, reservations[1].second.get())
            reject = false; owner.admit(initial, mac, second)
            assertEquals(1, reservations[0].second.get()); owner.release(first)
            assertSame(initial, owner.scopeFor(mac.code, second)); assertEquals(0, reservations[2].second.get())
            owner.clear(); owner.clear(); assertEquals(1, reservations[2].second.get())
        }
    }
}
