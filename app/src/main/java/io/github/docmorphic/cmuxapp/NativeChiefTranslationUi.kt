package io.github.docmorphic.cmuxapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt

internal fun chiefTranslationLanguageName(language: NativeChiefTranslationLanguage, locale: Locale) =
    Locale.forLanguageTag(language.code).getDisplayLanguage(locale).ifEmpty { language.tag }

internal sealed interface NativeChiefTranslationSheet {
    val row: String
    val anchor: Rect?
    data class Choice(override val row: String, override val anchor: Rect? = null) : NativeChiefTranslationSheet
    data class From(override val row: String, val conversation: Boolean,
        val languages: List<NativeChiefTranslationLanguage>, override val anchor: Rect? = null) : NativeChiefTranslationSheet
}

/** Presentation work is canceled on disposal. Accepted translations belong to the retained model. */
internal class NativeChiefTranslationUi(private val session: NativeChiefTranscriptSession,
    private val model: NativeChiefTranslations, private val scope: CoroutineScope, private val locale: Locale,
    private val onError: (String) -> Unit) : AutoCloseable {
    var sheet by mutableStateOf<NativeChiefTranslationSheet?>(null); private set
    private var epoch = 0L
    private var work: Job? = null
    private var closed = false
    private var locateAnchor: (() -> Rect?)? = null
    private fun current(row: String) = !closed && try { session.actionItem(row) != null } catch (_: IllegalStateException) { false }
    fun dismiss() { ++epoch; work?.cancel(); work = null; sheet = null; locateAnchor = null }
    fun showChoice(row: String, locate: () -> Rect? = { null }) {
        dismiss()
        if (current(row) && model.canTranslate(row)) {
            locateAnchor = locate
            sheet = NativeChiefTranslationSheet.Choice(row, locate())
        }
    }
    fun anchor(request: NativeChiefTranslationSheet): Rect? =
        if (sheet === request && current(request.row)) locateAnchor?.invoke() else null
    fun choose(request: NativeChiefTranslationSheet, conversation: Boolean, language: NativeChiefTranslationLanguage? = null) {
        if (sheet !== request) return
        if (!current(request.row)) { dismiss(); return }
        val locate = locateAnchor
        dismiss()
        val generation = epoch
        work = scope.launch {
            try {
                val outcome = if (conversation) model.translateConversation(request.row, language) else model.translateMessage(request.row, language)
                if (outcome == NativeChiefTranslationOutcome.NEEDS_SOURCE_LANGUAGE && language == null) {
                    val collator = Collator.getInstance(locale)
                    val languages = model.sourceLanguages().sortedWith { a, b -> collator.compare(chiefTranslationLanguageName(a, locale), chiefTranslationLanguageName(b, locale)) }
                    if (generation == epoch && current(request.row) && languages.isNotEmpty()) {
                        locateAnchor = locate
                        sheet = NativeChiefTranslationSheet.From(request.row, conversation, languages, locate?.invoke())
                    }
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation == epoch && current(request.row)) onError(error.message ?: "Translation unavailable")
            }
        }
    }
    fun toggle(row: String) { if (current(row)) try { model.toggleOriginal(row) } catch (_: IllegalStateException) { } }
    override fun close() { closed = true; dismiss() }
}

@Composable
internal fun NativeChiefTranslationDownloadHost(host: NativeChiefTranslationPlatformHost, model: NativeChiefTranslations) {
    val request by host.download.state.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A provider using a separate task can return RESULT_CANCELED immediately.
    // Resume translation only after this foreground host actually leaves and returns.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    DisposableEffect(host, model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) host.download.settingsLeftApp()
            if (event == Lifecycle.Event.ON_RESUME) {
                host.download.resumeFromSettings()
                try { model.refreshAvailability() } catch (_: IllegalStateException) { }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(host, request?.id, request?.settingsOpen) {
        val pending = request?.takeIf { it.settingsOpen } ?: return@LaunchedEffect
        delay(5_000)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !host.download.externalDeparted)
            host.download.settingsFailed(pending.id)
    }
    val pending = request?.takeUnless { it.settingsOpen } ?: return
    AlertDialog(onDismissRequest = { host.download.decline(pending.id) }, modifier = Modifier.testTag("ChiefTranslationDownload"),
        title = { Text(stringResource(R.string.chief_translation_indicator_download, chiefTranslationLanguageName(pending.source, locale))) },
        text = { Text(stringResource(R.string.chief_translation_download_explanation,
            chiefTranslationLanguageName(pending.source, locale), chiefTranslationLanguageName(pending.target, locale))) },
        confirmButton = { TextButton(onClick = {
            if (host.download.openSettings(pending.id)) try {
                val intent = checkNotNull(host.settings())
                launcher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
            } catch (_: Exception) { host.download.settingsFailed(pending.id) }
        }, modifier = Modifier.testTag("ChiefTranslationOpenSettings")) { Text(stringResource(R.string.chief_translation_open_settings)) } },
        dismissButton = { TextButton(onClick = { host.download.decline(pending.id) }) { Text(stringResource(R.string.chief_translation_cancel)) } })
}

