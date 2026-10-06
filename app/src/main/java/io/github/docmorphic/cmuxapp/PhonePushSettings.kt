package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

@Composable
internal fun PhonePushSettings(team: NativeTeamScope?, permits: (NativeTeamScope) -> Boolean,
    onEnableBackground: () -> Unit, onConnect: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember(context) { NativeCredentialStore(context) }
    val scope = rememberCoroutineScope()
    val currentPermits by rememberUpdatedState(permits)
    var model by remember(team) { mutableStateOf<PhonePushSetupState?>(null) }
    var message by remember(team) { mutableStateOf<String?>(null) }
    var busy by remember(team) { mutableStateOf(false) }
    var consent by remember(team) { mutableStateOf(false) }
    var selected by remember(team) { mutableStateOf<PhonePushSetupMac?>(null) }
    var review by remember(team) { mutableStateOf<PhoneHelperOfferReview?>(null) }
    var raw by remember(team) { mutableStateOf("") } // Never rememberSaveable: this contains the offer secret.
    var scanning by remember(team) { mutableStateOf(false) }
    var scanGeneration by remember(team) { mutableIntStateOf(0) }
    LaunchedEffect(team, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(store.revisions, PhoneFcmTokens.revisions(context), flow {
                while (true) { emit(System.currentTimeMillis()); delay(5000) }
            }) { _, _, time -> time }.collect { time ->
                try {
                    val next = withContext(Dispatchers.IO) {
                        synchronized(store.accountStateLock) {
                            phonePushSetupState(store.load() ?: org.json.JSONObject(), team?.takeIf(currentPermits),
                                PhoneFcmTokens.setup(context), store.visiblePairedMacs(), time)
                        }
                    }
                    model = next
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive(); model = null
                    message = "Couldn’t read push settings. Reopen Settings and try again."
                }
            }
        }
    }
    fun act(failureMessage: String, action: suspend (NativeTeamScope) -> Unit) {
        val owner = team ?: return
        if (busy || !currentPermits(owner)) return
        busy = true; message = null
        scope.launch {
            try { action(owner) }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); if (currentPermits(owner)) message = failureMessage }
            finally { busy = false }
        }
    }
    fun closeOffer() { selected = null; review = null; raw = ""; scanGeneration++; scanning = false }
    fun inspectOffer(value: String) {
        val target = selected ?: return
        act("This offer is invalid, expired, or belongs to a different Mac or push service. Request a new offer on your Mac.") { owner ->
            val inspected = withContext(Dispatchers.IO) {
                synchronized(store.accountStateLock) {
                    check(currentPermits(owner))
                    PhoneHelperOfferReview.create(value.trim(), checkNotNull(store.load()), owner, target.origin,
                        checkNotNull(PhoneFcmTokens.snapshot(context)), System.currentTimeMillis())
                }
            }
            if (currentPermits(owner) && selected?.origin == target.origin) { review = inspected; raw = "" }
        }
    }
    PhonePushSettingsContent(model, busy, message, onToggle = { enabled ->
        if (enabled) consent = true else act("Couldn’t turn off push alerts. Try again.") { owner ->
            withContext(Dispatchers.IO) { synchronized(store.accountStateLock) {
                check(currentPermits(owner)); PhoneFcmTokens.revoke(context)
            } }
            closeOffer()
        }
    }, onEnableBackground = onEnableBackground, onConnect = onConnect, onRetry = {
        act("Couldn’t retry push registration. Try again.") { PhoneFcmTokens.recover(context).join() }
    }, onPair = { selected = it; review = null; raw = ""; message = null }, onCancel = { mac ->
        act("Couldn’t cancel pairing. Try again.") { owner ->
            PhoneHelperEnrollments.cancel(context, owner, checkNotNull(mac.attempt)) { currentPermits(owner) }
        }
    })
    if (consent) AlertDialog(onDismissRequest = { if (!busy) consent = false },
        title = { Text("Allow push alerts on this phone?") },
        text = { Text("Your Mac’s notification helper will send encrypted alerts through Google’s push service. Your phone registers with that service. You can turn push alerts off here at any time.") },
        confirmButton = { TextButton(enabled = !busy && model?.canEnable == true, modifier = Modifier.testTag("push.consent"), onClick = {
            act("Couldn’t enable push alerts. Check notification permissions and try again.") { owner ->
                withContext(Dispatchers.IO) { PhoneFcmTokens.authorize(context, owner.login) { currentPermits(owner) } }
                consent = false
            }
        }) { Text("Allow Push Alerts") } }, dismissButton = { TextButton(enabled = !busy, onClick = { consent = false }) { Text("Cancel") } })
    selected?.let { mac ->
        val inspected = review
        var time by remember(inspected) { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(inspected) { if (inspected != null) while (true) { time = System.currentTimeMillis(); delay(1000) } }
        AlertDialog(onDismissRequest = { if (!busy) closeOffer() },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(if (inspected == null) "Pair notification helper" else "Confirm notification helper") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(mac.name)
                    if (inspected == null) {
                        Text("Scan or paste the short-lived pairing offer shown by the notification helper on this Mac.")
                        OutlinedTextField(raw, onValueChange = { if (it.toByteArray().size <= 8192) raw = it },
                            modifier = Modifier.fillMaxWidth().testTag("push.offer"), enabled = !busy && !scanning,
                            label = { Text("Helper pairing offer") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            visualTransformation = PasswordVisualTransformation(), maxLines = 3)
                        TextButton(enabled = !busy && !scanning, onClick = {
                            val owner = team ?: return@TextButton
                            val generation = ++scanGeneration
                            scanning = true
                            try {
                                GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()).startScan()
                                    .addOnSuccessListener { barcode ->
                                        if (scanGeneration == generation && currentPermits(owner) && selected?.origin == mac.origin) {
                                            scanning = false
                                            val value = barcode.rawValue
                                            if (value != null && value.toByteArray().size <= 8192) inspectOffer(value)
                                            else message = "This QR code does not contain a valid pairing offer."
                                        }
                                    }.addOnFailureListener {
                                        if (scanGeneration == generation) { scanning = false; message = "Couldn’t scan the offer. You can paste it instead." }
                                    }.addOnCanceledListener { if (scanGeneration == generation) scanning = false }
                            } catch (_: Exception) { scanning = false; message = "Couldn’t open the scanner. You can paste the offer instead." }
                        }) { Text(if (scanning) "Scanning…" else "Scan QR Code") }
                    } else {
                        Text("Check that this address and key fingerprint match the notification helper on your Mac.")
                        Text(inspected.endpoint, style = MaterialTheme.typography.bodySmall)
                        Text(inspected.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("push.fingerprint"))
                        Text(if (time >= inspected.expiresAt) "This offer expired. Request a new offer on your Mac."
                            else "Expires in ${((inspected.expiresAt - time + 999) / 1000)} seconds", style = MaterialTheme.typography.bodySmall)
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
            }, confirmButton = {
                TextButton(enabled = !busy && !scanning && model?.canPair == true &&
                    (if (inspected == null) raw.isNotBlank() else time < inspected.expiresAt),
                    modifier = Modifier.testTag(if (inspected == null) "push.review" else "push.confirm"), onClick = {
                        if (inspected == null) inspectOffer(raw) else act("Pairing could not start. Check your account and request a new offer.") { owner ->
                            PhoneHelperEnrollments.prepare(context, inspected) { owner == inspected.team && currentPermits(owner) }
                            closeOffer(); message = "Pairing started. You can leave this screen while it finishes."
                        }
                    }) { Text(if (busy) "Working…" else if (inspected == null) "Review Offer" else "Pair Helper") }
            }, dismissButton = { TextButton(enabled = !busy, onClick = ::closeOffer) { Text("Cancel") } })
    }
}

