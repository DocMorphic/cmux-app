package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class NativeWorkspaceRowPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val row = NativeWorkspace("row", "Pinned work", emptyList(), null, true, null, null, true,
        emptyList(), null, "Latest activity", "#12ABEF", description = "Custom description", unreadCount = 4)
    @Test fun pinStatusAndReservedRailAlignWhileTheOwnerDisconnects() {
        var availability by mutableStateOf(NativeFeedAvailability.CONNECTED)
        compose.setContent { CmuxTheme { Surface { Column(Modifier.width(393.dp)) {
            NativeWorkspaceRow(row, emptyList(), false, availability = availability, onOpen = {}, onAction = { _, _ -> })
            NativeWorkspaceRow(row.copy(id = "read", title = "Read workspace", isPinned = false, hasUnread = false,
                unreadCount = 0, color = null, description = null), emptyList(), false, onOpen = {}, onAction = { _, _ -> })
        } } } }
        compose.onNodeWithTag("workspace.pin:row", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("workspace.pin:read", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("workspace.status:row", useUnmergedTree = true).assertDoesNotExist()
        val rail = compose.onNodeWithTag("workspace.color:row", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val readRail = compose.onNodeWithTag("workspace.color:read", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(rail.left, readRail.left)
        val railPixels = compose.onNodeWithTag("workspace.color:row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
        assertEquals((3 * compose.activity.resources.displayMetrics.density).roundToInt().toFloat(), railPixels, 0.01f)
        compose.runOnIdle { availability = NativeFeedAvailability.OFFLINE }
        compose.onNodeWithTag("workspace.status:row", useUnmergedTree = true).assertTextEquals("Disconnected")
        val title = compose.onNodeWithTag("workspace.title:row", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag("workspace.status:row", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(status.left > title.left)
        assertTrue(status.top < title.bottom)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "workspace-row-presentation").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "row-layout.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.runOnIdle { availability = NativeFeedAvailability.CONNECTING }
        compose.onNodeWithTag("workspace.status:row", useUnmergedTree = true).assertTextEquals("Reconnecting")
    }
    @Test fun localizedDateAndTimeFormattingFollowTheStaticActivityStamp() {
        val now = Instant.parse("2026-10-04T12:30:00Z").toEpochMilli()
        val older = row.copy(lastActivityAt = Instant.parse("2026-10-03T23:30:00Z").epochSecond.toDouble())
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("10/3", workspaceActivityLabel(older, NativeFeedAvailability.CONNECTED, now, Locale.US, utc))
        assertEquals("3.10.", workspaceActivityLabel(older, NativeFeedAvailability.CONNECTED, now, Locale.GERMANY, utc))
        val today = row.copy(lastActivityAt = Instant.parse("2026-10-04T11:05:00Z").epochSecond.toDouble())
        assertEquals("11:05", workspaceActivityLabel(today, NativeFeedAvailability.CONNECTED, now, Locale.GERMANY, utc))
        assertEquals("Disconnected", workspaceActivityLabel(today, NativeFeedAvailability.OFFLINE, now, Locale.US, utc))
    }
}
