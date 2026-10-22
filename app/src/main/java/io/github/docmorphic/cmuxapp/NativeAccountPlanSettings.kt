/* Settings/plan presentation follows cmux MobileSettingsPlanSection and
 * MobilePlansView; reviewed labels at a5911a1f92f3eeb11c4d7f3e2ef89204718c5009.
 * GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import android.content.res.Resources
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun NativeAccountPlanSettings(account: NativeAccount, teams: NativeAccountTeams, owner: NativeTeamScope?) {
    if (owner == null || !teams.isCurrent(owner)) return
    key(owner) {
        val scope = rememberCoroutineScope()
        val api = remember(account, teams, owner) { nativeCloudApi(account, teams, owner) }
        val controller = remember(api) { AccountPlanController(scope, api::accountPlan) { teams.isCurrent(owner) } }
        DisposableEffect(controller) { onDispose { controller.close(); api.close() } }
        val context = LocalContext.current
        AccountPlanSettings(controller) { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}

@Composable
internal fun AccountPlanSettings(controller: AccountPlanController, openExternal: (String) -> Unit) {
    val state by controller.state.collectAsState()
    // A separate dialog window must resolve the same current locale as its
    // Settings row, including configuration overrides and an already-open error.
    val context = LocalContext.current
    val configuration = Configuration(LocalConfiguration.current)
    val resources = remember(context, configuration) { context.createConfigurationContext(configuration).resources }
    val inheritedDensity = LocalDensity.current
    val inheritedDirection = LocalLayoutDirection.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var awaitingReturn by rememberSaveable { mutableStateOf(false) }
    var linkFailed by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onReturn by rememberUpdatedState {
        if (awaitingReturn) { awaitingReturn = false; controller.refresh() }
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) onReturn() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controller) { controller.refresh() }
    fun open(url: String) {
        linkFailed = false; awaitingReturn = true
        try { openExternal(url) }
        catch (_: Exception) { awaitingReturn = false; linkFailed = true }
    }
    TextButton(onClick = { expanded = true; linkFailed = false; controller.refresh() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).testTag("settings.plan")) {
        PlanLabelAndValue(resources.getString(R.string.cmux_billing_settingsrow),
            state.plan?.let { localizedPlanName(it, resources) } ?: resources.getString(if (state.loading)
                R.string.cmux_billing_loading else R.string.cmux_billing_android_view_plan),
            "settings.plan.title", "settings.plan.summary", Modifier.weight(1f))
        Text(" ›", Modifier.padding(start = 8.dp))
    }
    if (expanded) {
        val scroll = rememberScrollState()
        // Compose 1.9.x creates a new window on density changes but launches
        // show() only once per call. Restart that call, keeping its owner and
        // scroll state above the replacement window.
        key(inheritedDensity) {
            NativeDialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                CompositionLocalProvider(LocalDensity provides inheritedDensity, LocalLayoutDirection provides inheritedDirection) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(resources.getString(R.string.cmux_billing_title), Modifier.weight(1f).testTag("plan.title").semantics { heading() }, fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = { controller.refresh() }, enabled = !state.loading, modifier = Modifier.size(48.dp).testTag("plan.refresh")) {
                                    Icon(Icons.Default.Refresh, resources.getString(R.string.cmux_billing_android_refresh))
                                }
                                IconButton(onClick = { expanded = false }, modifier = Modifier.size(48.dp).testTag("plan.done")) {
                                    Icon(Icons.Default.Check, resources.getString(R.string.cmux_billing_done))
                                }
                            }
                            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                state.plan?.let { plan ->
                                    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
                                        PlanLabelAndValue(resources.getString(if (state.failure == null) R.string.cmux_billing_currentplan else R.string.cmux_billing_android_last_loaded),
                                            localizedPlanName(plan, resources), "plan.currentLabel", "plan.current",
                                            Modifier.fillMaxWidth().padding(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    val sourceLabel = when (plan.source) {
                                        "apple" -> R.string.cmux_billing_source_apple
                                        "stripe" -> R.string.cmux_billing_source_web
                                        else -> null
                                    }
                                    sourceLabel?.let { Text(resources.getString(it), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    if (plan.teamBilled) Text(resources.getString(R.string.cmux_billing_ineligible_team), Modifier.fillMaxWidth())
                                    plan.manageUrl?.let { url ->
                                        Button(onClick = { open(url) }, modifier = Modifier.testTag("plan.manage")) { Text(resources.getString(R.string.cmux_billing_manage)) }
                                    }
                                    if (plan.canViewPlans) OutlinedButton(onClick = { open(AccountPlan.PRICING) }, modifier = Modifier.testTag("plan.offers")) {
                                        Text(resources.getString(R.string.cmux_billing_android_web_offers))
                                    }
                                    if (!plan.billingAvailable && plan.source != "apple") Text(resources.getString(R.string.cmux_billing_ineligible_unavailable), Modifier.fillMaxWidth())
                                    if (plan.source == "apple") Text(resources.getString(R.string.cmux_billing_android_apple_manage_help), Modifier.fillMaxWidth())
                                }
                                state.failure?.let {
                                    Text(resources.getString(if (it == AccountPlanFailure.SIGNED_OUT) R.string.cmux_billing_error_signedout
                                        else R.string.cmux_billing_android_load_failed), Modifier.fillMaxWidth().testTag("plan.failure"), color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { controller.refresh() }, enabled = !state.loading, modifier = Modifier.testTag("plan.retry")) { Text(resources.getString(R.string.cmux_billing_retry)) }
                                }
                                if (linkFailed) Text(resources.getString(R.string.cmux_billing_android_browser_failed), Modifier.fillMaxWidth().testTag("plan.browserFailure"), color = MaterialTheme.colorScheme.error)
                                HorizontalDivider()
                                TextButton(onClick = { open(NativeSupportLink.TERMS.address) }) { Text(resources.getString(R.string.cmux_billing_terms)) }
                                TextButton(onClick = { open(NativeSupportLink.PRIVACY.address) }) { Text(resources.getString(R.string.cmux_billing_privacy)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keep complete words readable when a translated value needs most of the row. */
@Composable
private fun PlanLabelAndValue(label: String, value: String, labelTag: String, valueTag: String,
    modifier: Modifier = Modifier, valueColor: Color = LocalContentColor.current) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val density = LocalDensity.current
    val labelWidth = measurer.measure(label, style = style, softWrap = false, maxLines = 1).size.width
    val valueWidth = measurer.measure(value, style = style, softWrap = false, maxLines = 1).size.width
    BoxWithConstraints(modifier) {
        val fits = labelWidth + valueWidth + with(density) { 12.dp.roundToPx() } <= with(density) { maxWidth.roundToPx() }
        if (fits) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f).testTag(labelTag))
            Spacer(Modifier.width(12.dp))
            Text(value, Modifier.width(with(density) { valueWidth.toDp() }).testTag(valueTag), color = valueColor)
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.fillMaxWidth().testTag(labelTag))
            Text(value, Modifier.fillMaxWidth().testTag(valueTag), color = valueColor)
        }
    }
}

private fun localizedPlanName(plan: AccountPlan, resources: Resources): String = when (plan.id) {
    "free" -> resources.getString(R.string.cmux_billing_plan_free)
    "go" -> resources.getString(R.string.cmux_billing_plan_go)
    "pro" -> resources.getString(R.string.cmux_billing_plan_pro)
    "max" -> resources.getString(R.string.cmux_billing_plan_max)
    else -> plan.name
}
