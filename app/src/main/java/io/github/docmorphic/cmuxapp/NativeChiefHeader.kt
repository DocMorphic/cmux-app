package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun NativeChiefPhoto(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.chief_avatar), null, modifier.clip(CircleShape))
}

@Composable
internal fun NativeChiefHeader(conversation: NativeChiefConversation, stopping: Boolean, onBack: () -> Unit,
    onInfo: () -> Unit, onStop: () -> Unit) {
    val density = LocalDensity.current
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE && LocalConfiguration.current.screenHeightDp < 480
    val safeTop = WindowInsets.statusBars.getTop(density) / density.density.toDouble()
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val ink = if (dark) Color.White else Color.Black
    val muted = ink.copy(alpha = .3f)
    val measurer = rememberTextMeasurer()
    val title = conversation.title.ifEmpty { conversation.id }
    val regular = nativeChiefTextStyle().copy(fontWeight = FontWeight.Bold)
    val points = with(density) { regular.fontSize.toDp().value }
    val nameStyle = regular.copy(fontSize = with(density) { NativeChiefHeaderPolicy.nameSize(points.toDouble()).toFloat().dp.toSp() })
    val name = remember(measurer, nameStyle, title) { measurer.measure(title, nameStyle, maxLines = 1) }
    val lines = remember(measurer, nameStyle, density) {
        val normal = nativeChiefTextStyleForHeader(17f, density)
        val a = measurer.measure("Hg", nameStyle); val b = measurer.measure("Hg", normal)
        (a.getLineBottom(0) - a.getLineTop(0)) to (b.getLineBottom(0) - b.getLineTop(0))
    }
    val unreadStyle = nativeChiefTextStyle(12).copy(fontSize = with(density) { 12.dp.toSp() }, fontWeight = FontWeight.Medium)
    val unread = conversation.unread.takeIf { it > 0 }?.toString()
    val unreadMeasure = remember(measurer, unreadStyle, unread) { unread?.let { measurer.measure(it, unreadStyle).size.width.toDouble() / density.density } }
    val backLabel = stringResource(R.string.chief_header_back)
    val photoLabel = stringResource(R.string.chief_header_photo)
    val stopLabel = stringResource(R.string.chief_header_stop)
    val unreadLabel = unread?.let { stringResource(R.string.chief_header_unread, conversation.unread) }
    val height = if (compact) (maxOf(safeTop, 24.0) + 54 - safeTop).toFloat().dp else 92.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).testTag("ChiefHeader")) {
        val frame = NativeChiefHeaderPolicy.header(maxWidth.value.toDouble(), if (maxWidth >= 440.dp) 20.0 else 16.0,
            name.size.width / density.density.toDouble(), lines.first / density.density.toDouble(), lines.second / density.density.toDouble(), unreadMeasure, compact, safeTop = safeTop)
        fun Modifier.glass() = clip(CircleShape).background(if (dark) Color(0xFF202020) else Color(0xFFEAEAEA))
            .border(.5.dp, ink.copy(alpha = .10f), CircleShape)
        Layout(modifier = Modifier.fillMaxSize(), content = {
            Box(Modifier.testTag("ChiefBack").glass().clickable(role = Role.Button, onClick = onBack).semantics {
                contentDescription = backLabel; unreadLabel?.let { stateDescription = it }
            }) {
                Icon(painterResource(R.drawable.ic_chief_back), null, Modifier.align(Alignment.CenterStart)
                    .offset(x = (if (unread == null) 17f else 15.333333f).dp, y = (1f / 3).dp).size(10.dp, 17.dp), tint = ink)
                if (unread != null && unreadMeasure != null) {
                    val pill = NativeChiefHeaderPolicy.unreadFrame(unreadMeasure)
                    Box(Modifier.offset(pill.x.toFloat().dp, pill.y.toFloat().dp).size(pill.width.toFloat().dp, 19.dp)
                        .clip(CircleShape).background(if (dark) Color(0xFFF3F3F3) else Color(0xFF404040)).clearAndSetSemantics {}, Alignment.Center) {
                        Text(unread, style = unreadStyle, color = if (dark) Color.Black else Color.White)
                    }
                }
            }
            Box(Modifier.testTag("ChiefHeaderStop").glass().clickable(enabled = !stopping, role = Role.Button, onClick = onStop)
                .semantics { contentDescription = stopLabel }, Alignment.Center) {
                // The vector's 14/24 ink reserve must draw the verified UIKit 17 pt stop.
                Icon(painterResource(R.drawable.ic_chief_stop), null, Modifier.size((204f / 7).dp), tint = ink)
            }
            Row(Modifier.testTag("ChiefHeaderName").then(if (compact) Modifier else Modifier.glass())
                .clickable(role = Role.Button, onClick = onInfo).semantics(mergeDescendants = true) {}
                .padding(start = if (compact) 12.dp else 13.dp, end = if (compact) 8.dp else 10.9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).testTag("ChiefHeaderTitle"), color = ink, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(if (compact) 4.dp else 5.9.dp))
                // UIKit reserves the full symbol image in compact mode. Portrait
                // reserves 4.7 points of ink and centers that same image over it.
                Box(Modifier.width(if (compact) (25f / 3).dp else 4.7.dp).height((44f / 3).dp), Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_chief_forward), null,
                        Modifier.requiredSize((25f / 3).dp, (44f / 3).dp).testTag("ChiefHeaderChevron"), tint = muted)
                }
            }
            Box(Modifier.testTag("ChiefHeaderPhoto").clickable(role = Role.Button, onClick = onInfo)
                .semantics { contentDescription = photoLabel }, Alignment.Center) {
                NativeChiefPhoto(Modifier.size(frame.avatar.width.toFloat().dp).testTag("ChiefHeaderAvatar"))
            }
        }) { children, c ->
            val rects = listOf(frame.back, frame.stop, frame.name, frame.avatar)
            val placeables = children.mapIndexed { i, child -> child.measure(Constraints.fixed(
                ((rects[i].width + if (i == 3 && !compact) 20 else 0) * density.density).roundToInt().coerceAtLeast(0), (rects[i].height * density.density).roundToInt().coerceAtLeast(0))) }
            layout(c.maxWidth, c.maxHeight) { placeables.forEachIndexed { i, p -> p.place(
                ((rects[i].x - if (i == 3 && !compact) 10 else 0) * density.density).roundToInt(), (rects[i].y * density.density).roundToInt()) } }
        }
    }
}

private fun nativeChiefTextStyleForHeader(points: Float, density: androidx.compose.ui.unit.Density) =
    androidx.compose.ui.text.TextStyle(fontSize = with(density) { points.dp.toSp() }, fontWeight = FontWeight.Bold,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
