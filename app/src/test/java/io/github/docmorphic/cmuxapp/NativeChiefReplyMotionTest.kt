package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefReplyMotionTest {
    private fun reference() = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-reply-motion.json"))
        .bufferedReader().use { it.readText() })

    private fun JSONArray.rows() = (0 until length()).map { getJSONObject(it) }
    private fun trip(row: JSONObject) = NativeChiefReplyTrip(row.getDouble("startY"), row.getDouble("targetY"),
        row.getDouble("initialOffsetX"), row.getDouble("easing"), row.getDouble("snapDistance"))

    @Test fun geometryAndBackgroundCurveMatchWholeActualSwift() {
        val source = reference()
        source.getJSONArray("easings").rows().forEach { row ->
            assertEquals(row.getDouble("value"), NativeChiefReplyMotion.threadEasing(row.getDouble("center"),
                row.getDouble("height"), row.getDouble("viewHeight"), row.getBoolean("out")), 1e-8)
        }
        source.getJSONArray("steps").rows().forEach { row ->
            assertEquals(row.getDouble("value"), NativeChiefReplyMotion.step(row.getDouble("easing"), row.getDouble("duration")), 1e-8)
        }
        source.getJSONArray("curves").rows().forEach { row ->
            assertEquals(row.getDouble("offset"), NativeChiefReplyMotion.offsetCurve(row.getDouble("progress")), 1e-8)
            assertEquals(row.getDouble("easeInOut"), NativeChiefReplyMotion.easeInOut(row.getDouble("progress")), 1e-8)
        }
        source.getJSONArray("snaps").rows().forEach { row ->
            assertEquals(row.getDouble("value"), NativeChiefReplyMotion.snapDistance(row.getDouble("scale"), row.getBoolean("out")), 1e-8)
        }
    }

    @Test fun everyTripFrameAndContainerMoveMatchesWholeActualReplyTrip() {
        fun assertState(label: String, expected: JSONObject, actual: NativeChiefReplyTrip) {
            assertEquals("$label startY", expected.getDouble("startY"), actual.startY, 1e-8)
            assertEquals("$label targetY", expected.getDouble("targetY"), actual.targetY, 1e-8)
            assertEquals("$label y", expected.getDouble("y"), actual.y, 1e-8)
            assertEquals("$label offsetX", expected.getDouble("offsetX"), actual.offsetX, 1e-8)
            assertEquals("$label stepCount", expected.getInt("stepCount"), actual.stepCount)
            assertEquals("$label resting", expected.getBoolean("resting"), actual.isResting)
        }
        reference().getJSONArray("trips").rows().forEach { row ->
            val actual = trip(row)
            val name = row.getString("name")
            assertState("$name initial", row.getJSONObject("initial"), actual)
            row.getJSONArray("actions").rows().forEachIndexed { index, action ->
                if (action.has("delta")) actual.containerMoved(action.getDouble("delta"))
                else actual.step(action.getDouble("duration"))
                assertState("$name action $index", action.getJSONObject("state"), actual)
            }
        }
    }

    @Test fun visibleTwinsUnseenRowsHeadingAndMissingAnchorMatchActualThreadRowsBody() {
        reference().getJSONArray("plans").rows().forEach { row ->
            val slots = row.getJSONArray("slots").rows().map {
                NativeChiefReplyMotion.Slot(it.getString("key"), if (it.isNull("messageID")) null else it.getString("messageID"),
                    it.getDouble("y"), it.getDouble("height"))
            }
            val origins = row.getJSONObject("starts")
            val starts = origins.keys().asSequence().associateWith { origins.getDouble(it) }
            val actual = NativeChiefReplyMotion.plan(slots, starts, row.getString("anchorID"), row.getDouble("anchorOffset"))
            val expected = row.getJSONArray("plan").rows()
            assertEquals(row.getString("name"), expected.size, actual.size)
            actual.zip(expected).forEach { (item, source) ->
                assertEquals(source.getString("key"), item.slot.key)
                assertEquals(source.getDouble("baseY"), item.slot.y, 1e-8)
                assertEquals(source.getDouble("startY"), item.startY, 1e-8)
                assertEquals(source.getDouble("targetY"), item.targetY, 1e-8)
                assertEquals(source.getDouble("initialOffsetX"), item.initialOffsetX, 1e-8)
            }
        }
    }

    @Test fun sourceAuthoredObservationFramesKeepTheirOriginalOnePointTolerance() {
        // These observations belong to upstream's tests, not to our physical
        // Android acceptance. Preserve the original source check as a guard.
        val source = reference()
        val observations = source.getJSONObject("sourceObservations")
        val rows = source.getJSONArray("trips").rows()
        val entering = trip(rows.single { it.getString("name") == "source-observed-enter" })
        val ys = observations.getJSONArray("intoY")
        val xs = observations.getJSONArray("intoX")
        repeat(ys.length()) { index ->
            if (index > 0) entering.step(1.0 / 60)
            assertEquals("enter y $index", ys.getDouble(index), entering.y, 1.0)
            assertEquals("enter x $index", xs.getDouble(index), entering.offsetX, 1.0)
        }
        while (!entering.isResting && entering.stepCount < 100) entering.step(1.0 / 60)
        assertTrue(entering.isResting)
        assertTrue(entering.stepCount < 60)
        val returning = trip(rows.single { it.getString("name") == "source-observed-exit" })
        val back = observations.getJSONArray("backY")
        repeat(back.length()) { index ->
            if (index > 0) returning.step(1.0 / 60)
            assertEquals("exit y $index", back.getDouble(index), returning.y, 1.0)
        }
        returning.step(1.0 / 60)
        assertEquals(-128.67, returning.y, 0.0)
    }
}
