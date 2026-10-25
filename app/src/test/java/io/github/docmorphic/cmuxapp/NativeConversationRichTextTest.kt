package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Expected results come from the complete unmodified Swift files, not a parallel reference port. */
class NativeConversationRichTextTest {
    private fun reference(): JSONObject {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/parity/chief-rich-text.json")).use { it.readBytes() }
        assertEquals("Whole-Swift reference identity", "30dda39ba706fc61ee8ce78ca0d70aae6474b53553ef4f219ac82e7ac7d612b0",
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun JSONArray.rows() = (0 until length()).map { getJSONObject(it) }
    private fun effect(value: String) = NativeConversationTextEffect.entries.single { it.wireName == value }
    private fun JSONObject.effect(key: String): NativeConversationTextEffect? =
        if (isNull(key)) null else this@NativeConversationRichTextTest.effect(getString(key))
    private fun JSONArray.runs() = rows().map { NativeConversationTextRun(it.getInt("location"), it.getInt("length"),
        it.getLong("style"), it.effect("effect")) }

    @Test fun overlappingClippedPlainAndUnknownStyleRunsMatchFoundationSemanticKeys() {
        reference().getJSONArray("richText").rows().forEach { row ->
            val input = row.getJSONObject("input")
            val expected = row.getJSONArray("normalized").runs()
            assertEquals(input.getString("name"), expected,
                NativeConversationRichText.normalized(input.getJSONArray("runs").runs(), input.getString("text").length))
        }
    }
    @Test fun plainEditPreservesOnlyUnchangedUtf16PrefixAndSuffix() {
        reference().getJSONArray("richText").rows().forEach { row ->
            val input = row.getJSONObject("input")
            assertEquals(input.getString("name"), row.getJSONArray("carried").runs(),
                NativeConversationRichText.carried(input.getJSONArray("runs").runs(), input.getString("text"), input.getString("newText")))
        }
    }
    @Test fun foundationSendTrimShiftsAndClipsStylesAndEffectsTogether() {
        reference().getJSONArray("richText").rows().forEach { row ->
            val input = row.getJSONObject("input")
            val trimmed = row.getJSONObject("trimmed")
            assertEquals(input.getString("name"), NativeConversationRichText.Trimmed(trimmed.getString("text"), trimmed.getJSONArray("runs").runs()),
                NativeConversationRichText.trimmed(input.getString("text"), input.getJSONArray("runs").runs()))
        }
    }
    @Test fun repeatedStyleAndEffectMutationTracesMatchAttributedStringAndKeepIndependentAttributes() {
        reference().getJSONArray("richText").rows().forEach { row ->
            val input = row.getJSONObject("input")
            var runs = NativeConversationRichText.normalized(input.getJSONArray("runs").runs(), input.getString("text").length)
            val count = input.getString("text").length
            val start = input.getInt("start"); val length = input.getInt("length")
            row.getJSONArray("actions").rows().forEachIndexed { index, action ->
                val label = input.getString("name") + " action " + index
                if (action.has("style")) {
                    val actual = NativeConversationRichText.toggleStyle(action.getLong("style"), start, length, runs, count)
                    assertEquals(label, action.getBoolean("enabled"), actual.enabled)
                    runs = actual.runs
                } else {
                    val actual = NativeConversationRichText.toggleEffect(effect(action.getString("effect")), start, length, runs, count)
                    assertEquals(label, action.effect("resultEffect"), actual.effect)
                    runs = actual.runs
                }
                assertEquals(label, action.getJSONArray("runs").runs(), runs)
                val active = action.getJSONArray("activeStyles")
                NativeConversationTextStyle.entries.forEachIndexed { i, style ->
                    assertEquals("$label ${style.wireName}", active.getBoolean(i), NativeConversationRichText.hasAll(style.mask, start, length, runs, count))
                }
                assertEquals(label, action.effect("commonEffect"), NativeConversationRichText.commonEffect(start, length, runs, count))
            }
        }
    }
    @Test fun everyUtf16UnitIncludingSurrogateHalvesReadsNormalizedMetadata() {
        reference().getJSONArray("richText").rows().forEach { row ->
            val input = row.getJSONObject("input")
            val count = input.getString("text").length
            val expected = row.getJSONArray("normalized").runs()
            val actual = input.getJSONArray("runs").runs()
            for (i in -1..count) {
                val run = expected.singleOrNull { i in it.location until it.location + it.length }
                assertEquals(input.getString("name") + " unit " + i, run?.style ?: 0L, NativeConversationRichText.styleAt(i, actual, count))
                assertEquals(input.getString("name") + " unit " + i, run?.effect, NativeConversationRichText.effectAt(i, actual, count))
            }
        }
    }
    @Test fun wireAndPaletteOrderAreSourceOrderAndDoNotEmitUnknownBits() {
        val source = reference()
        source.getJSONArray("styleWire").rows().forEach { row ->
            val names = row.getJSONArray("names").let { a -> (0 until a.length()).map(a::getString) }
            assertEquals(names, NativeConversationTextStyle.wireNames(row.getLong("mask")))
            assertEquals(row.getLong("mask") and 15L, NativeConversationTextStyle.fromWire(names + listOf("unknown", "BOLD")))
        }
        assertEquals(source.getJSONArray("effectOrder").let { a -> (0 until a.length()).map(a::getString) },
            NativeConversationTextEffect.entries.map { it.wireName })
    }
    @Test fun everyEffectPoseStaggerDurationAndFontScaleMatchOriginalSwiftWithWrappingSeeds() {
        val source = reference()
        assertEquals(source.getDouble("cycle"), NativeConversationTextEffectMotion.CYCLE, 0.0)
        source.getJSONArray("motion").rows().forEachIndexed { index, row ->
            val input = row.getJSONObject("input")
            val effect = effect(input.getString("effect"))
            val i = input.getInt("index"); val count = input.getInt("count")
            assertEquals("probe $index font", row.getDouble("fontScale"), NativeConversationTextEffectMotion.fontScale(effect), 1e-12)
            assertEquals("probe $index duration", row.getDouble("duration"), NativeConversationTextEffectMotion.duration(effect), 1e-12)
            assertEquals("probe $index delay", row.getDouble("delay"), NativeConversationTextEffectMotion.delay(effect, i, count), 1e-12)
            val pose = NativeConversationTextEffectMotion.pose(effect, i, count, input.getDouble("time"), input.getString("seed").toULong())
            val values = listOf(pose.dx, pose.dy, pose.rotation, pose.scale, pose.opacity)
            val expected = row.getJSONArray("pose")
            values.forEachIndexed { component, actual -> assertEquals("probe $index pose $component", expected.getDouble(component), actual, 1e-12) }
        }
    }
    @Test fun utf8IdentitySeedMatchesWholeSwiftAtFullUnsignedWidth() {
        reference().getJSONArray("seeds").rows().forEach { row ->
            assertEquals(row.getString("text"), row.getString("seed").toULong(), NativeConversationTextEffectMotion.seed(row.getString("text")))
        }
    }
    @Test fun sourceSixtyHzTracksPreserveRestIntervalEdgesOmitOpaqueTracksAndKeepQuartzCoreMatrices() {
        reference().getJSONArray("keyframes").rows().forEachIndexed { case, row ->
            val input = row.getJSONObject("input")
            val actual = NativeConversationTextEffectMotion.keyframes(effect(input.getString("effect")), input.getInt("index"),
                input.getInt("count"), input.getDouble("fontSize"), input.getString("seed").toULong())
            val times = row.getJSONArray("transformTimes")
            val matrices = row.getJSONArray("transforms")
            assertEquals("track $case transform count", times.length(), actual.transforms.size)
            actual.transforms.forEachIndexed { i, frame ->
                assertEquals("track $case frame $i time", times.getDouble(i), frame.time, 1e-14)
                val c = cos(frame.rotation) * frame.scale
                val s = sin(frame.rotation) * frame.scale
                val matrix = listOf(c, s, 0.0, 0.0, -s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, frame.dx, frame.dy, 0.0, 1.0)
                val expected = matrices.getJSONArray(i)
                matrix.forEachIndexed { component, value ->
                    assertEquals("track $case frame $i matrix $component", expected.getDouble(component), value, 1e-12)
                }
            }
            val opacityTimes = row.getJSONArray("opacityTimes")
            val opacities = row.getJSONArray("opacities")
            assertEquals("track $case opacity count", opacityTimes.length(), actual.opacities.size)
            actual.opacities.forEachIndexed { i, frame ->
                assertEquals("track $case opacity $i time", opacityTimes.getDouble(i), frame.time, 1e-14)
                assertEquals("track $case opacity $i", opacities.getDouble(i), frame.opacity, 1e-12)
            }
        }
    }
}
