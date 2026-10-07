package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

@Composable
internal fun TaskComposerFailureBanner(failure: TaskComposerFailure) {
    Row(Modifier.fillMaxWidth().background(Color(0x19FF9999), RoundedCornerShape(14.dp))
        .semantics { liveRegion = LiveRegionMode.Polite }.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.ic_task_warning), contentDescription = null,
            tint = Color(0xFFFF9999), modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(failure.title, color = Color(0xFFFF9999), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("TaskComposerFailureTitle"))
            Text(failure.message, color = Color(0xFFFF9999), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("TaskComposerFailureMessage"))
        }
    }
}

/** iOS's 38-point visual surfaces with Android's 48-dp activation targets. */
@Composable
internal fun TaskComposerCircle(label: String, icon: Int, enabled: Boolean, accent: Boolean = false,
    busy: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp).semantics { contentDescription = label }, enabled = enabled) {
        Surface(Modifier.size(38.dp), shape = CircleShape,
            color = if (accent && enabled) Color(0xFF76B9FF) else Color(0xFF25272B),
            contentColor = if (accent && enabled) Color(0xFF081421) else if (enabled) Color(0xFFE1E3E8) else Color(0xFF74777E)) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(painterResource(icon), null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun TaskComposerPill(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit) {
    FilledTonalButton(onClick, modifier.height(38.dp), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF25272B), contentColor = Color(0xFFE1E3E8))) {
        ProvideTextStyle(MaterialTheme.typography.labelMedium) { content() }
    }
}


/** Keep utilities and submit fixed while long agent/model names scroll between them. */
@Composable
internal fun TaskComposerPillScroller(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    val canvas = Color(0xFF0B0C0E)
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(scroll).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        if (scroll.value > 0) Box(Modifier.align(Alignment.CenterStart).width(12.dp).height(48.dp)
            .background(Brush.horizontalGradient(listOf(canvas, Color.Transparent))))
        if (scroll.value < scroll.maxValue) Box(Modifier.align(Alignment.CenterEnd).width(12.dp).height(48.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, canvas))))
    }
}
