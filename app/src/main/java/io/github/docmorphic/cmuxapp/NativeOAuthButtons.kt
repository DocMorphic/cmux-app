package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original order, label/artwork spacing and stable loading labels; capsule paint is Android. */
@Composable internal fun NativeOAuthButtons(form: NativeSignInState, session: NativeSignInSession) {
    val ink = signInInk()
    val fill = if (NativeAppColors.dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .88f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (provider in NativeOAuthProvider.entries) {
            val label = stringResource(when (provider) {
                NativeOAuthProvider.APPLE -> R.string.cmux_signin_provider_apple
                NativeOAuthProvider.GOOGLE -> R.string.cmux_signin_provider_google
                NativeOAuthProvider.GITHUB -> R.string.cmux_signin_provider_github
            })
            val artwork = when (provider) {
                NativeOAuthProvider.APPLE -> R.drawable.cmux_signin_apple
                NativeOAuthProvider.GOOGLE -> R.drawable.cmux_signin_google
                NativeOAuthProvider.GITHUB -> R.drawable.cmux_signin_github
            }
            OutlinedButton({ session.signInProvider(provider) }, enabled = !form.busy,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = fill, contentColor = ink,
                    disabledContainerColor = fill, disabledContentColor = ink),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .18f)),
                shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("signin.${provider.wire}")) {
                Box(contentAlignment = Alignment.Center) {
                    Row(Modifier.alpha(if (form.provider == provider) 0f else 1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(artwork), contentDescription = null,
                            colorFilter = if (provider == NativeOAuthProvider.GOOGLE) null else ColorFilter.tint(ink),
                            modifier = Modifier.height(16.dp).width(if (provider == NativeOAuthProvider.APPLE) 13.024.dp else 16.dp))
                        Text(label, fontSize = 17.sp, lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                    if (form.provider == provider) CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable internal fun NativeSignInEmailDivider() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val separator = if (NativeAppColors.dark) Color(0x99545458) else Color(0x493C3C43)
        HorizontalDivider(Modifier.weight(1f), color = separator.copy(alpha = separator.alpha * .4f))
        BasicText(stringResource(R.string.cmux_signin_provider_divider),
            style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp,
                color = signInInk().copy(alpha = .45f), textAlign = TextAlign.Center),
            autoSize = TextAutoSize.StepBased(minFontSize = 6.6.sp, maxFontSize = 11.sp, stepSize = .1.sp),
            maxLines = 1,
            modifier = Modifier.testTag("signin.email.divider"))
        HorizontalDivider(Modifier.weight(1f), color = separator.copy(alpha = separator.alpha * .4f))
    }
}
