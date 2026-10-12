# Settings setup reference — 2026-10-12

## Authorization recovery follow-up — 2026-10-12

Reading the complete scoped iOS auth sink (`MobileShellComposite.swift`, lines
17283–17307) found that the first Android guidance checkpoint recognized too few
host auth errors. The source recognizes six normalized RPC codes and five auth
message fragments inside host-reported RPC failures. Android now matches those;
general local/network exception text still does not diagnose account mismatch.
The iOS automatic-recovery owner also stops redial after auth rejection. Android's
foreground recovery now holds terminal failures until an explicit new connection
request or changed route/login/team. The gate and request generation live in the
Activity ViewModel; directory updates, background return and recreation cannot
restart the rejected generation. Ordinary network backoff now caps at 60s.

Primary feed failures retire their shared wire with the original cause, leaving
other Macs alone. Per-Mac stops survive feed pause/return and cosmetic name changes;
a fresh foreground verification resumes that exact allowed pairing. Explicit
workspace refresh fails promptly while held. Core auth rejection is not swallowed
as an empty notification feed. Cached-byte admission declines host auth messages
with retryable-looking codes. Disconnected Computers offers Sign Out, and stale
Sign Out actions cannot remove a recovered account. Auth-failed Mac switching does
not automatically restore/retry another Mac under the rejected session.

All **87 distinct JVM cases have passing results** across the recorded runs. The
initial suite passed 86/87; its old native-revocation test expected pause/return to
implicitly resume. That expectation now uses independent fresh host verification
and the explicit recovery hook; its cache/admission assertions still pass. Initial
XML/failure logs are retained. Main/test builds passed (final 1m19s).

Both Android cases passed unassisted in **20.132s**: the connected Settings/Details
→ terminal flow, and host auth rejection held across 70s of test-clock advancement,
real Activity recreation, another 70s, then explicit saved-Mac selection and
terminal input. The stale Sign Out action left sign-in intact. No crash/ANR records;
timeout restored and sole existing AVD stopped/reaped. These are fixtures, not
physical Mac/Pixel or complete auth/network acceptance. Signed 650/global pin stay
unchanged. Evidence/hashes: `captures/runtime/auth-recovery-verification.json`.

## Highlighted connection guidance — 2026-10-12

Scoped reference remains `f4b1509054949eaad5d695569ad443c4c18ed68d`:
`MobileSetupGuidancePolicy.swift`, `SetupHelpView.swift`, the disconnected
`CMUXMobileRootView.swift` classifier and `DisconnectedWorkspaceShellView.swift`.
Mismatch has first precedence, then signed out, known paired Mac, then never
paired. A highlighted section moves first and shows **You are here**, while all
other setup steps and network guidance remain. Settings keeps its unhighlighted
reference order. Reading guidance never drives pairing or discovery.

Android now exposes the same guidance from sign-in, disconnected Computers and
onboarding's connection page. Known saved/hidden pairings distinguish setup from
wake/reconnect guidance. The main connection publishes a typed reauthentication
signal for host RPC `unauthorized` and exhausted Iroh control authentication
errors; successful verified connection clears it, and route/login/team changes
reset its remembered scope. Network timeouts, error-message text, local errors,
permission denial and protocol/identity failures are not relabeled as account
mismatches. This is presentation data, never an authorization grant.

A highlighted help sheet restores open state, responds to current guidance and
retains the existing fixed header, density and download handling. Large text puts
the marker below the heading. Onboarding's help remains open when the connection
becomes ready, without repeating its entry, pairing or permission actions.

All **13 focused JVM cases passed** (guidance 4, onboarding state 9), including
all eight durable-input combinations, complete highlighted ordering, typed
rejections and bounded cyclic/deep cause handling. The debug/test build passed in 1m53s. Six Android cases passed unassisted in
**61.689s** on the sole existing API37 / 16 KiB AVD: highlighted order/open-state
restoration and live guidance change; the existing actual font-scale-2 reference
and download recovery; sign-in help without sending a code or losing entered
email; onboarding help restoration without repeating actions and persistence
when the Mac becomes ready; the existing connected Settings reference/terminal
flow; and host-framed `unauthorized` rejection followed by highlighted account
recovery, successful reconnect and actual terminal paste input. This now repeats
the connected reference case after the earlier text-density repair.

