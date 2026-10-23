# Native sign-in recovery

## Original sign-in error roles and copy applied — 2026-10-24

The form now retains a display role rather than arbitrary provider/exception
text. The original SignInErrorPresentation/AuthError display-safe mapping covers
29 specific provider codes (case-normalized), unverified-email distinction,
unauthorized, network, unknown, local-validation and phase-timeout roles.
Cancellation stays silent and preserves an entered code; failed authentication
still clears it. Ordinary and verification-recovery views resolve their role
through resources, with a polite live-region semantic on ordinary errors.
Missing nonce/local invalid code use the original short invalid-code role.
This changes error presentation, not account/pairing storage or recovery requests.

Twenty original main-catalog keys provide180 exact translations across nine
locales. The additional OFFLINE role keeps the exact English AuthError.swift
default: the pinned iOS main catalog lacks that key. Its source-default origin
is explicit, rather than attributing Mac translations to iOS. Role/key/resource,
source hashes, locale completeness and this fallback are guarded;20 localization
checks pass. The data is sign-in-error-strings.json and the common generator
writes/checks cmux_signin_error_strings.xml in all nine folders.

All27 focused JVM tests pass (the previous21 plus six mapping/privacy/domain/
cancellation tests). Final owned API36 cohort passes four cases in16.234s.
One retained error remains complete through18 controlled locale/font profiles,
with one request total, no provider marker and no text overflow. Arabic/German
large-text frames with the actual keyboard visible were reviewed. A known invalid
OTP displays the original localized message in the existing recovery flow; its
four Activity recreations and existing pending-code/authentication checks retain.
Crash empty, preflight no ANR, settings/window restored, emulator exit0.

Exact source/data/APK/semantic/runtime bindings:
recovery-20261023/native-signin-error-checkpoint.json and-runtime-v1/;
original source/value comparison: native-signin-error-source-comparison.json.
App SHA256:d50678f1253de5fcf59914d6c5ca9dc7c34e325bd66c77bd9d320720a7b8585e.
Test SHA256:e44c98f453366ff232919d0f38633c89f70b87c87412ba341e9a702134b1aa83.
These are controlled composition locales/scales and fixture/local-HTTP callbacks;
not actual OS-language/font transitions, real account/backend, Pixel/Mac,
TalkBack or full original sign-in UI/method/phase-orchestration acceptance.
Automatic-backoff orchestration and its separate retrying state remain open.
Real connectivity preflight/deadline behavior is not proven by mapped roles.

Next source behavior: original CmuxMobileWorkspace/SignInCodeInputPolicy keeps
ASCII letters/digits, uppercases and caps at six; SignInView automatically verifies
complete normalized input. Android currently preserves case and waits for its
manual submit button. Port those event/focus/busy semantics and original entry
states alongside the remaining scaffold/method/keyboard/localization gates.
No new signed build or real-device action;722/physical712/global pins and stored
account/pairing/signing unchanged. Full goal remains active.

## Unverified-email recovery — 2026-10-24

Android now preserves the typed refusal needed by the original native iOS
SignInEmailCodeFailurePolicy. When requesting a code returns
`USER_EMAIL_ALREADY_EXISTS` with a true boolean/numeric
`would_work_if_email_was_verified` detail, the form requests verification and
enters the verification screen. A provider failure leaves its controls reachable.

The screen offers the original resend, “I verified my email,” paid-account
recovery and different-email actions. Continuing requests a code again; another
unverified refusal shows the original pending message without automatically
sending another verification email. Successful code preparation returns to code
entry. Only an actual successful OTP exchange publishes credentials/completes
sign-in. A failed exchange clears its submitted code as in the original view.

Both recovery requests use the existing cmux HTTPS backend, POST only normalized
email, and carry no account token. Verification uses `/api/auth/email-verification`;
paid recovery uses `/api/billing/recover`. Any2xx is accepted without inferring
whether an address exists or has paid. The neutral acceptance message is the
original source copy. Requests have60-second bounds, close responses, cancel
with their coroutine, do not follow redirects and do not automatically retry.
Recovery429 stays typed internally but presents the original coordinator's safe
generic server-error copy. OTP responses preserve current/legacy known-error and
actual-status headers, including known errors transported with HTTP200 and HTML429.

One retained owner serializes the flow and fences retired/cancelled callbacks.
Different-email returns to entry with its address retained and challenge cleared;
closing purges the memory-only form. Neither recovery operation changes stored
account/pairing/signing data. Both retained production wiring and the fallback
composition owner receive the real recovery callbacks/challenge invalidator.

Source audit: eleven whole original view/policy/coordinator/client/config/SDK
files at `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`, each identical at detected
79d982f9, are bound in recovery-20261023/signin-verification-source-audit/review.json.
The two original catalogs contribute ten keys across nine locales: all90 values
match the original files byte-for-value. The one email argument maps `%@` to
`%1$s`. Data, catalog hashes, exact key/resource/catalog association and generated
resources are checked by mobile-ui-localization.py;16 localization checks pass.

All21 focused JVM tests pass (4 retained form,9 challenge/nonce,8 typed/provider/
recovery/cancellation). They cover actual local HTTP requests, normalized payloads,
privacy-neutral2xx, redirects/429/503, refusal typing, verified retry and late
uncooperative callbacks. Final owned API36 cohort passes three cases in9.581s:
actual recovery input with local HTTP and OTP fixture exchange; existing pending
code/authentication recreation; new verification/billing recovery recreation.
Four actual Activity recreations repeat neither request; its916-byte saved state
omits the fixture email. Existing saved state omits the code. Return-to-email,
failed-code clearing, one publication/completion and privacy-neutral recovery are
verified. Final dark Surface frames reviewed, crash empty, preflight no ANR,
settings/window restored and emulator exit0.

Exact source/APK/test/provider bindings and screenshots:
`captures/recovery-20261023/native-signin-recovery-checkpoint.json` and `-runtime-v2/`.
App SHA256:777508de0ddd8cb57b3fe7899989a4e05241954e40222a7f873645757897991b.
Test SHA256:01ebc96085586925bab4353c76c140655ae56db8da099e05a9a27856bf819d80.
The earlier two-case runtime-v1 passed functional checks on the same production
APK, but its isolated host omitted the themed Surface/insets; its screenshots
are superseded. The final host matches the actual root Surface/content placement
and checks compositor background. No production change was needed for that repair.

## Remaining acceptance

Real backend delivery/verification, real-account restore and the signed Pixel
upgrade remain open. No real email/account request or physical phone/Mac action
was performed in this fixture cohort. Exact original full sign-in scaffold,
materials/motion/provider methods, complete error presentation/localization,
large text/RTL/OS-language/keyboard/older-API variants, process death and TalkBack
remain open alongside all full-app gates. Existing signed candidate722 and
physical712 remain unchanged; this work is source/debug evidence, not a new
signed delivery. Native Google Play purchase/restore is a separate backend gate;
this existing paid-account recovery endpoint does not implement Play billing.
