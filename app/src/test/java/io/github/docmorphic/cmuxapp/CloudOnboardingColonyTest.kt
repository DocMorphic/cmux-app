package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class CloudOnboardingColonyTest {
    @Test fun pinnedSwiftMethodBodiesAgreeOnWrapPopulationAndOneHundredTwentyStepBoundary() {
        val reference = JSONObject(javaClass.getResourceAsStream("/parity/cloud-onboarding-colony.json")!!.bufferedReader().use { it.readText() })
        assertEquals("a5911a1f92f3eeb11c4d7f3e2ef89204718c5009", reference.getString("upstreamRevision"))
        assertEquals("c0d7ee3894f2953a2708509d11eb0126edca1783558cd3be67f52d5c304f0293", reference.getString("sourceFileSha256"))
        val cases = reference.getJSONArray("cases"); assertEquals(8, cases.length())
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i); val columns = case.getInt("columns"); val rows = case.getInt("rows")
            val cells = BooleanArray(columns * rows); val alive = case.getJSONArray("alive")
            for (j in 0 until alive.length()) cells[alive.getInt(j)] = true
            val colony = CloudOnboardingColony(columns, rows, { .99 }, cells)
            // Preceding stable generations establish the real counter without
            // injecting or exposing a production state setter just for this test.
            repeat(case.getInt("stepCountBefore")) { colony.advance() }
            colony.advance()
            val expected = case.getJSONObject("expected")
            assertEquals(case.getString("id"), expected.getInt("stepCountAfter"), colony.stepCount)
            assertEquals(case.getString("id"), expected.getBoolean("reseeded"), colony.seedCount > 0)
            if (!expected.getBoolean("reseeded")) {
                val actual = colony.cells.indices.filter { colony.cells[it] }
                val wanted = expected.getJSONArray("aliveAfter")
                assertEquals(case.getString("id"), (0 until wanted.length()).map { wanted.getInt(it) }, actual)
            }
        }
    }
    private fun pattern(columns: Int, rows: Int, vararg positions: Pair<Int, Int>) =
        BooleanArray(columns * rows).also { cells -> positions.forEach { (row, col) -> cells[row * columns + col] = true } }

    @Test fun seedUsesStrictTwentyTwoPercentBoundaryAndOneInitialGeneration() {
        var n = 0
        val colony = CloudOnboardingColony(4, 3, { listOf(0.0, .21999, .22, .999)[n++ % 4] })
        assertEquals(6, colony.cells.count { it }); assertEquals(0, colony.stepCount)
        assertEquals(1L, colony.seedCount); assertEquals(0L, colony.advances)
        assertArrayEquals(booleanArrayOf(true, true, false, false), colony.cells.take(4).toBooleanArray())
    }

    @Test fun blinkerCrossesBothToroidalEdgesWithoutReseedingAtTheMinimumPopulation() {
        val initial = pattern(12, 12, 0 to 11, 0 to 0, 0 to 1, 6 to 4, 6 to 5, 6 to 6)
        val colony = CloudOnboardingColony(12, 12, { error("Should not reseed six survivors") }, initial)
        val vertical = pattern(12, 12, 11 to 0, 0 to 0, 1 to 0, 5 to 5, 6 to 5, 7 to 5)
        colony.advance(); assertArrayEquals(vertical, colony.cells)
        colony.advance(); assertArrayEquals(initial, colony.cells)
        assertEquals(0L, colony.seedCount); assertEquals(2L, colony.advances)
    }

    @Test fun stableBlocksSurviveExactlyOneHundredTwentyStepsBeforeReseeding() {
        val initial = pattern(12, 12, 2 to 2, 2 to 3, 3 to 2, 3 to 3, 8 to 8, 8 to 9, 9 to 8, 9 to 9)
        val colony = CloudOnboardingColony(12, 12, { .99 }, initial)
        repeat(120) { colony.advance(); assertArrayEquals(initial, colony.cells) }
        assertEquals(120, colony.stepCount); assertEquals(0L, colony.seedCount)
        colony.advance(); assertEquals(0, colony.cells.count { it })
        assertEquals(0, colony.stepCount); assertEquals(1L, colony.seedCount); assertEquals(121L, colony.advances)
    }

    @Test fun dyingColonyReseedsInsteadOfKeepingAnEmptyOrTinyGeneration() {
        val colony = CloudOnboardingColony(12, 12, { .01 }, pattern(12, 12, 6 to 4, 6 to 5, 6 to 6))
        colony.advance()
        assertEquals(144, colony.cells.count { it }); assertEquals(0, colony.stepCount)
        assertEquals(1L, colony.seedCount)
    }

    @Test fun productionDensityThresholdReseedsEvenSixSurvivingCells() {
        val colony = CloudOnboardingColony(36, 52, { .99 }, pattern(36, 52, 6 to 4, 6 to 5, 6 to 6, 40 to 20, 40 to 21, 40 to 22))
        colony.advance(); assertEquals(0, colony.cells.count { it }); assertEquals(1L, colony.seedCount)
    }

    @Test fun initialCellsAreOwnedAndReducedMotionHasNoTimeOrSpatialFlicker() {
        val initial = pattern(12, 12, 0 to 0)
        val colony = CloudOnboardingColony(12, 12, initial = initial)
        initial[0] = false; assertTrue(colony.cells[0])
        for (dark in listOf(false, true)) for (time in listOf(0.0, 1.0, 19.3)) for (row in 0..51) {
            assertEquals(if (dark) .10f else .16f, CloudOnboardingColony.opacity(dark, true, time, row, 17), 0f)
            assertTrue(CloudOnboardingColony.opacity(dark, false, time, row, 17) in 0f..if (dark) .20f else .26f)
        }
        assertEquals(80L, CloudOnboardingColony.TICK_MILLIS)
    }
}
