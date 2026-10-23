package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun NativeSignInRecoveryView(form: NativeSignInState, session: NativeSignInSession) {
    Column(Modifier.fillMaxWidth().testTag("signin.emailVerification"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.cmux_signin_recovery_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.cmux_signin_recovery_body, form.email.trim().lowercase(Locale.ROOT)),
            textAlign = TextAlign.Center, color = NativeAppColors.muted)
        val error = when (form.recoveryError) {
            NativeSignInRecoveryError.PENDING -> stringResource(R.string.cmux_signin_recovery_pending)
            // The original coordinator maps recovery HTTP429 to AuthError.serverError,
            // whose presentation is the same safe generic message as unavailable.
            NativeSignInRecoveryError.RATE_LIMIT, NativeSignInRecoveryError.UNAVAILABLE -> stringResource(R.string.cmux_signin_recovery_unavailable)
            null -> form.error?.let { stringResource(it.resource) }
        }
        error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("signin.recovery.error"),
            textAlign = TextAlign.Center, color = NativeAppColors.onErrorContainer) }
        if (form.busy) CircularProgressIndicator(Modifier.size(24.dp).testTag("signin.recovery.busy"))
        Button(session::requestCode, enabled = !form.busy,
            modifier = Modifier.fillMaxWidth().testTag("signin.emailVerificationContinue")) {
            Text(stringResource(R.string.cmux_signin_recovery_continue), textAlign = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(session::resendVerification, enabled = !form.busy,
                modifier = Modifier.weight(1f).testTag("signin.emailVerificationResend")) {
                Text(stringResource(R.string.cmux_signin_recovery_resend), textAlign = TextAlign.Center)
            }
            TextButton(session::recoverBilling, enabled = !form.busy,
                modifier = Modifier.weight(1f).testTag("signin.billingRecovery")) {
                Text(stringResource(R.string.cmux_signin_recovery_billing), textAlign = TextAlign.Center)
            }
        }
        if (form.billingRecoveryAccepted) Text(stringResource(R.string.cmux_signin_recovery_accepted),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("signin.billingRecoveryMessage"),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = NativeAppColors.muted)
        TextButton(session::useDifferentEmail, enabled = !form.busy, modifier = Modifier.testTag("signin.useDifferentEmail")) {
            Text(stringResource(R.string.cmux_signin_recovery_different))
        }
    }
}
