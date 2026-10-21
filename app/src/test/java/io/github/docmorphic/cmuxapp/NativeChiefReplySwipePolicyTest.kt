package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefReplySwipePolicyTest {
    private fun vectors() = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-reply-swipe.json"))
        .bufferedReader().use { it.readText() })

    @Test fun offsetsAndBothIndicatorFramesMatchExecutedWholeSwiftGeometry() {
        val cases = vectors().getJSONArray("offsets")
        repeat(cases.length()) { i ->
            val row = cases.getJSONObject(i)
            val offset = NativeChiefReplySwipePolicy.bubbleOffset(row.getDouble("translation"))
            assertEquals(row.getDouble("offset"), offset, 1e-8)
            val progress = NativeChiefReplySwipePolicy.progress(offset)
            assertEquals(row.getDouble("progress"), progress, 1e-8)
            assertEquals(row.getDouble("scale"), NativeChiefReplySwipePolicy.scale(progress), 1e-8)
            for (outgoing in listOf(false, true)) {
                val key = if (outgoing) "outgoingIndicator" else "incomingIndicator"
                val actual = NativeChiefReplySwipePolicy.indicator(80.0, 60.0, outgoing, offset)
                if (row.isNull(key)) assertNull(actual)
                else {
                    val expected = row.getJSONArray(key); assertNotNull(actual)
                    listOf(actual!!.x, actual.y, actual.side).forEachIndexed { index, value -> assertEquals(expected.getDouble(index), value, 1e-8) }
                }
            }
        }
    }

    @Test fun minimumHitAreasAndHorizontalAnglesMatchExecutedSwift() {
        val cases = vectors().getJSONArray("areas")
        repeat(cases.length()) { i ->
            val row = cases.getJSONObject(i)
            val actual = NativeChiefReplySwipePolicy.swipeArea(NativeChiefReplySwipePolicy.Area(12.0, 18.0, row.getDouble("width"), 44.0), row.getBoolean("outgoing"))
            val expected = row.getJSONArray("area")
            listOf(actual.x, actual.y, actual.width, actual.height).forEachIndexed { index, value -> assertEquals(expected.getDouble(index), value, 1e-8) }
        }
        val angles = vectors().getJSONArray("angles")
        repeat(angles.length()) { i ->
            val row = angles.getJSONObject(i)
            assertEquals(row.getBoolean("allowed"), NativeChiefReplySwipePolicy.isSwipeAngle(row.getDouble("x"), row.getDouble("y")))
        }
    }

    @Test fun directionalCrossingsAndReleaseCancellationMatchActualSourceUpdateMethod() {
        val cases = vectors().getJSONArray("crossings")
        repeat(cases.length()) { i ->
            val steps = cases.getJSONObject(i).getJSONArray("steps")
            var committed = false; var pulses = 0; var haptics = 0; var indicator = 0.0
            repeat(steps.length()) { j ->
                val step = steps.getJSONObject(j)
                val offset = NativeChiefReplySwipePolicy.bubbleOffset(step.getDouble("translation"))
                if (!committed) indicator = NativeChiefReplySwipePolicy.progress(offset)
                when (NativeChiefReplySwipePolicy.crossing(offset, step.getDouble("velocity"), committed)) {
                    NativeChiefReplySwipePolicy.Crossing.COMMIT -> { committed = true; pulses++; haptics++ }
                    NativeChiefReplySwipePolicy.Crossing.UNCOMMIT -> { committed = false; haptics++ }
                    NativeChiefReplySwipePolicy.Crossing.NONE -> Unit
                }
                assertEquals(step.getDouble("offset"), offset, 1e-8)
                assertEquals(step.getBoolean("committed"), committed)
                assertEquals(step.getInt("pulses"), pulses); assertEquals(step.getInt("haptics"), haptics)
                assertEquals(step.getDouble("indicatorProgress"), indicator, 1e-8)
            }
            assertEquals(cases.getJSONObject(i).getBoolean("releaseCommit"), committed)
            assertFalse(cases.getJSONObject(i).getBoolean("cancelCommit"))
        }
    }

    @Test fun unacknowledgedLeftwardVerticalAndOutsideTouchesCannotOwnTheDrag() {
        val box = NativeChiefReplySwipePolicy.Area(0.0, 0.0, 50.0, 44.0)
        fun allowed(acknowledged: Boolean = true, translation: Double = 10.0, vx: Double = 100.0, vy: Double = 0.0, x: Double = 30.0, y: Double = 20.0) =
            NativeChiefReplySwipePolicy.canBegin(acknowledged, translation, vx, vy, x, y, box, false)
        assertTrue(allowed()); assertFalse(allowed(acknowledged = false)); assertFalse(allowed(translation = -1.0))
        assertFalse(allowed(vx = -100.0)); assertFalse(allowed(vy = 33.0)); assertFalse(allowed(x = 27.999)); assertFalse(allowed(y = 44.0))
        assertTrue(allowed(x = 183.999)); assertFalse(allowed(x = 184.0))
    }
}
