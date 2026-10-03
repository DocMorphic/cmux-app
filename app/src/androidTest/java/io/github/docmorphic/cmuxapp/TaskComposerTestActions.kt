package io.github.docmorphic.cmuxapp

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.json.JSONArray
import org.json.JSONObject

/** Exercise the same folder browser used by the iOS-shaped composer. */
internal fun ComposeTestRule.chooseTaskDirectory(peer: NativeFixturePeer, path: String) {
    peer.directoryResponse = { method, params ->
        if (method == "mobile.directory.search") JSONObject().put("directories", JSONArray().put(path))
            .put("search_scope", "all_indexed_volumes").put("gathering_complete", true)
        else JSONObject().put("current_path", params.getString("path")).put("entries", JSONArray())
            .put("offset", 0).put("limit", 50).put("total_count", 0)
    }
    openTaskOptions()
    onNodeWithContentDescription("Browse folders").performClick()
    onNodeWithText("Search folders").performTextInput(path)
    waitUntil(10_000) { onAllNodesWithContentDescription("Use folder: $path").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithContentDescription("Use folder: $path").performClick()
    onNodeWithText("Done").performClick()
}

internal fun ComposeTestRule.assertTaskDirectory(path: String) {
    openTaskOptions()
    onNodeWithContentDescription("Browse folders").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, path))
    onNodeWithText("Done").performClick()
}

/** The dock moves during the system IME animation. Invoke its accessible action
 * and wait for the sheet instead of injecting a tap at a stale screen position. */
internal fun ComposeTestRule.openTaskOptions() {
    onNodeWithContentDescription("Task Options").assertIsEnabled()
        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
    waitUntil(10_000) { onAllNodesWithContentDescription("Browse folders").fetchSemanticsNodes().isNotEmpty() }
}


/** Advance the test dispatcher after one scroll action; performScrollTo loops
 * without advancing it when a pill is clipped in the compact dock. */
internal fun ComposeTestRule.openTaskPicker(label: String) {
    onNode(hasScrollAction() and hasAnyDescendant(hasContentDescription(label)))
        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { scroll ->
            scroll(if (label == "Agent") -10_000f else 10_000f, 0f)
        }
    mainClock.advanceTimeBy(500)
    waitForIdle()
    onNodeWithContentDescription(label).assertIsDisplayed().performClick()
}
