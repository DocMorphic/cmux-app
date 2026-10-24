package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pinned source card structure; Android scrolling, menu, font and capsule paint are adaptations. */
@Composable internal fun NativeSignInMethodsScreen(form: NativeSignInState, session: NativeSignInSession,
    emailFocus: FocusRequester, emailFocused: (Boolean) -> Unit, requestCode: () -> Unit,
    onSetup: () -> Unit, onMacSetup: () -> Unit, onUseHelper: () -> Unit, onLicenses: () -> Unit,
    restoring: Boolean = false, revalidateSession: () -> Unit = {}) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag("signin.methods.screen")) {
        NativeCloudOnboardingBackdrop(cloudOnboardingReducedMotion(), Modifier.matchParentSize())
        // Like the original keyboardDismissSurface, this sits behind the card;
        // form controls own their taps rather than sharing a parent recognizer.
        Box(Modifier.matchParentSize().pointerInput(keyboard, focus) {
            detectTapGestures { keyboard?.hide(); focus.clearFocus() }
        })
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 430.dp).fillMaxWidth().padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 20.dp).alpha(if (form.busy) .6f else 1f)
                .testTag("signin.methods.card"), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                NativeSignInBrand()
                if (restoring) NativeSignInRestoreStatus(revalidateSession)
                NativeOAuthButtons(form, session)
                NativeSignInEmailDivider()
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val placeholder = stringResource(R.string.cmux_signin_provider_email_placeholder)
                    // Keep IME selection/composition synchronous with edits. The
                    // retained owner still supplies text across modes/recreation;
                    // a delayed flow collection must not echo an old empty value
                    // back into a newly opening native input connection.
                    var input by remember(session) { mutableStateOf(TextFieldValue(form.email)) }
                    var observedEmail by remember(session) { mutableStateOf(form.email) }
                    if (observedEmail != form.email) {
                        observedEmail = form.email
                        if (input.text != form.email) input = TextFieldValue(form.email, TextRange(form.email.length))
                    }
                    BasicTextField(input, { input = it; session.editEmail(it.text) },
                        Modifier.fillMaxWidth().height(50.dp)
                            .background(if (NativeAppColors.dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .88f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = .18f), CircleShape)
                            .padding(horizontal = 16.dp).focusRequester(emailFocus).onFocusChanged { emailFocused(it.isFocused) }
                            .semantics { contentType = ContentType.EmailAddress; contentDescription = placeholder }
                            .testTag("signin.email"), singleLine = true,
                        textStyle = TextStyle(color = signInInk(), fontSize = 17.sp), cursorBrush = SolidColor(signInAccent()),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                            keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                        decorationBox = { field -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (input.text.isEmpty()) Text(placeholder, color = signInMuted(), fontSize = 17.sp,
                                modifier = Modifier.clearAndSetSemantics {})
                            field()
                        } })
                    val accent = signInAccent()
                    Button(requestCode, enabled = !form.busy && form.email.isNotBlank(), shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White,
                            disabledContainerColor = if (NativeAppColors.dark) Color(0xFF2A2A2C) else Color(0xFFECECED),
                            disabledContentColor = if (NativeAppColors.dark) Color(0xFF6B6B6E) else Color(0xFFB7B7B9)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("signin.request")) {
                        Text(stringResource(R.string.cmux_signin_provider_email_code), fontSize = 17.sp, lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                form.error?.let { NativeSignInErrorText(stringResource(it.resource)) }
            }
        }
        // These existing Android routes are intentionally outside the original auth card.
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            var options by rememberSaveable { mutableStateOf(false) }
            IconButton({ options = true }, Modifier.testTag("signin.options")) {
                Icon(painterResource(R.drawable.ic_chief_menu_more), stringResource(R.string.cmux_signin_android_options), tint = signInInk())
            }
            DropdownMenu(options, { options = false }) {
                for ((resource, tag, action) in listOf(
                    Triple(R.string.cmux_signin_android_setup, "signin.setup-help", onSetup),
                    Triple(R.string.cmux_signin_android_mac_setup, "signin.pairing.help", onMacSetup),
                    Triple(R.string.cmux_signin_android_helper, "signin.helper", onUseHelper),
                    Triple(R.string.cmux_signin_android_licenses, "signin.licenses", onLicenses))) {
                    DropdownMenuItem(text = { Text(stringResource(resource)) }, onClick = { options = false; action() },
                        modifier = Modifier.testTag(tag))
                }
            }
        }
    }
}
