package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountPlanTest {
    private fun response(plan: String = "max") = JSONObject().put("authenticated", true).put("billingAvailable", true)
        .put("subscriptionPlanId", plan).put("planId", "pro").put("billingSource", "stripe").put("billingManagement", "stripe")
        .put("user", JSONObject().put("id", "user"))

    @Test fun exactSubscriptionIsUsedAndInvalidIdentityCannotLookLikeAFreePlan() {
        assertEquals("Max", AccountPlan.decode(response(), "user").name)
        assertEquals("Go", AccountPlan.decode(response("go"), "user").name)
        assertEquals("Pro", AccountPlan.decode(response().apply { remove("subscriptionPlanId") }, "user").name)
        assertEquals("Future Plan", AccountPlan.decode(response("future-plan"), "user").name)
        assertTrue(runCatching { AccountPlan.decode(response(), "replacement") }.isFailure)
        assertTrue(runCatching { AccountPlan.decode(response().put("authenticated", false), "user") }.exceptionOrNull() is AccountPlanSignedOut)
        for (value in listOf(JSONObject.NULL, "", "max\nfree", "a".repeat(65))) {
            assertTrue(runCatching { AccountPlan.decode(response().put("subscriptionPlanId", value), "user") }.isFailure)
        }
    }

    @Test fun managementLinksAreFixedAndAppleDoesNotOfferADuplicateWebPurchase() {
        val web = AccountPlan.decode(response().put("manageUrl", "https://untrusted.example/token"), "user")
        assertEquals("https://cmux.com/dashboard/billing", web.manageUrl); assertTrue(web.canViewPlans)
        val apple = web.copy(source = "apple", management = "external", billingAvailable = false)
        assertEquals("https://apps.apple.com/account/subscriptions", apple.manageUrl); assertFalse(apple.canViewPlans)
        assertNull(web.copy(management = "none").manageUrl)
        assertNull(web.copy(billingAvailable = false).manageUrl)
        assertFalse(web.copy(billingAvailable = false).canViewPlans)
    }

    @Test fun nativePlanRequestIsAuthenticatedGetAndDoesNotUseApplePurchaseRoutes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response().toString()))
            val owner = CloudAccountScope("login", "user", "team", 1)
            CloudApi(owner, { CloudCredentials(owner, "test-access", "test-refresh") }, { true },
                CloudApiRequests(server.url("/").toString())).use { api ->
                assertEquals("max", api.accountPlan().id)
                val request = server.takeRequest()
                assertEquals("GET", request.method); assertEquals("/api/billing/plan", request.path)
                assertEquals(0, request.bodySize)
                assertEquals("Bearer test-access", request.getHeader("Authorization"))
                assertEquals("test-refresh", request.getHeader("X-Stack-Refresh-Token"))
                assertNull(request.getHeader("x-cmux-bundle-id")); assertNull(request.getHeader("Cookie"))
            }
        }
    }

    @Test fun teamCoverageAvoidsPersonalOffersWithoutMaskingAnActiveWebSubscription() {
        val team = AccountPlan.decode(response("free").put("teamPlanId", "team").put("billingSource", "none"), "user")
        assertTrue(team.teamBilled); assertFalse(team.canViewPlans); assertNull(team.manageUrl)
        val personal = AccountPlan.decode(response("pro").put("teamPlanId", "team"), "user")
        assertFalse(personal.teamBilled); assertTrue(personal.canViewPlans); assertNotNull(personal.manageUrl)
    }

    @Test fun refreshKeepsLastKnownPlanButSignedOutResponseClearsIt() = runTest {
        var result: suspend () -> AccountPlan = { AccountPlan("max", "stripe", "stripe", true) }
        AccountPlanController(this, { result() }, { true }).use { controller ->
            controller.refresh(); runCurrent(); assertEquals("max", controller.state.value.plan?.id)
            result = { throw java.io.IOException("raw server detail must not be displayed") }
            controller.refresh(); runCurrent()
            assertEquals("max", controller.state.value.plan?.id)
            assertFalse(controller.state.value.failure!!.contains("raw server")); assertFalse(controller.state.value.loading)
            result = { throw AccountPlanSignedOut() }
            controller.refresh(); runCurrent()
            assertNull(controller.state.value.plan); assertTrue(controller.state.value.failure!!.contains("Sign in"))
        }
    }

    @Test fun supersededAndRetiredRequestsCannotPublishTheirResults() = runTest {
        val gates = mutableListOf<CompletableDeferred<AccountPlan>>()
        var current = true
        AccountPlanController(this, {
            val gate = CompletableDeferred<AccountPlan>(); gates += gate
            withContext(NonCancellable) { gate.await() }
        }, { current }).use { controller ->
            controller.refresh(); runCurrent()
            controller.refresh(); runCurrent()
            gates[1].complete(AccountPlan("pro", "stripe", "stripe", true)); runCurrent()
            gates[0].complete(AccountPlan("max", "apple", "external", true)); runCurrent()
            assertEquals("pro", controller.state.value.plan?.id)
            controller.refresh(); runCurrent(); current = false
            gates[2].complete(AccountPlan("go", "stripe", "stripe", true)); runCurrent()
            assertEquals("pro", controller.state.value.plan?.id)
            controller.close(); assertNull(controller.state.value.plan)
        }
    }
}
