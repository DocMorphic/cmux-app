package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection

internal fun nativeChiefStyleLabel(style: NativeConversationTextStyle) = when (style) {
    NativeConversationTextStyle.BOLD -> R.string.chief_rich_bold
    NativeConversationTextStyle.ITALIC -> R.string.chief_rich_italic
    NativeConversationTextStyle.UNDERLINE -> R.string.chief_rich_underline
    NativeConversationTextStyle.STRIKETHROUGH -> R.string.chief_rich_strikethrough
}
internal fun nativeChiefEffectLabel(effect: NativeConversationTextEffect) = when (effect) {
    NativeConversationTextEffect.BIG -> R.string.chief_rich_big
    NativeConversationTextEffect.SMALL -> R.string.chief_rich_small
    NativeConversationTextEffect.SHAKE -> R.string.chief_rich_shake
    NativeConversationTextEffect.NOD -> R.string.chief_rich_nod
    NativeConversationTextEffect.EXPLODE -> R.string.chief_rich_explode
    NativeConversationTextEffect.RIPPLE -> R.string.chief_rich_ripple
    NativeConversationTextEffect.BLOOM -> R.string.chief_rich_bloom
    NativeConversationTextEffect.JITTER -> R.string.chief_rich_jitter
}

/** The source's fixed custom-keyboard layout; Android supplies its native surface material.
 * The composer remains the focus/selection owner while this keyboard replaces the IME. */
@Composable internal fun NativeChiefTextEffectsPalette(editor: NativeChiefRichEditor, onClose: () -> Unit, modifier: Modifier = Modifier) {
    editor.revision
    val density = LocalDensity.current
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF0091FF) else Color(0xFF0088FF)
    val animated = nativeRichMotionEnabled()
    val close = stringResource(R.string.chief_rich_close)
    val bottomInset = WindowInsets.navigationBars.getBottom(density) / density.density
    val contentHeight = (330f - bottomInset).coerceAtLeast(0f).dp
    Box(modifier.fillMaxWidth().height(contentHeight).background(MaterialTheme.colorScheme.surfaceContainer)
        .testTag("ChiefTextEffectsPalette")) {
    BoxWithConstraints(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).height(contentHeight)) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).height(30.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chief_rich_title), style = TextStyle(fontSize = with(density) { 17.dp.toSp() },
                fontWeight = FontWeight.SemiBold, platformStyle = PlatformTextStyle(includeFontPadding = false)), color = ink)
        }
        Box(Modifier.align(AbsoluteAlignment.TopRight).absolutePadding(top = 10.dp, right = 16.dp).size(30.dp).clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)).clickable(role = Role.Button, onClick = onClose)
            .semantics { contentDescription = close }.testTag("ChiefTextEffectsClose"), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_primary_close), null, Modifier.size(15.dp), tint = ink)
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 50.dp).fillMaxWidth().height(44.dp)
            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)).padding(4.dp)) {
            NativeConversationTextStyle.entries.forEachIndexed { index, style ->
                val label = stringResource(nativeChiefStyleLabel(style))
                val on = editor.activeStyle and style.mask != 0L
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                    .background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable(role = Role.Button) { editor.toggle(style) }
                    .semantics { contentDescription = label; selected = on }.testTag("ChiefTextStyle:${style.wireName}"), contentAlignment = Alignment.Center) {
                    Text(listOf("B", "I", "U", "S")[index], modifier = Modifier.clearAndSetSemantics {}, color = ink, style = TextStyle(
                        fontSize = with(density) { 19.dp.toSp() }, fontWeight = if (style == NativeConversationTextStyle.BOLD) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (style == NativeConversationTextStyle.ITALIC) FontStyle.Italic else FontStyle.Normal,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        textDecoration = when (style) { NativeConversationTextStyle.UNDERLINE -> TextDecoration.Underline
                            NativeConversationTextStyle.STRIKETHROUGH -> TextDecoration.LineThrough; else -> TextDecoration.None }))
                }
            }
        }
        }
        val tileWidth = (maxWidth - 40.dp) / 2
        val tileHeight = (((330f - maxOf(bottomInset, 8f) - 104 - 24) / 4).toInt()).coerceAtLeast(36).dp
        NativeConversationTextEffect.entries.forEachIndexed { index, effect ->
            val label = stringResource(nativeChiefEffectLabel(effect))
            val on = editor.activeEffect == effect
            val shape = RoundedCornerShape(12.dp)
            Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset(x = 16.dp + (tileWidth + 8.dp) * (index % 2), y = 104.dp + (tileHeight + 8.dp) * (index / 2))
                .size(tileWidth, tileHeight).clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                .then(if (on) Modifier.border(2.dp, accent, shape) else Modifier)
                .clickable(role = Role.Button) { editor.toggle(effect) }
                .semantics { contentDescription = label; selected = on }.testTag("ChiefTextEffect:${effect.wireName}"), contentAlignment = Alignment.Center) {
                val style = TextStyle(fontSize = with(density) { 17.dp.toSp() }, fontWeight = FontWeight.Medium,
                    platformStyle = PlatformTextStyle(includeFontPadding = false))
                val runs = remember(label, effect) { listOf(NativeConversationTextRun(0, label.length, effect = effect)) }
                var layout by remember(label) { mutableStateOf<TextLayoutResult?>(null) }
                val text = nativeConversationStyled(AnnotatedString(label), runs, style, density, ink, true)
                Text(text, color = ink, style = style, onTextLayout = { layout = it },
                    modifier = nativeConversationEffectOverlay(label, runs, style, ink, { layout }, animated,
                        NativeConversationTextEffectMotion.seed(effect.wireName)).clearAndSetSemantics {})
            }
        }
    }
    }
}