@Composable
internal fun PhonePushSettingsContent(model: PhonePushSetupState?, busy: Boolean, message: String?,
    onToggle: (Boolean) -> Unit, onEnableBackground: () -> Unit, onConnect: () -> Unit,
    onRetry: () -> Unit, onPair: (PhonePushSetupMac) -> Unit, onCancel: (PhonePushSetupMac) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PUSH ALERTS", style = MaterialTheme.typography.labelSmall)
        Text(model?.stage?.text ?: "Loading push settings…", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("push.status").semantics { liveRegion = LiveRegionMode.Polite })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Allow Push Alerts on This Phone", Modifier.weight(1f))
            Switch(model?.enabled == true, onCheckedChange = onToggle,
                enabled = !busy && model != null && (model.enabled || model.canEnable),
                modifier = Modifier.testTag("push.enabled").semantics { contentDescription = "Allow Push Alerts on This Phone" })
        }
        when (model?.stage) {
            PhonePushSetupStage.BACKGROUND -> TextButton(enabled = !busy, onClick = onEnableBackground) { Text("Enable Background Notifications") }
            PhonePushSetupStage.CONNECT -> TextButton(enabled = !busy, onClick = onConnect) { Text("Connect a Mac") }
            PhonePushSetupStage.TOKEN, PhonePushSetupStage.CLEANUP -> TextButton(enabled = !busy, onClick = onRetry) { Text("Retry Notification Setup") }
            else -> Unit // Android system settings has its existing section immediately below.
        }
        model?.macs?.forEach { mac ->
            HorizontalDivider()
            Text(mac.name, style = MaterialTheme.typography.titleSmall)
            Text(mac.stage.text, style = MaterialTheme.typography.bodySmall)
            if (mac.attempt != null) TextButton(enabled = !busy, onClick = { onCancel(mac) }) { Text("Cancel Pairing") }
            else if (mac.stage == PhonePushSetupStage.CONNECT) TextButton(enabled = !busy, onClick = onConnect) { Text("Connect ${mac.name}") }
            else if (mac.stage != PhonePushSetupStage.UPDATING) TextButton(enabled = !busy && model.canPair, onClick = { onPair(mac) }) {
                Text(if (mac.stage == PhonePushSetupStage.PAIRED || mac.stage == PhonePushSetupStage.RENEW) "Pair Helper Again" else "Pair Notification Helper")
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("push.message").semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
