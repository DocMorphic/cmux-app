package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class AgentFeedQuestionDraftsTest {
    private val single = AgentFeedQuestion("single", null, "Choose", false,
        listOf(AgentFeedOption("a", "Alpha", null), AgentFeedOption("b", "Beta", null)))
    private val multi = single.copy(id = "multi", multiSelect = true)

    @Test fun selectingPresetRetainsInactiveCustomAndSingleChoiceCannotToggleItselfOff() {
        val initial = AgentFeedQuestionDrafts().write(single, "Custom")
        val selected = initial.choose(single, "a").choose(single, "a")
        assertEquals("Alpha", selected.answered(single)); assertEquals("Custom", selected.custom[single.id]); assertFalse(single.id in selected.customSelected)
        assertEquals("Beta", selected.choose(single, "b").answered(single))
        assertEquals(selected, selected.choose(single, "unknown"))
    }
    @Test fun customReplacesSelectedOptionsAndClearingItDoesNotResurrectOldChoices() {
        val draft = AgentFeedQuestionDrafts().choose(multi, "a").choose(multi, "b").write(multi, " Own answer ")
        assertEquals("Own answer", draft.answered(multi)); assertTrue(draft.selected[multi.id].isNullOrEmpty())
        assertNull(draft.write(multi, " ").answered(multi))
    }
    @Test fun allAnswersRequireEveryPromptAndFollowDisplayedOrder() {
        val partial = AgentFeedQuestionDrafts().choose(multi, "b").choose(multi, "a")
        assertNull(partial.answers(listOf(single, multi)))
        assertNull(partial.answers(emptyList()))
        val complete = partial.choose(single, "b")
        assertEquals(listOf("Beta", "Alpha, Beta"), complete.answers(listOf(single, multi)))
        assertEquals("Alpha", complete.choose(multi, "b").answered(multi))
    }
    @Test fun focusingCustomSelectsItEvenWhenEmptyAndCanRestoreItsRetainedText() {
        val draft = AgentFeedQuestionDrafts().choose(single, "a").selectCustom(single)
        assertNull(draft.answered(single)); assertTrue(single.id in draft.customSelected)
        assertTrue(draft.selected[single.id].isNullOrEmpty())
        val typed = draft.write(single, "  Own answer  ").choose(single, "b")
        assertEquals("Beta", typed.answered(single))
        val restored = AgentFeedQuestionDrafts.decode(typed.encode())!!
        assertEquals(typed, restored)
        assertEquals("Own answer", restored.selectCustom(single).answered(single))
        // A no-op text callback must not change the answer mode.
        assertEquals(typed, typed.write(single, "  Own answer  "))
    }
    @Test fun oldDraftCodecInfersModeAndNewCodecPreservesAnEmptyActiveAnswer() {
        val legacy = AgentFeedQuestionDrafts.decode("""{"selected":{},"custom":{"single":"Legacy"}}""")!!
        assertEquals("Legacy", legacy.answered(single))
        val empty = AgentFeedQuestionDrafts().choose(single, "b").selectCustom(single)
        assertEquals(empty, AgentFeedQuestionDrafts.decode(empty.encode()))
        assertNull(AgentFeedQuestionDrafts.decode(empty.encode())!!.answered(single))
    }
    @Test fun savedDraftsRoundTripUnicodeAndEmptyCustomWithoutSubmitting() {
        val original = AgentFeedQuestionDrafts().choose(multi, "b").write(single, "Line 1\n👩🏽‍💻 \"quotes\"")
        assertEquals(original, AgentFeedQuestionDrafts.decode(original.encode()))
        assertNull(AgentFeedQuestionDrafts.decode("invalid"))
    }
}
