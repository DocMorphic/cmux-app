# Native computer details and independent connection checks

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`, particularly
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MacComputerDetailView.swift`
for per-device/build identity and private-address placement, and
`MobileIrohSettingsView.swift` for app-wide reset behavior.

## Implemented

The Computers picker and saved native computer rows in Settings now expose a
separate **Details** button. Opening it does not select a computer, replace the
active workspace, or dial a Mac. The page identifies the exact Mac device/build,
shows directory availability and selectable device ID, and scopes private-address
editing/removal to that Mac/build. Another build of the same Mac cannot appear in
that address list or be changed by its editor.

Native Connection Check and private-address controls moved out of the main
Settings page into these details. The existing selected-connection checker stays
available for legacy TCP pairings. Networking now owns the confirmed, account-wide
private-address reset; it disables paths while retaining coordinates. Reset blocks
duplicate taps, keeps the confirmation open on failure and supports retry.

The per-Mac checker works without an already-open connection. It:

1. Fences the current account/team incarnation and refreshes authenticated discovery.
2. Resolves the exact device/build from the fresh, unexpired directory. A missing
   or changed build produces a safe discovery failure, with no stale dial attempt.
3. Uses the refreshed endpoint/record, rather than a saved endpoint hint. The
   connection acquisition is fenced to the original runtime owner as well.
4. Borrows a connection lease and verifies `mobile.host.status`, followed by an
   authenticated `mobile.workspace.list` read and actual transport diagnostics.
5. Closes only that lease. An existing UI/feed/service lease stays open. A newly
   opened check-only connection is released on success, mismatch, error or timeout.

The overall check has a 30-second discovery/dial deadline; its connected RPC check
retains the ten-second limit. Caller cancellation propagates. Reports exclude
Mac names, device IDs, addresses, account credentials and raw server errors. Checks
send no terminal input and do not change the selected computer or workspace.

Old native QR codes may omit optional user/team/device/build hints. Details accept
those omissions only when the saved record has a known device ID and build; any
provided conflicting field is rejected. The QR is not authority: checking still
requires the exact Mac/build in the current team's authenticated directory.

## Verification (2026-09-29)

Thirty JVM tests pass with no failures/errors/skips: nine computer-check cases,
eight existing connected-check cases and thirteen runtime/account cases. They
exercise unopened and already-open Macs, lease release/preservation, refreshed
endpoint adoption, removed/wrong-build targets, wrong-team/retired-account fencing,
identity failure before workspace reads, full-workflow timeouts, caller
cancellation and old native QR compatibility.

Seven Android 17 arm64 emulator UI tests passed in **28.26 seconds**. They cover
checking an undiscovered target, pending-state duplicate prevention and safe report
sharing, editing only one Mac/build, Details versus parent-row selection, existing
Networking refresh/error/cancellation behavior, and confirmed global reset with
retained coordinates. Detail and private-address screenshots were inspected.

After that UI run, a final compatibility adjustment allowed omitted optional hints
in old native QR codes. Its positive/rejection cases passed in the repeated
30-case JVM run, and main/test APK builds passed. The seven-case UI run was on the
preceding navigation build; it was not rerun for that factory-only adjustment.
The test APK is unchanged. Native ELF and 16 KiB ZIP checks pass on the final main
APK; no native library or FFI binding changed.

Evidence is in ignored `captures/runtime/computer-details/`, including separate
UI-tested and final artifact hashes, JVM XML, build logs and screenshots. No phone
or Mac setting was changed. The Pixel remains on the preceding `ab1901a` debug
checkpoint, and published signed build 157 remains unchanged.

| Artifact | SHA-256 |
| --- | --- |
| Final main debug APK | `74ee5dadad9847e52888cdecc712f33e4519f2cad8a716eab57f2d3e77efe82a` |
| UI-tested navigation APK | `6f53e7953120bd1d5ef3c7f93a8164fd404c8fda480ab2c9a17ab591fb39cbda` |
| App test APK | `1e28a375d200b2dd7ab76d14b4b74f15aa5bf9492acfc46d1865a44216bfe6bc` |

