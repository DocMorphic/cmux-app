/* Badge geometry and behavior follow cmux WorkspaceUnreadDot / WorkspaceGroupHeaderRow,
 * revision 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reserve the same leading gutter when read, with room for the badge and the next glyph. */
@Composable
internal fun NativeUnreadGutter(unread: NativeWorkspaceUnread, gap: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.width(18.5.dp + gap).height(20.dp).clearAndSetSemantics { }) {
        unread.badgeCount?.let { count ->
            Box(Modifier.offset(x = (-1.5).dp).size(20.dp).background(Color(0xFF76B9FF), CircleShape),
                contentAlignment = Alignment.Center) {
                BasicText(count.toString(), Modifier.width(17.dp),
                    style = TextStyle(color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
                    maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 6.25.sp, maxFontSize = 12.5.sp, stepSize = 0.25.sp))
            }
        }
    }
}

/** Android vector equivalents for common remote SF Symbol names; unknown names retain a folder. */
internal fun nativeWorkspaceGroupIcon(symbol: String?): Int {
    if (symbol == null || symbol == "folder.fill") return R.drawable.ic_workspace_folder_fill
    return when (symbol.removeSuffix(".fill")) {
        "folder" -> R.drawable.ic_workspace_folder
        "pin" -> R.drawable.ic_workspace_pin_fill
        "terminal", "terminal.square" -> R.drawable.ic_workspace_terminal
        "hammer" -> R.drawable.ic_workspace_hammer
        "wrench", "wrench.and.screwdriver" -> R.drawable.ic_workspace_wrench
        "globe", "globe.americas", "globe.europe.africa", "globe.asia.australia" -> R.drawable.ic_workspace_globe
        "bolt" -> R.drawable.ic_workspace_zap
        "testtube.2", "flask" -> R.drawable.ic_workspace_test_tubes
        "ladybug", "ant" -> R.drawable.ic_workspace_bug
        "doc", "doc.text", "document" -> R.drawable.ic_workspace_file_text
        "shippingbox", "cube", "cube.box" -> R.drawable.ic_workspace_package
        "star" -> R.drawable.ic_workspace_star
        "heart" -> R.drawable.ic_workspace_heart
        "bookmark" -> R.drawable.ic_workspace_bookmark
        "tag" -> R.drawable.ic_workspace_tag
        "briefcase" -> R.drawable.ic_workspace_briefcase
        "house" -> R.drawable.ic_workspace_house
        "gear", "gearshape" -> R.drawable.ic_workspace_settings
        "person", "person.2", "person.3" -> R.drawable.ic_workspace_users
        "cpu", "memorychip" -> R.drawable.ic_workspace_cpu
        "network", "point.3.connected.trianglepath.dotted" -> R.drawable.ic_workspace_network
        "server.rack", "externaldrive" -> R.drawable.ic_workspace_server
        "cloud" -> R.drawable.ic_workspace_cloud
        "lock", "lock.shield" -> R.drawable.ic_workspace_lock
        else -> R.drawable.ic_workspace_folder_fill
    }
}
