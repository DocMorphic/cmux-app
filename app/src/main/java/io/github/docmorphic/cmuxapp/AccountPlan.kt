/* Plan presentation and wire fields follow cmux billing at
 * 186cec79781256867ad4516f0802118738bd2393. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale

internal data class AccountPlan(val id: String, val source: String, val management: String, val billingAvailable: Boolean,
    val teamBilled: Boolean = false) {
    val name get() = when (id) {
        "free" -> "Free"; "go" -> "Go"; "pro" -> "Pro"; "max" -> "Max"
        "founders" -> "Founder's Edition"
        else -> id.replace('-', ' ').replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
    }
    // Navigation is fixed and credential-free. The server's arbitrary manageUrl
    // is deliberately not a browser destination. No purchase or subscription
    // mutation is issued by this client.
    val manageUrl get() = when {
        source == "apple" -> "https://apps.apple.com/account/subscriptions"
        management == "stripe" && billingAvailable && !teamBilled -> "https://cmux.com/dashboard/billing"
        else -> null
    }
    val sourceLabel get() = when (source) {
        "apple" -> "Billed through the App Store."
        "stripe" -> "Billed on the web."
        else -> null
    }
    val canViewPlans get() = billingAvailable && source != "apple" && !teamBilled

    companion object {
        const val PRICING = "https://cmux.com/pricing"
        fun decode(json: JSONObject, expectedUser: String): AccountPlan {
            if (json.opt("authenticated") != true) throw AccountPlanSignedOut()
            require(json.optJSONObject("user")?.opt("id") == expectedUser) { "Plan account does not match this session" }
            fun field(key: String): String = (json.opt(key) as? String)?.takeIf {
                it.matches(Regex("[a-zA-Z0-9_-]{1,64}"))
            } ?: throw IllegalArgumentException("Invalid plan response")
            // Old servers only return free/pro in planId. New servers carry the
            // exact personal subscription in subscriptionPlanId (including Go/Max).
            val plan = field(if (json.has("subscriptionPlanId")) "subscriptionPlanId" else "planId")
            val source = field("billingSource")
            // iOS gives an active personal Stripe subscription priority over
            // the implicit team's billing notice. Team coverage otherwise
            // routes the user to their admin, not another personal checkout.
            val teamBilled = json.opt("teamPlanId") == "team" && source != "stripe"
            return AccountPlan(plan, source, field("billingManagement"), json.opt("billingAvailable") == true, teamBilled)
        }
    }
}
internal class AccountPlanSignedOut : java.io.IOException("Sign in again to load your plan")
internal data class AccountPlanState(val plan: AccountPlan? = null, val loading: Boolean = false, val failure: String? = null)

/** Scoped, read-only loading. An old response cannot publish after refresh or sign-out. */
internal class AccountPlanController(parent: CoroutineScope, private val load: suspend () -> AccountPlan,
    private val isCurrent: () -> Boolean) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(AccountPlanState())
    val state = mutable.asStateFlow()
    private var pending: Job? = null
    private var revision = 0
    fun refresh() {
        if (!job.isActive || !isCurrent()) return
        pending?.cancel()
        val request = ++revision
        mutable.value = mutable.value.copy(loading = true, failure = null)
        pending = scope.launch {
            fun admitted() { ensureActive(); if (request != revision || !isCurrent()) throw CancellationException("Plan account changed") }
            try {
                val plan = load(); admitted()
                mutable.value = AccountPlanState(plan)
            } catch (failure: Exception) {
                admitted()
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                val signedOut = failure is AccountPlanSignedOut || failure is CloudNotSignedIn ||
                    failure is CloudApiFailure && failure.status == 401
                mutable.value = mutable.value.copy(plan = mutable.value.plan.takeUnless { signedOut }, loading = false, failure = if (signedOut)
                    "Sign in again to load your plan." else "Couldn’t load your plan. Check your connection and try again.")
            }
        }
    }
    override fun close() { revision++; job.cancel(); mutable.value = AccountPlanState() }
}
