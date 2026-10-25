package io.github.docmorphic.cmuxapp

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Independent GET-user and other HTTP queues keep parallel source reads order-independent. */
internal class NativeTeamHttpFixture : QueueDispatcher() {
    val users = LinkedBlockingQueue<MockResponse>()
    private val unscripted = AtomicInteger()
    @Volatile var beforeUserResponse: (() -> Unit)? = null
    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.method == "GET" && request.path == "/api/v1/users/me") {
            beforeUserResponse?.invoke()
            return users.poll(5, TimeUnit.SECONDS) ?: run {
                unscripted.incrementAndGet()
                MockResponse().setResponseCode(599).setBody("Unscripted fixture user read")
            }
        }
        return super.dispatch(request)
    }
    override fun shutdown() {
        super.shutdown()
        check(unscripted.get() == 0) { "${unscripted.get()} unscripted fixture user reads" }
    }
}
internal fun MockWebServer.teamUsers(count: Int = 1, reply: () -> MockResponse) {
    val routed = dispatcher as? NativeTeamHttpFixture ?: NativeTeamHttpFixture().also {
        check(dispatcher.javaClass == QueueDispatcher::class.java) { "Keep the explicit fixture dispatcher" }
        dispatcher = it
    }
    repeat(count) { routed.users.add(reply()) }
}
internal fun MockWebServer.teamSelection(reply: () -> MockResponse) {
    teamUsers(reply = reply)
    enqueue(reply())
}
