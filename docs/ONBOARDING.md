# Android introduction and Mac connection flow — 2026-10-03

Implements the five scenes reviewed in [ONBOARDING_AUDIT.md](ONBOARDING_AUDIT.md):
workspaces, notification feed, notification opt-in, Mac pairing setup and connect.
The reference remains the scoped upstream 0fc35 revision; the global parity pin
is unchanged.

## Behavior

- First-run progress is per installation: welcome, connect, complete. Missing or
  unknown values begin at welcome. Writes must succeed before the UI considers
  progress saved. A completed tour cannot regress to connect. Fixture connectors
  bypass the gate without changing installed progress.
- The tour requires a currently verified account/team owner. Cached credentials
  alone cannot enter it. Account/team recovery stays available through Settings,
  and explicit incoming pairing/workspace/notification routes take precedence.
- Back and horizontal paging share one settled stage. Pairing and connect have
  no Skip action. Successful connection does not dismiss the tour: the user
  chooses Open Workspaces. Saved-instance restoration retains the page, method,
  pasted code and whether connect entry was already dispatched.
- Settings offers View Introduction Again. Replay starts at the first page,
  never writes first-run progress and does not initiate discovery/connection
  merely by being opened. An offline/cached account can read the introduction;
  connection actions lead to account Settings until verified authority returns.
  Explicit retry and pairing remain available for verified accounts.
- Automatic discovery uses the existing shared runtime. One available Mac can be
  selected in foreground first-run onboarding; multiple Macs remain selectable.
  Hidden Macs, a pending explicit pairing, another active connection attempt and
  stale account ownership prevent incidental selection. Saved computers can also
  be selected through their existing guarded reconnect path.
- The method picker remains available after a successful connection. Selecting
  Tailscale in that state opens the scanner rather than completing the tour.
  Scanning and pasted codes use the existing parsing, ownership and explicit
  grant-confirmation path. Canceling the scanner leaves the tour available.
- Keep Mac Awake reuses the exact account/device/build power session. The offer
  appears only for a connected Mac with supported, known state; pending mutations
  disable its switch through the existing power controller.
- Enabling notifications is explicit and single-flight. Permission results are
  confined to the account that requested them. A late result cannot move the
  user back to a push page they have left. Denial still permits continuing setup.
  Not Now performs no notification operation.

## Android presentation and delivery limits

The guide uses the original Mac Settings screenshots and cmux logo. The first
three scenes show labeled, static Android-style examples; they are not live user
workspaces or claims of connected devices. Portrait keeps the footer aligned;
compact-height layouts place actions side by side and use two columns at normal
text sizes. Large text retains a vertically scrollable layout. Page content can scroll
vertically for small screens and large text, and offscreen pages hide accessibility
semantics. Stage changes are immediate and introduce no forced motion.

The notification page accurately describes the current Android foreground
connection service and ongoing notification. The encrypted FCM ingress exists,
but this installation has no Firebase project/token registration or verified
sender. Permission is not described as proof of push delivery when the connection
is stopped. Configured push and physical Pixel/Mac notification acceptance remain
required for the full goal.

## Verification

19 JVM tests passed (nine onboarding state/gate tests and ten existing power
controller tests). Debug and test APKs built; the final account-scope rebuild took
50 seconds. Nine initial Android tests passed in 77.144 seconds, including the
power section and reusable setup guide. After layout/replay review, three tour
tests passed in 27.387 seconds; the walkthrough also passed in normal landscape
(11.429 seconds) and 150% text portrait (7.167 seconds).

The final APK passed all four onboarding UI tests in 38.916 seconds at the actual
150% portrait font scale, including offline replay, permission single-flight and
late results, swipe/button navigation, saved page/draft restoration, method change
after readiness and explicit completion. Screenshots were reviewed in normal
portrait, normal landscape and large-text portrait. The production MainActivity
also cold-launched signed out in 2,553 ms with an empty crash buffer; this isolated
launch is not a performance benchmark. Only the existing API 37 / 16 KB emulator
was used. Its font/rotation settings were restored and it was shut down.

Final debug SHA-256: `41de8b22e3d6fa09eda7e33d00323ba4d652aa80367486ea0bd639b0560f8ce3`.
Final test SHA-256: `eda26032df8d77e877aabee6e1ff4abfbba2a43092e857ab619518e004ef843b`.
Evidence: ignored `captures/runtime/onboarding/` (build logs, XML test receipts,
instrumentation logs, hashes, screenshots and verification.json).

The UI tests inject permission results and connection phases. Saved-instance
restoration is emulated by the Compose testing API; it is not whole-app process
restart evidence.
Physical Pixel/Mac pairing, whole-app process restart, account switching during a
real OS permission request, QR cancellation, connected power mutations and live
onboarding reconnection remain acceptance work. The scoped UI fixtures do not
prove these workflows. Build 481 predates this introduction implementation.
