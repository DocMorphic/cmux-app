package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/** Measure both native tiers before choosing. Probe content is never placed or accessible. */
@Composable
internal fun NativeWhatsNewPageBody(page: WhatsNewPage, policy: NativeMacCompatibilityPolicy,
    modifier: Modifier = Modifier, fitting: Boolean = true, onNaturalHeight: (Int) -> Unit = {}) {
    val scroll = rememberScrollState()
    val report by rememberUpdatedState(onNaturalHeight)
    // iOS launch sheets use compact natural content inside a scroll viewport;
    // standalone native detail pages can choose the regular/compact fitting tiers.
    if (!fitting) {
        Column(modifier.fillMaxWidth().verticalScroll(scroll)
            .wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { report(it.height) }) {
            NativeWhatsNewContent(page, policy, true)
        }
        return
    }
    SubcomposeLayout(modifier.fillMaxWidth().clipToBounds()) { constraints ->
        val natural = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val budget = constraints.maxHeight
        val regular = subcompose("regular-measure") {
            NativeWhatsNewContent(page, policy, false, Modifier.clearAndSetSemantics {})
        }.single().measure(natural)
        val compactHeight = if (regular.height > budget) subcompose("compact-measure") {
            NativeWhatsNewContent(page, policy, true, Modifier.clearAndSetSemantics {})
        }.single().measure(natural).height else regular.height
        val compact = regular.height > budget
        val scrolling = compactHeight > budget
        val tier = if (scrolling) "scroll" else if (compact) "compact" else "regular"
        val visible = subcompose("visible") {
            val viewport = if (scrolling) Modifier.verticalScroll(scroll) else Modifier
            Box(viewport.testTag("whatsnew.layout.$tier")) {
                NativeWhatsNewContent(page, policy, compact, Modifier.onSizeChanged { report(it.height) })
            }
        }.single().measure(if (scrolling) constraints.copy(minHeight = 0) else natural)
        layout(constraints.constrainWidth(visible.width), constraints.constrainHeight(visible.height)) {
            visible.placeRelative(0, 0)
        }
    }
}

@Composable
private fun NativeWhatsNewContent(page: WhatsNewPage, policy: NativeMacCompatibilityPolicy,
    compact: Boolean, modifier: Modifier = Modifier) {
    val detail = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val featureTitle = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
    Column(modifier.fillMaxWidth().padding(top = if (compact) 32.dp else 40.dp,
        bottom = if (compact) 12.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 36.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page.kind == WhatsNewKind.ANNOUNCEMENT) Text("ANNOUNCEMENT",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("whatsnew.announcement"))
            Text(page.title, style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }.testTag("whatsnew.title.${page.key}"))
            if (page.body == WhatsNewBody.Pairing) Text(
                "On your Mac, open cmux Settings > Mobile and turn on Enable iOS pairing. This setting also enables the Android companion.",
                style = detail, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        when (val body = page.body) {
            is WhatsNewBody.Features -> Column(Modifier.padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 13.dp else 28.dp)) {
                // Remote rows have positional identity; duplicate titles must remain separate.
                body.rows.forEach { feature ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.width(if (compact) 30.dp else 40.dp), contentAlignment = Alignment.TopCenter) {
                            Icon(painterResource(whatsNewFeatureIcon(feature.symbol)), contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (compact) 22.dp else 26.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(feature.title, style = featureTitle, fontWeight = FontWeight.SemiBold)
                            Text(feature.detail, style = detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            WhatsNewBody.Pairing -> {
                Image(painterResource(if (MaterialTheme.colorScheme.surface.luminance() < .5f)
                    R.drawable.mac_pairing_settings_dark else R.drawable.mac_pairing_settings_light),
                    "cmux Mac Settings showing Enable iOS pairing", Modifier.padding(horizontal = 24.dp)
                        .fillMaxWidth().aspectRatio(1030f / 285f).clip(MaterialTheme.shapes.medium)
                        .testTag("whatsnew.pairing.image"))
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("On this Android phone", style = featureTitle, fontWeight = FontWeight.SemiBold)
                    Text("Use the same cmux account and team on your Mac and this phone. Your Mac appears in Computers after mobile pairing is enabled.",
                        style = detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(Modifier.padding(horizontal = 24.dp).fillMaxWidth().testTag("whatsnew.compatibility"),
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Mac version required", style = featureTitle, fontWeight = FontWeight.SemiBold)
                        val requirement = policy.requirement()
                        // Use Android's actual admission profile, never its marketing version as an iOS version.
                        WhatsNewCompatibilityRow("Stable Mac", requirement?.stable?.let { "cmux $it or later" }
                            ?: "No stable minimum listed", compact)
                        WhatsNewCompatibilityRow("Nightly Mac", requirement?.nightly?.let { "cmux NIGHTLY $it or later" }
                            ?: "No separate minimum", compact)
                    }
                }
            }
            is WhatsNewBody.Web -> Unit // Web pages use the retained renderer outside this native layout.
        }
    }
}

@Composable
private fun WhatsNewCompatibilityRow(label: String, value: String, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace)
    }
}

/** Android vector equivalents for feed SF Symbol names. Unknown names get a neutral update icon. */
private fun whatsNewFeatureIcon(symbol: String?): Int = when (symbol?.removeSuffix(".fill")) {
    "book.closed", "book", "bookmark" -> R.drawable.ic_workspace_bookmark
    "desktopcomputer", "display", "laptopcomputer", "macbook" -> R.drawable.ic_computer_desktop
    "rectangle.stack", "square.stack", "sidebar.left" -> R.drawable.ic_primary_workspaces
    "terminal", "apple.terminal" -> R.drawable.ic_computer_terminal
    "bell", "bell.badge" -> R.drawable.ic_notification
    "network", "wifi", "antenna.radiowaves.left.and.right" -> R.drawable.ic_workspace_network
    "folder" -> R.drawable.ic_workspace_folder
    "doc", "doc.text" -> R.drawable.ic_workspace_file_text
    "globe" -> R.drawable.ic_workspace_globe
    "person.2", "person.3" -> R.drawable.ic_workspace_users
    "lock", "lock.shield" -> R.drawable.ic_workspace_lock
    "gear", "gearshape" -> R.drawable.ic_workspace_settings
    "magnifyingglass" -> R.drawable.ic_primary_search
    "bolt" -> R.drawable.ic_computer_bolt
    "checkmark", "checkmark.circle" -> R.drawable.ic_menu_check
    else -> R.drawable.ic_computer_star
}