## Still required for complete computer-detail parity

The subsequent [Mac Power checkpoint](MAC_POWER.md) implements the detail's
capability-gated keep-awake status, mutation, reconciliation, events and retry.
The [appearance checkpoint](COMPUTER_APPEARANCE.md) adds local name/color/icon
editing and shared display across computer/workspace surfaces; physical-device
acceptance remains open. The active pinned iOS composition also stores appearance
locally; see the source correction in that checkpoint.

- Physical Mac/Pixel acceptance of the new detail navigation and check-only lease
  flow, including a genuinely unavailable Mac and another app build.
- Physical acceptance of per-computer connection status, foreground role and
  workspace count (implemented in [COMPUTER_CONNECTION.md](COMPUTER_CONNECTION.md)).
- Live acceptance of appearance, the Mac Power control and
  [keep-awake row indicators](MAC_POWER_INDICATORS.md).
- Physical acceptance of [Iroh/Direct selection and direct addresses](DIRECT_CONNECTION.md),
  now implemented through the runtime and native backend. [Tailscale-only selection
  and authorized route editing](TAILSCALE_CONNECTION.md) are also implemented;
  saved Tailscale reconnect/checks now run independently of Iroh discovery.
  Legacy route unification and physical checks remain open.
- Compatibility/version-floor guidance and physical acceptance of the
  [confirmed Forget/local-cleanup flow](COMPUTER_REVOCATION.md). Confirmation,
  scoped server removal and durable local cleanup are implemented. Legacy or
  unresolved pairings retain an explicitly labeled local removal action.
- Full visual comparison with the running iOS detail. Its pinned body renders
  private addresses and identity; the shared Networking source comments describe
  moving checks per computer, but that comment alone is not proof of a rendered
  check section in the pinned detail. This Android placement integrates the
  existing checker and is not evidence of pixel-identical iOS navigation.

The full companion goal remains active. This checkpoint does not claim complete
computer-detail parity or direct-only routing.

## Scoped Tailscale-only Details (2026-10-06)

Authenticated saved Tailscale rows with an explicit matching account/team, stable
origin and known device/build now expose Details without native discovery. Bare
URL hints and unowned, mismatched or unresolved rows cannot establish identity.
The page exposes the existing appearance, connection-method and route controls.
Its independent checker uses the admitted saved connector (or the existing
Tailscale-only runtime), verifies host identity and reads workspaces, then closes
only its lease. Account changes and removed/replaced rows fence the report.

Connected rows reuse the feed-owned Keep Mac Awake controller and its mutation
gate; opening Details does not create another connection or event subscription.
Confirmed remote Forget can clean up the captured scoped raw row while preserving
sibling builds and changed replacements.

46 focused JVM tests passed with no failures/errors/skips. Main and Android test
Kotlin compilation passed; the final focused run took 20 seconds. The initial
compiler method-size failure was resolved by extracting the Details host and
onboarding power composables. Logs, XML and source hashes are in local ignored
`captures/runtime/tailscale-computer-details/`. The new Android UI test
`scopedTailscaleRowOpensDetailsAndChecksItsSavedRouteWithoutNativeDiscovery` is
compiled and queued for the next integration milestone; no APK or emulator run
was performed for this batch.

The subsequent edited-address checkpoint in `TAILSCALE_CONNECTION.md` implements
main raw-primary reconnect selection and persistence using replacement grants,
with exact ticket-source coverage. Physical acceptance of both the Details and
main reconnect flows remains open, along with legacy pre-tag adoption and
remote-account Forget. Global upstream pins are unchanged.

A legacy record's fully scoped provisional directory locator now supplies its
Details target before the host has confirmed a final stored build. The connection
still requires current account permission and exact host verification. See
`ATTACH_TICKETS.md` for sole-build admission and remaining acceptance.

