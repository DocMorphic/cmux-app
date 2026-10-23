# Native sign-in recovery

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
