# Pairing links and startup reconnect

## Source scope

Reviewed cmux at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:

- [`CMUXMobileRootView.swift`](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/CMUXMobileRootView.swift):
  `reconnectStoredMacDuringRestoreIfPossible`,
  `finishAuthenticationBootstrapAndConnect`, `consumePendingURLIfReady`,
  `startOpenURLConnection` and its disconnected-attach fallback.
- `MobileStartupConnectionCoordinator.swift` and
  `MobileInjectedAttachStartupTests.swift`: the app-lifetime coordinator's injected
  attach route is a DEBUG/dev launch facility. Android does not copy that runner
  into production. The production deferred-link ordering and saved reconnect
  behavior inform this implementation.

This targeted comparison does not advance the whole-parity pin or claim support
for every newer upstream attach-ticket format.

## Android behavior

An incoming pairing link owns the screen's first foreground connection decision.
Saved foreground and screen feed dials wait while the link is unresolved or its
Tailscale confirmation is open. Account discovery can still run to resolve an
Iroh link. Once a foreground attempt has begun, a later link does not suspend the
existing session merely to show confirmation.

Tailscale links retain explicit confirmation before granting access. Cancel or
dismiss consumes that launch request and releases the saved reconnect. A pending
Iroh lookup shows **Finding this Mac…**, with Cancel, and expires after 30 seconds
if account/directory resolution cannot finish. Expiry consumes only that request,
shows a retry explanation, and releases the saved reconnect. A newer route or
login retires the old timer. The 30-second bound and dialog are Android behavior,
not a claim that iOS uses this exact presentation or duration.

An approved pairing attempt captures the previous live route, or the current
saved pairing when no live route exists yet. Failure uses the existing
[switch recovery checks](MAC_SWITCH_RECOVERY.md): same login/team scope, exact
saved route and app instance, current connector permission. A removed or changed
record cannot be restored, and failed restoration cannot cycle back to the
failed new target. A successful attach selects the newly verified computer's
filter after the host handshake. It no longer leaves an old Mac's filter selected.

Manually pasted and opened Iroh links use the same directory resolver. Both check
user, team, endpoint, device ID, build tag, current directory scope and ambiguity.
Pasting a link cannot silently resolve to a sibling Stable/Nightly instance.

The existing `NativeLaunchRoutes` consumed-state encoding and Android saved-state
confirmation remain in use. This change does not retain an in-flight transport
task across Activity/process recreation. After process death, persisted verified
pairings are reconnected; an unverified in-flight attach is not persisted as a
successful pairing. Exact prior pane restoration, physical Iroh/account/team
acceptance and the wider source audit remain separate gates.

## Verification — 2026-10-03

- **18 JVM checks passed**, with no failures/errors/skips: ten switch-recovery
  cases (including saved fallback before a live connection and live-over-saved
  precedence) and eight launch-route parsing, ownership and directory cases.
- The final app debug/test build passed in **59 seconds**. The earlier
  build with JVM checks passed in 52 seconds; attribution-only packaging took
  9 seconds. A later test-only theme correction built in 17 seconds and left the
  app APK byte-identical. Packaged attribution matches its source.
- The complete connection suite passed **10 Android cases in 90.209 seconds**.
  It checks cold-launch deferral, confirmation dismissal, failed approved pairing
  fallback, successful pairing selecting the new computer, Iroh cancellation and
  timeout, plus existing switch supersession and connection-timeout/disposal
  regressions. Local RPC peers prove the terminal opens on the intended Mac.
- A follow-up run passed **7 cases in 102.892 seconds**: both lookup cases in the
  actual `CmuxTheme`, plus five existing Android task/process-restoration cases.
  Those cover a pending confirmation surviving process death, dismissal remaining
  consumed, replacing/reopening links, signed-out launch/launcher reentry,
  notifications superseding confirmation and a consumed original link preserving
  the restored terminal. The logs show Android restoring saved bundles in new UI
  PIDs. In total, **15 distinct Android cases passed**, with no skips.

The first run failed three new tests because they asserted dialog visibility
before the asynchronous launch effect presented its window. The tests now wait
for that actual UI condition; the failed output is retained. The first lookup
screenshot used a default light test theme. Its corrected dialog was rendered and
visually inspected with the app's dark theme. The Compose fixture omits the
Activity's outer Surface, so the background shell title color in that screenshot
is not evidence of production color rendering. Full-shell visual acceptance is
still a separate gate.

Evidence is in ignored `captures/runtime/pairing-startup/`: build logs, JVM XML,
both instrumentation runs, the initial failure, APK receipts and screenshots.
Tests ran on the single existing API 37 / 16 KB emulator, then it was shut down.
No AVD was added. The physical Pixel was absent from ADB, and its app data was
untouched. Signed milestone **456** is unchanged and does not include this update.

| Artifact | SHA-256 |
| --- | --- |
| App debug APK used in both passing runs | `739e783dda545be54c6d8343c172e5bb97556b2b6c53ab9ad8307bf47c7342e1` |
| Ten-case test APK | `23cc011807ea96a0c5ef4aa6abf953ea03c9b4535fef71e48654bfc170ffaf2f` |
| Final test APK, including theme correction and process checks | `39fb3d59eb70149eb8fa6333047637857b61e66fef4eb585abee631405bdbf38` |
