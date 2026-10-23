package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Source-shaped challenge card. Capsule paint is Android, not UIKit Liquid Glass. */
@Composable
internal fun NativeSignInChallengeScreen(form: NativeSignInState, session: NativeSignInSession,
    codeFocus: FocusRequester, codeFocused: (Boolean) -> Unit, returnToEmail: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag("signin.challenge.screen")) {
        // The original sign-in and Cloud header share grid/seed/tick/paint rules.
        NativeCloudOnboardingBackdrop(cloudOnboardingReducedMotion(), Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .pointerInput(keyboard, focus) { detectTapGestures { keyboard?.hide(); focus.clearFocus() } },
            verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 430.dp).fillMaxWidth().padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 20.dp).alpha(if (form.verificationRequired && form.busy) .6f else 1f)
                .testTag("signin.challenge.card"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                NativeSignInBrand()
                if (form.verificationRequired) NativeSignInRecoveryView(form, session) else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cmux_signin_code_title), fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            color = signInInk(), modifier = Modifier.semantics { heading() }.testTag("signin.code.title"))
                        Text(stringResource(R.string.cmux_signin_code_sent_to, form.email), fontSize = 15.sp,
                            color = signInMuted(), maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.testTag("signin.code.sentTo"))
                    }
                    val fixedSize = with(LocalDensity.current) { 32.dp.toSp() }
                    val placeholder = stringResource(R.string.cmux_signin_code_placeholder)
                    BasicTextField(form.code, session::editCode,
                        Modifier.fillMaxWidth().height(60.dp)
                            .background(if (NativeAppColors.dark) Color.White.copy(alpha = .10f) else Color.Black.copy(alpha = .05f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = .18f), CircleShape)
                            .padding(horizontal = 16.dp).focusRequester(codeFocus).onFocusChanged { codeFocused(it.isFocused) }
                            .semantics { contentType = ContentType.SmsOtpCode; contentDescription = placeholder }
                            .testTag("signin.code"),
                        enabled = !form.busy || form.authenticating, singleLine = true,
                        textStyle = TextStyle(color = signInInk(), fontSize = fixedSize,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(signInAccent()),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                            keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        decorationBox = { field -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (form.code.isEmpty()) Text(placeholder, color = signInMuted(), fontSize = fixedSize,
                                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clearAndSetSemantics {})
                            field()
                        } })
                    form.error?.let { NativeSignInErrorText(stringResource(it.resource)) }
                    Button(session::submitCode, enabled = !form.busy && form.code.length == 6,
                        colors = ButtonDefaults.buttonColors(containerColor = signInAccent(), contentColor = Color.White),
                        shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("signin.submit")) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.cmux_signin_code_verify), fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp, color = if (form.authenticating) Color.Transparent else Color.Unspecified)
                            if (form.authenticating) CircularProgressIndicator(Modifier.size(20.dp), color = signInInk(), strokeWidth = 2.dp)
                        }
                    }
                    TextButton(returnToEmail, colors = ButtonDefaults.textButtonColors(contentColor = signInMuted()),
                        modifier = Modifier.testTag("signin.code.useDifferentEmail")) {
                        Text(stringResource(R.string.cmux_signin_recovery_different), fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable private fun NativeSignInBrand() {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp).testTag("signin.brand"),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.cmux_signin_mark), contentDescription = null, modifier = Modifier.size(28.dp))
        Text(stringResource(R.string.cmux_signin_code_brand), fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-.33).sp, color = signInInk())
    }
}

@Composable internal fun NativeSignInErrorText(message: String, tag: String = "signin.error") {
    SelectionContainer {
        Text(message, Modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = 12.sp, color = if (NativeAppColors.dark) Color(0xFFFF453A) else Color(0xFFFF3B30))
    }
}

@Composable private fun signInInk() = if (NativeAppColors.dark) Color.White else Color.Black
@Composable private fun signInMuted() = if (NativeAppColors.dark) Color(0x99EBEBF5) else Color(0x993C3C43)
@Composable private fun signInAccent() = if (NativeAppColors.dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
