package io.github.docmorphic.cmuxapp

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Original reader: scrollable, top-padded status content with a centered intrinsic stack. */
@Composable
internal fun ColumnScope.AgentFeedFullTextStatus(failed: Boolean, onRetry: () -> Unit) {
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
    val message = stringResource(if (failed) R.string.cmux_ui_mobile_agentfeed_fulltext_unavailable_b62af026
        else R.string.cmux_ui_mobile_agentfeed_fulltext_loading_fa5e4486)
    val retry = stringResource(R.string.cmux_ui_mobile_agentfeed_fulltext_retry_6fd0587a)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val dark = NativeAppColors.dark
    val secondary = if (dark) Color(0x99EBEBF5) else Color(0x993C3C43)
    val link = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("AgentFeedFullTextStatusViewport")
        .verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.TopCenter) {
        // SwiftUI Text reports the longest wrapped line, allowing its VStack to
        // hug that text and center within ScrollView. Compose otherwise retains
        // the entire width constraint for wrapped text. Measure only this short
        // localized status, then use its natural painted line allocation.
        fun width(text: String): Int {
            val layout = measurer.measure(text, body, constraints = Constraints(maxWidth = constraints.maxWidth))
            return ceil((0 until layout.lineCount).maxOfOrNull {
                layout.getLineRight(it) - layout.getLineLeft(it)
            } ?: 0f).toInt().coerceAtMost(constraints.maxWidth)
        }
        val contentWidth = with(density) { maxOf(width(message), if (failed) width(retry) else 0).toDp() }
        Column(Modifier.width(contentWidth).testTag("AgentFeedFullTextStatusContent"),
            horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(if (failed) 16.dp else 0.dp)) {
            if (failed) {
                Text(message, Modifier.testTag("AgentFeedFullTextUnavailable"), style = body)
                // Foundation expands this compact painted text to the platform's
                // minimum touch bounds without adding Material button padding.
                Text(retry, Modifier.testTag("AgentFeedFullTextRetry").clickable(role = Role.Button, onClick = onRetry),
                    style = body, color = link)
            } else {
                Column(Modifier.fillMaxWidth().testTag("AgentFeedFullTextLoading").semantics(mergeDescendants = true) {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    AgentFeedFullTextSpinner(secondary)
                    Spacer(Modifier.height(with(density) { 6.5.sp.toDp() }))
                    Text(message, Modifier.testTag("AgentFeedFullTextLoadingLabel"), style = body, color = secondary)
                }
            }
        }
    }
}

private val agentFeedActivityFades = floatArrayOf(.2f, .267f, .267f, .417f, .567f, .7f, .85f, 1f)

/** Eight rounded fading spokes, as observed in the original native ProgressView. */
@Composable
private fun AgentFeedFullTextSpinner(color: Color) {
    val transition = rememberInfiniteTransition(label = "Full text activity")
    val phase by transition.animateFloat(0f, 8f,
        infiniteRepeatable(tween(800, easing = LinearEasing)), label = "Activity step")
    val size = with(LocalDensity.current) { 20.sp.toDp() }
    Canvas(Modifier.size(size).testTag("AgentFeedFullTextProgress")) {
        val radius = this.size.minDimension / 2
        for (spoke in 0 until 8) {
            val angle = spoke * Math.PI / 4
            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(color.copy(alpha = color.alpha * agentFeedActivityFades[(spoke - phase.toInt()).mod(8)]),
                center + direction * (radius * .5f), center + direction * (radius * .875f),
                strokeWidth = radius * .25f, cap = StrokeCap.Round)
        }
    }
}