Two screenshots were inspected, showing the account gate first with the marker
and the actual scaled reference with fixed Done. The final affected-branch check for the highlighted marker at actual font
scale 2 passed in **7.051s**; its screenshot shows the marker below the full-width
heading. Its test-only build passed in 37s, and production source/main APK stayed
unchanged. Seven distinct Android cases therefore have passing evidence across
the two runs. Both runs had no new crash/ANR records; timeouts were restored and
the sole AVD stopped/reaped.
Evidence: ignored `captures/runtime/setup-guidance*`. Signed 650/global pin remain
unchanged; physical/matched-iOS/TalkBack and full connection acceptance remain
open. In particular, this classifier does not invent a phone sign-in diagnosis
from Irx grant, protocol or peer-identity rejection.

Scoped source: `f4b1509054949eaad5d695569ad443c4c18ed68d`,
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/SetupHelpView.swift`,
`SetupHelpGateContent.swift` and the entry in `MobileSettingsView.swift`.
The global parity pin is unchanged.

Settings now opens **Set Up Computer** before View Introduction Again. Like the
iOS Settings entry, this is a plain reference in setup order, without a current
blocker marker: Sign in; Run cmux on your computer; Wake the computer; Match the
account; then How it connects and the same-account footer. It has a fixed Done
header, scrolling content, heading semantics, decorative icons and wrapping text.
Opening the screen does not create an account, change pairing or start discovery.

Mac pairing wording names the existing Enable iOS pairing toggle and explains
that it also enables Android. Minimum-version guidance reads the same live policy
as admission and disappears when that requirement is absent. Network guidance
follows the Iroh/private-network reference. The phone download uses
[Tailscale's official Android link](https://tailscale.com/download/android),
verified to target `com.tailscale.ipn` on Google Play; the computer link targets
the existing official download page. A failed browser launch shows a retryable
inline error, and a later successful launch clears it.

## Evidence

Only the existing API37/16 KiB AVD was used. Two distinct Android cases have
passing results, across the recorded runs:

- The production NativeScreen Settings route opens/closes the reference,
  preserves its saved pairing and the same open transport, then opens a terminal
  and sends actual `terminal.paste` input. This passed in the **18.792 s** batch.
- The final component case passed in **11.250 s**: open-dialog restoration;
  all sections in source order; live minimum-policy changes; fixed header while
  scrolling; both exact download targets; failed launch and recovery; Done
  dismissal; and large-font text. It directly verifies the renderer's font scale
  is **2**, rather than assuming a parent test override reached the Dialog.

Original failures remain in ignored captures: the first connected test omitted
the notification data its fixture expects (**34.200 s** batch); the stronger text
layout assertion then proved Dialog content rendered at scale 1 instead of 2
(**18.792 s** batch). Passing the host density into the Dialog fixed rendering.
The next ordering assertion incorrectly used clipped off-screen bounds at the
larger size (**5.995 s**); unclipped bounds fixed the test without changing UI.
The inspected final screenshot shows actual enlarged text and a visible Done
button; it is not a matched-iOS screenshot or a physical Pixel check.

Builds succeeded in 1m35s, 30s, 27s and 19s. Original and repaired runtime/build
logs are retained as `captures/runtime/settings-setup-help-*`. Final crash/ANR
buffers are empty. The screen timeout was restored and the sole emulator and
Gradle processes were stopped/reaped. No physical Pixel data changed.

Final debug APK SHA-256:
`c411a2478d0c4e491593d1112f891397c0b82ffd91af94fe04cac5b99ab94a9e`.
Final test APK SHA-256:
`e9de65cd2dd5a80bcf6f7aaa29df3e1a7f6924f36c97c99fccdb584c06f9bbf3`.
The connected test was not repeated after the text-density change; the final
renderer case used these APKs. Signed 650 is unchanged.

The existing connection-action guide remains separately available from sign-in
and Computers. Highlighted setup gates on disconnected/onboarding screens,
matched-iOS grouped styling, TalkBack, localization and physical workflow
acceptance remain open. This checkpoint does not complete overall Settings or
connection parity.

## Highlighted guidance artifact hashes

Debug APK SHA-256:
`d917354bb30a29e01c1ca4b0682ee7415ee886cf01fd1944372b53dc80f5dc75`.
Initial test APK SHA-256 (six-case run):
`803ad739c2e5c1ee8e3ceff2389cea1c490a038621d8299b1b0a99383eb82322`.
Final test APK SHA-256 (scaled-marker follow-up):
`c13ac537da2cf379f9af6643366986a39d5108876b47415f2b6a4f727e35ea07`.
Source/test/APK receipts: `captures/runtime/setup-guidance-verification.json`.
No physical Pixel data changed; signed 650 remains the published download.