@Composable
internal fun NativeChiefTranslationCaption(presentation: NativeChiefTranslationPresentation, outgoing: Boolean,
    tailOverhang: Dp, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val label = when (presentation.caption) {
        NativeChiefTranslationCaption.TRANSLATING -> stringResource(R.string.chief_translation_caption_translating)
        NativeChiefTranslationCaption.SHOW_ORIGINAL -> stringResource(R.string.chief_translation_caption_show_original)
        NativeChiefTranslationCaption.VIEW_TRANSLATION -> stringResource(R.string.chief_translation_caption_view_translation)
        NativeChiefTranslationCaption.NOT_DOWNLOADED -> stringResource(R.string.chief_translation_caption_not_downloaded)
        NativeChiefTranslationCaption.UNSUPPORTED -> stringResource(R.string.chief_translation_caption_unsupported, chiefTranslationLanguageName(presentation.source, locale))
    }
    val tappable = presentation.caption in setOf(NativeChiefTranslationCaption.SHOW_ORIGINAL, NativeChiefTranslationCaption.VIEW_TRANSLATION, NativeChiefTranslationCaption.NOT_DOWNLOADED)
    val active by rememberUpdatedState(tappable && enabled)
    val action by rememberUpdatedState(onClick)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val color = if (tappable) { if (dark) Color(0xFF0091FF) else Color(0xFF0088FF) }
        else if (dark) Color(0x99EBEBF5) else Color(0x993C3C43)
    val font = nativeChiefCaptionFont()
    val density = LocalDensity.current
    val style = nativeChiefTextStyle(11).copy(fontSize = with(density) { font.pointSize.toFloat().dp.toSp() })
    // Source y = primary body bottom + 4; its tail has already been reserved by the balloon.
    Layout(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("$tag:Frame"), content = {
        Row(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}
            .then(if (tappable && enabled) Modifier.clickable(role = Role.Button) { if (active) action() } else Modifier),
            // Pinned UIKit uses physical .right/.left paragraph alignment. Its
            // English attachment/text sequence also keeps its order in RTL UI.
            horizontalArrangement = if (outgoing) Arrangement.Absolute.Right else Arrangement.Absolute.Left, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_chief_translate), null, Modifier.size(font.pointSize.toFloat().dp).testTag("$tag:Glyph"), tint = color)
            Spacer(Modifier.width(3.dp))
            Text(label, Modifier.testTag("$tag:Text"), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }) { children, constraints ->
        val child = children.single().measure(constraints.copy(minHeight = 0))
        val y = with(density) { (4.dp - tailOverhang).roundToPx() }
        val h = maxOf(child.height, (NativeChiefBubbleGeometry.ceilToPixel(font.lineHeight, density.density.toDouble()) * density.density).roundToInt())
        layout(constraints.maxWidth, (h + y).coerceAtLeast(0)) { child.place(0, y + (h - child.height) / 2) }
    }
}

@Composable
internal fun NativeChiefTranslationIndicator(state: NativeChiefTranslationState, model: NativeChiefTranslations, modifier: Modifier) {
    val indicator = state.indicator ?: return
    val source = state.automatic?.source ?: return
    val locale = LocalConfiguration.current.locales[0]
    val name = chiefTranslationLanguageName(source, locale)
    val title = when (indicator) {
        NativeChiefTranslationIndicator.TRANSLATING -> stringResource(R.string.chief_translation_indicator_translating, name)
        NativeChiefTranslationIndicator.WAITING_FOR_DOWNLOAD -> stringResource(R.string.chief_translation_indicator_waiting)
        NativeChiefTranslationIndicator.UNSUPPORTED -> stringResource(R.string.chief_translation_caption_unsupported, name)
    }
    var menu by remember(model) { mutableStateOf(false) }
    Box(modifier.widthIn(max = (LocalConfiguration.current.screenWidthDp - 32).dp)) {
        Surface(onClick = { menu = true }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .94f),
            modifier = Modifier.height(32.dp).testTag("ChiefTranslationIndicator")) {
            Row(Modifier.padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_chief_translate), null, Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, style = nativeChiefTextStyle(13).copy(fontSize = with(LocalDensity.current) { 13.dp.toSp() }, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (indicator == NativeChiefTranslationIndicator.WAITING_FOR_DOWNLOAD) DropdownMenuItem(
                text = { Text(stringResource(R.string.chief_translation_indicator_download, name)) }, onClick = {
                    menu = false; try { model.retryAutomatic() } catch (_: IllegalStateException) { }
                }, modifier = Modifier.testTag("ChiefTranslationRetryAutomatic"))
            DropdownMenuItem(text = { Text(stringResource(R.string.chief_translation_indicator_stop)) }, onClick = {
                menu = false; try { model.stopAutomatic() } catch (_: IllegalStateException) { }
            }, modifier = Modifier.testTag("ChiefTranslationStop"))
        }
    }
}
