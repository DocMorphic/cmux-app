package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.roundToInt

/** Chief's direct Info page: backgrounds/media are absent in this backend. */
@Composable
internal fun NativeChiefDetails(conversation: NativeChiefConversation, state: NativeChiefListState,
    onClose: () -> Unit, onChange: (Boolean?, Boolean?) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val inheritedDensity = LocalDensity.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    NativeDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BackHandler(onBack = onClose)
        // Dialog owns another AndroidComposeView; retain the host's text-size override.
        CompositionLocalProvider(LocalDensity provides inheritedDensity) {
        val dark = MaterialTheme.colorScheme.background.luminance() < .5f
        val ink = if (dark) Color.White else Color.Black
        val background = if (dark) Color.Black else Color.White
        val density = LocalDensity.current
        val bodyStyle = nativeChiefTextStyle()
        val points = with(density) { bodyStyle.fontSize.toDp().value.toDouble() }
        val titleStyle = bodyStyle.copy(fontWeight = FontWeight.Bold,
            fontSize = with(density) { NativeChiefHeaderPolicy.titleSize(points).toFloat().dp.toSp() })
        val measurer = rememberTextMeasurer()
        val scroll = rememberScrollState()
        val title = conversation.title.ifEmpty { conversation.id }
        Box(Modifier.fillMaxSize().background(background.copy(alpha = .6f)).statusBarsPadding().navigationBarsPadding().testTag("ChiefDetails")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val titleHeight = remember(measurer, titleStyle, title, constraints.maxWidth) {
                measurer.measure(title, titleStyle, maxLines = 2,
                    constraints = Constraints(maxWidth = (constraints.maxWidth - with(density) { 32.dp.roundToPx() }).coerceAtLeast(1))).size.height / density.density.toDouble()
            }
            val actionsSize = NativeChiefHeaderPolicy.actionSize(points)
            val rest = NativeChiefHeaderPolicy.details(maxWidth.value.toDouble(), 0.0, titleHeight, actionsSize)
            val frame = NativeChiefHeaderPolicy.details(maxWidth.value.toDouble(), scroll.value / density.density.toDouble(), titleHeight, actionsSize)
            val margin = if (maxWidth >= 440.dp) 20.dp else 16.dp
            Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("ChiefDetailsInfo").padding(top = rest.height.toFloat().dp)
                .padding(horizontal = margin).padding(bottom = 20.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ink.copy(alpha = .09f))) {
                    ChiefDetailsToggle(stringResource(R.string.chief_details_hide_alerts), state.muted, "ChiefHideAlerts") { onChange(it, null) }
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(.5.dp).background(ink.copy(alpha = .12f)))
                    ChiefDetailsToggle(stringResource(R.string.chief_details_receipts), state.readReceipts, "ChiefReadReceipts") { onChange(null, it) }
                }
            }
            Layout(modifier = Modifier.fillMaxWidth().height(frame.height.toFloat().dp).background(background.copy(alpha = .65f)).testTag("ChiefDetailsHeader"), content = {
                NativeChiefPhoto(Modifier.testTag("ChiefDetailsPhoto"))
                Text(title, Modifier.fillMaxWidth().testTag("ChiefDetailsTitle").semantics { heading() }.graphicsLayer {
                    scaleX = frame.titleScale.toFloat(); scaleY = frame.titleScale.toFloat(); transformOrigin = TransformOrigin(.5f, 0f)
                }, color = ink, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Row(Modifier.graphicsLayer { alpha = frame.actionsAlpha.toFloat() }
                    .then(if (frame.actionsAlpha == 0.0) Modifier.clearAndSetSemantics {} else Modifier), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    listOf(Triple(R.drawable.ic_chief_call, R.string.chief_details_call, "Call"),
                        Triple(R.drawable.ic_chief_video, R.string.chief_details_video, "Video"),
                        Triple(R.drawable.ic_chief_mail, R.string.chief_details_mail, "Mail")).forEach { (icon, label, tag) ->
                        val description = stringResource(label)
                        Box(Modifier.size(actionsSize.toFloat().dp).clip(CircleShape).background(ink.copy(alpha = .09f))
                            .testTag("ChiefDetails$tag").semantics { role = Role.Button; contentDescription = description; disabled() }, Alignment.Center) {
                            Icon(painterResource(icon), null, Modifier.size(points.toFloat().dp), tint = ink.copy(alpha = .3f))
                        }
                    }
                }
            }) { children, c ->
                val avatar = children[0].measure(Constraints.fixed((frame.avatar.width * density.density).roundToInt(), (frame.avatar.height * density.density).roundToInt()))
                val label = children[1].measure(Constraints.fixedWidth((c.maxWidth - 32.dp.roundToPx()).coerceAtLeast(1)))
                val actions = children[2].measure(Constraints.fixed((frame.actions.width * density.density).roundToInt(), (frame.actions.height * density.density).roundToInt()))
                layout(c.maxWidth, c.maxHeight) {
                    avatar.place((frame.avatar.x * density.density).roundToInt(), 0)
                    label.place(16.dp.roundToPx(), (frame.titleTop * density.density).roundToInt())
                    actions.place((frame.actions.x * density.density).roundToInt(), (frame.actions.y * density.density).roundToInt())
                }
            }
            val backLabel = stringResource(R.string.chief_header_back)
            Box(Modifier.padding(start = margin).size(44.dp).clip(CircleShape).background(ink.copy(alpha = .09f))
                .testTag("ChiefDetailsBack").clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = backLabel }, Alignment.Center) {
                Icon(painterResource(R.drawable.ic_chief_back), null, Modifier.size(10.dp, 17.dp), tint = ink)
            }
        }
        }
        }
    }
}

@Composable
private fun ChiefDetailsToggle(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f).padding(vertical = 15.dp), style = nativeChiefTextStyle(), color = MaterialTheme.colorScheme.onBackground)
        val ink = MaterialTheme.colorScheme.onBackground
        val progress by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "Chief details switch")
        Canvas(Modifier.size(63.dp, 32.dp).testTag(tag).toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }) {
            drawRoundRect(if (checked) Color(0xFF34C759) else ink.copy(alpha = .24f), cornerRadius = CornerRadius(size.height / 2))
            val radius = size.height / 2 - 2.dp.toPx()
            drawCircle(Color.White, radius, Offset(size.height / 2 + (size.width - size.height) * progress, size.height / 2))
        }
    }
}

@Composable
internal fun NativeChiefServiceIntro(title: String) {
    val density = LocalDensity.current
    val body = nativeChiefTextStyle()
    val points = with(density) { body.fontSize.toDp().value.toDouble() }
    val style = nativeChiefTextStyle(11).copy(fontSize = with(density) { NativeChiefHeaderPolicy.captionSize(points).toFloat().dp.toSp() })
    val color = Color(0xFF8E8E93)
    Column(Modifier.fillMaxWidth().padding(top = (41f / 3).dp, bottom = (14f / 3).dp).testTag("ChiefServiceIntro"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = style.copy(fontWeight = FontWeight.Medium), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_workspace_lock), null, Modifier.size(6.7.dp, (28f / 3).dp), tint = color)
            Spacer(Modifier.width(3.dp))
            Text(stringResource(R.string.chief_service_subtitle), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
