# Recovery after a failed computer switch

## Source comparison — 2026-10-03

Targeted reference: cmux
[`MobileShellComposite.swift`](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite.swift),
`MobileShellComposite+MacSwitchState.swift` and
`IrohMacSwitchRecoveryTests.swift` at
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`.
The iOS switch path snapshots the live foreground pairing, restores that baseline
if a switch fails after retiring it, and lets newer selections supersede recovery.
This is a scoped review; the whole-parity upstream pin is unchanged.

Android previously closed the foreground client and repeatedly retried a failed
new selection. `NativeMacSwitchRecovery` now retains a verified foreground
pairing and its computer filter before a manual selection. Picker, saved Settings
rows and discovery selections use this path. A failed switch selects the previous
route again and reconnects through the existing authenticated connector. The UI
reports that the previous computer was selected again; it does not claim that
reconnection has already succeeded.

## Ownership and supersession

- Restoration requires the same login incarnation and full team scope, including
  generation. The exact route, device ID, instance tag and account ownership must
  still be saved and permitted by the connector. Forgetting the record or changing
  its route disables restoration. A name change alone does not.
- Rapid selections retain the original baseline and filter, including two taps
  before Compose retires the old client. A newer attempt rejects an earlier
  failure. A verified route normalization preserves the current attempt.
- A new choice during restoration retains the original baseline. Failure of the
  restoration itself uses ordinary retry and cannot alternate between computers.
- Sign-out, login/team changes, unrelated route changes and returning to the All
  Computers filter retire the pending switch. Notification/deep-link routes do
  not acquire an implicit picker rollback intent.
- The baseline contains pairing metadata and filter state, with no client,
  credentials, Activity, terminal input or process-death persistence. It is owned
  by the existing screen ViewModel and cleared with that session.

Android restores the computer overview/filter, not an exact prior terminal or
browser pane. It conservatively refuses an old live route if its saved record has
changed. This checkpoint does not claim the entire iOS switch/cancellation or
startup attach/reconnect contract is finished. Physical multi-Mac, native Iroh,
Activity-recreation and full visual acceptance remain open.

## Verification

Eight focused JVM cases passed with zero failures/errors/skips, covering baseline
ownership, rapid taps, route normalization, restoration supersession, no-loop
behavior, initial connection, scope changes and revoked authority. Debug/test APK
builds passed in 47 seconds; the subsequent attribution-only APK build passed in
9 seconds. Packaged attribution matches its source.

Five Android cases passed on the existing API 37 / 16 KB emulator in **53.589
seconds**, with no skips. Two new cases exercise the production screen through
real local RPC peers: failed selection restores the previous filter and terminal
RPC destination; a late non-cancellable failure cannot redirect a newer choice.
Three existing connection tests cover dial timeout, host-handshake timeout and
screen disposal. The fixtures enforce emulator-only execution and do not touch
the physical phone's account or app data.

Evidence: `captures/runtime/mac-switch-recovery/` (ignored), including JVM XML,
instrumentation output, build logs and artifact hashes. No new AVD was created;
the emulator was stopped after verification. Pixel was absent from ADB. Signed
milestone 456 is unchanged and does not include this feature.

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `aefabaeb7f1cfe7b3533214cb62b3df91adf85f302f1db8b18017e6447fdea06` |
| Test APK | `7cff63a0f642003b8c8e2977d1785940d1efea77afbf781cdde6467a4eb60a5c` |