## Combined legacy/Details Android milestone (2026-10-06)

At source `af1d085f12ca3a484dc432f91b4e3eb5088a3786`, one debug/test APK
build passed in 74 seconds. **14/14 Android tests passed in 80.669 seconds** on the
existing Android 17 / API37 / 16 KiB AVD. These include all five Computer Details
cases and nine ticket/Keystore cases: the previously queued Tailscale-only Details
check and legacy-build Keystore reload are now executed, alongside existing
navigation, per-build private-address editing, connection-state display, external
route reuse/revocation, ticket handling and directory persistence regressions.

Seven screenshots were inspected. Text, diagnostics, route explanations and
buttons were readable at font scale 1.0. The fake saved-route checker reports
identity/account verification; its transport fields correctly say Not Reported.
These captures verify fixture rendering, not pixel-identical iOS parity.

No new crash/ANR events occurred during testing; the final crash buffer was empty.
The existing AVD was stopped and reaped; no new virtual device was created. Pixel
was absent at preflight. No physical Mac workflow, signed-in upgrade, production
push or signed release acceptance is implied. The latest signed download remains
616. Logs, command, APK/source hashes and screenshots are in local ignored
`captures/runtime/legacy-details-milestone/`.

## Combined identity-upgrade Android milestone (2026-10-06)

At base `9fdd6de` plus the recorded restart/test patch, one debug/test APK build
passed in 96 seconds on the existing Android 17 / API37 / 16 KiB AVD. **20 distinct
Android cases now have unassisted passing evidence**: all five Computer Details
and twelve ticket/Keystore cases, plus three corrected screen-level pairing and
recovery flows. This executes the queued raw-grant, background-selection and
fresh-confirmation encrypted-storage upgrades.

Screen integration exposed a same-locator restart edge case: an explicit attempt
that learns a build could close its session without changing its effect key.
The branch now increments its retry generation when a restart is needed under
the same locator. The screen test confirms explicit dialing, learned build,
replacement connection, terminal replay and displayed fixture text.

The first run passed 17/20 in 102.731 seconds. Three screen cases incorrectly
expected external raw links to present fresh confirmation, despite the current
in-app-only entry policy. The corrected tests exercise actual Add Computer/paste
entry and external-link rejection. Two passed directly; the third stalled in
Espresso's next-frame idle wait with the IME open. Its first follow-up completion
was manually assisted and is excluded from the unassisted pass count. A captured
SIGQUIT trace showed instrumentation waiting in Espresso and the main thread in
its message loop. The test now explicitly dismisses the visible IME before
scrolling. It passed alone in 15.643 seconds. Its first terminal capture was too
early (blank), so a stronger check waits for terminal text; that final case passed
unassisted in **17.638 seconds**, and its screenshot visibly shows fixture output.
Only the test APK was rebuilt for those follow-ups (20, 22 and 19 seconds).

Eight screenshots were inspected: readable Details/confirmation controls and the
rendered terminal, at font scale 1.0. No new runtime crash/ANR events appeared in
the recorded event-buffer comparisons; final crash buffer empty. All emulator
processes were stopped/reaped before builds and at completion; no new AVD was
created. Pixel was absent at preflight. No signed release or physical Mac/Pixel
acceptance is implied; signed build 616 remains the last verified signed artifact.

Evidence: ignored local `captures/runtime/confirmed-upgrade-milestone/`, including
all initial failures, the assisted run, thread trace, command/source/APK hashes,
final unassisted logs and screenshots. Debug logs also report ART skipping the
large `NativeScreen` method (44,301 instructions) and skipped frames. Investigate
this performance evidence and reconcile the remaining old raw-launch fixtures;
neither is closed by this milestone. Full pairing/notification/input/network
acceptance, legacy metadata comparison and iOS UI parity remain open.
