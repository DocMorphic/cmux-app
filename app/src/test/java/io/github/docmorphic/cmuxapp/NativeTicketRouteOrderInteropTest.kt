package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class NativeTicketRouteOrderInteropTest(private val name: String, private val fixture: JSONObject) {
    @Test fun matchesPinnedSwiftRouteComparator() {
        val input = fixture.getJSONArray("routes")
        val routes = (0 until input.length()).map { index ->
            val row = input.getJSONObject(index)
            MobileAttachRoute(row.getString("id"), "tailscale", row.getLong("priority"),
                MobileAttachEndpoint.HostPort("100.64.0.7", 58000 + row.getInt("key")))
        }
        val expected = fixture.getJSONArray("expected").let { rows -> (0 until rows.length()).map(rows::getInt) }
        val actual = NativeTicketPairingRoutes.ordered(routes).map { (it.endpoint as MobileAttachEndpoint.HostPort).port - 58000 }
        assertEquals(name, expected, actual)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val resource = checkNotNull(NativeTicketRouteOrderInteropTest::class.java.getResourceAsStream("/pairing/route-order.json"))
            val root = resource.bufferedReader().use { JSONObject(it.readText()) }
            return root.getJSONArray("cases").let { rows -> (0 until rows.length()).map { index ->
                val value = rows.getJSONObject(index); arrayOf(value.getString("name"), value)
            } }
        }
    }
}
