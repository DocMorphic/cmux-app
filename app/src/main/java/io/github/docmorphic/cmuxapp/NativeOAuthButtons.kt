package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Working provider actions; the remaining full entry scaffold/artwork is tracked separately. */
@Composable internal fun NativeOAuthButtons(form: NativeSignInState, session: NativeSignInSession) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (provider in NativeOAuthProvider.entries) {
            val label = stringResource(when (provider) {
                NativeOAuthProvider.APPLE -> R.string.cmux_signin_provider_apple
                NativeOAuthProvider.GOOGLE -> R.string.cmux_signin_provider_google
                NativeOAuthProvider.GITHUB -> R.string.cmux_signin_provider_github
            })
            OutlinedButton({ session.signInProvider(provider) }, enabled = !form.busy,
                shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("signin.${provider.wire}")) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, Modifier.alpha(if (form.provider == provider) 0f else 1f), fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    if (form.provider == provider) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(Modifier.weight(1f))
            Text(stringResource(R.string.cmux_signin_provider_divider), fontSize = 12.sp, color = NativeAppColors.muted,
                textAlign = TextAlign.Center, modifier = Modifier.weight(3f))
            HorizontalDivider(Modifier.weight(1f))
        }
    }
}
