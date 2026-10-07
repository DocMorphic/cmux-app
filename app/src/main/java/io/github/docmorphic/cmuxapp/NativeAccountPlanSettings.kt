/* Settings/plan presentation follows cmux MobileSettingsPlanSection and
 * MobilePlansView at 186cec79781256867ad4516f0802118738bd2393.
 * GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    var awaitingReturn by rememberSaveable { mutableStateOf(false) }
    var linkFailure by remember { mutableStateOf<String?>(null) }
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
        linkFailure = null; awaitingReturn = true
        try { openExternal(url) }
        catch (_: Exception) { awaitingReturn = false; linkFailure = "Couldn’t open the browser. Try again." }
    }
    TextButton(onClick = { expanded = true; linkFailure = null; controller.refresh() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).testTag("settings.plan")) {
        Text("Plan", Modifier.weight(1f))
        Text(state.plan?.name ?: if (state.loading) "Loading…" else "View plan")
        Text(" ›", Modifier.padding(start = 8.dp))
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Plans", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { controller.refresh() }, enabled = !state.loading) { Text("Refresh") }
                    TextButton(onClick = { expanded = false }) { Text("Done") }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    state.plan?.let { plan ->
                        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (state.failure == null) "Current plan" else "Last loaded plan", Modifier.weight(1f))
                                Text(plan.name, modifier = Modifier.testTag("plan.current"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        plan.sourceLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (plan.teamBilled) Text("Your team admin manages billing for this account.")
                        plan.manageUrl?.let { url ->
                            Button(onClick = { open(url) }, modifier = Modifier.testTag("plan.manage")) { Text("Manage Subscription") }
                        }
                        if (plan.canViewPlans) OutlinedButton(onClick = { open(AccountPlan.PRICING) }, modifier = Modifier.testTag("plan.offers")) {
                            Text("View plans on cmux.com")
                        }
                        if (!plan.billingAvailable && plan.source != "apple") Text("Plans are currently unavailable for purchase.")
                        if (plan.source == "apple") Text("Manage this subscription with the Apple Account used for the purchase.")
                    }
                    state.failure?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { controller.refresh() }, enabled = !state.loading) { Text("Try Again") }
                    }
                    linkFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    HorizontalDivider()
                    TextButton(onClick = { open(NativeSupportLink.TERMS.address) }) { Text("Terms of Use") }
                    TextButton(onClick = { open(NativeSupportLink.PRIVACY.address) }) { Text("Privacy Policy") }
                }
            }
        }
    }
}
