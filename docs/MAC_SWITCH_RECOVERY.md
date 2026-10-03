# Recovery after a failed computer switch

Included, with the pending-picker follow-up below, in signed development build
**463**. See [PIXEL_INSTALL.md](PIXEL_INSTALL.md) for delivery and upgrade evidence;
the individual debug checkpoints below retain their original artifact details.

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
- Sign-out, login/team changes and unrelated route changes retire the pending
  switch. All Computers cancels a pending picker switch and restores its previous
  authorized Mac while retaining the All filter. Notification routes do not acquire
  an implicit picker rollback intent. Approved pairing links now explicitly use
  this recovery with a saved fallback, as documented in [Pairing startup](PAIRING_STARTUP.md).
- The baseline contains pairing metadata and filter state, with no client,
  credentials, Activity, terminal input or process-death persistence. It is owned
  by the existing screen ViewModel and cleared with that session.

Android restores the computer overview/filter. The targeted source review does
not establish a requirement to reopen the exact prior terminal/browser pane after
a picker switch; that should not be inferred from the route baseline alone. It
conservatively refuses an old live route if its saved record has changed. This
checkpoint does not claim the entire iOS switch/cancellation or startup
attach/reconnect contract is finished. Physical multi-Mac, native Iroh,
Activity-recreation and full visual acceptance remain open.

## Initial restoration verification

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

## Pending picker selection and cancellation — 2026-10-03

The subsequent exact `WorkspaceListView.swift` review at `0fc35d6` found that
`handleMacTitlePickerSelection` retains a pending choice, `currentMacTitlePickerSelection`
uses it for menu state, and `macTitlePickerShowsProgress` presents progress.
`applyMacTitlePickerSelection` commits the list filter only after `switchMac`
succeeds. All/automatic selections cancel a pending switch with
`restorePreviousOnCancel: true`. `MobileShellComposite.swift` and
`MobileShellComposite+MacSwitchState.swift` enforce the current restore generation
and foreground route authority. They do not capture an exact previous pane in
that route baseline.

Android's toolbar picker now keeps the prior workspace/notification filter while
connecting. It displays a spinner, exposes **Connecting to [computer]** to
accessibility, and checks the pending Mac in the menu. Only the verified successful
connection commits the new filter. Failure removes the pending indicator and
uses the existing fallback path.

Selecting All Computers during a pending switch retires the target connection
effect and restores the authorized baseline, with All remaining the selected
filter. If no baseline is still permitted, it returns to Computers. Late target
callbacks cannot replace the restored Mac or persist the cancelled target.
Restoration keeps a new attempt identity so a subsequent selection can supersede
it. A stale cancellation with another owner/route cannot clear the newer intent.
This change is scoped to the toolbar picker; internal task/draft routing retains
its existing explicit selection path.

Verification: **12 JVM cases passed**, with no failures/errors/skips. They include
All-filter restoration, rejection of stale cancellation without clearing a newer
attempt, missing/revoked baseline handling and the previous failure/supersession
cases. The final debug/test build passed in **21 seconds** after the initial
build passed in 66 seconds. Packaged attribution was checked against source.

**14 Android cases passed in 129.974 seconds**, with no failures or skips, on
the existing API 37 / 16 KB emulator. The two new cases establish a real foreground
terminal first, then hold a second Mac's dial. They verify the old filter and
workspace rows remain until success, the pending spinner/accessibility state,
successful target input routing, and All cancelling the switch. The cancellation
case releases a deliberately non-cancellable late successful dial, verifies its
client closes, checks saved pairing/filter state, and reopens the original
terminal without input going to the cancelled target. The other twelve cases
cover existing connection/launch recovery plus workspace and notification picker
search, mutation, bulk-read and colliding-ID routing.

The pending-state screenshot was reviewed using the app theme and root Surface.
The workspace rows and old Mac label remain visible during the switch. This is
not full iOS visual or physical multi-Mac acceptance. No native library changed;
no new AVD or signed milestone was created, and the emulator was shut down.
Evidence: ignored `captures/runtime/mac-picker-cancellation/` (JVM XML, build and
instrumentation logs, screenshot, APK hashes). Signed build 456 is unchanged.

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `46e9355156f6bbbdc908ad4bba317ba02c2c9daf973d98269099d0dd04ce0efe` |
| Test APK | `e96c1fd681e53cfa4dff22c90613f41d39d4c4a484288a59bab6645d2fea1f10` |


## Stable open computer menu — 2026-10-03

The scoped `0fc35d6` review of
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/WorkspaceMacTitlePickerMenuButton.swift`
and its `WorkspaceMacTitlePickerMenuTests.swift` establishes a separate menu
contract: rows and callbacks are captured together for each opening, while the
button's accessibility state stays live. Twenty refreshes leave the open iOS
menu unchanged; reopening gets current values and actions even when rows compare
equal. Android previously iterated live row/appearance/selection values.

`NativeComputerSelector` now captures names, order, selection checkmarks,
keep-awake indicators and actions for one opening. The toolbar's pending progress
and accessible target continue to update. Selecting a row dismisses the menu and
checks the current login/team scope and connector permission before invoking the
captured action. A removed, ambiguous or replaced saved route is rejected; a name
change alone is accepted. Account/team generation changes dismiss the opening.
These checks read current authority at the tap, including changes that have not
yet caused recomposition. All Computers and Pair actions also require the original
account scope. No action silently substitutes a newly saved route.

This is a scoped menu-stability change, not a complete visual-parity claim.
iOS also displays authoritative build subtitles and conditionally exposes Add
Computer; the Android subtitle/source resolution and add-availability mapping
remain to be audited. `WorkspaceMacBuildLabelResolver.swift` was inspected as
context, but a tag such as `default` is not assumed to uniquely mean Stable.
The whole-parity upstream pin is unchanged. This follow-up is not in signed 463.


### Verification

Four JVM checks passed (zero failures/errors/skips). Eight Android checks passed
on the existing API 37 / 16 KB emulator in **78.592 seconds**: four new menu cases,
two pending/cancelled-switch cases and the existing workspace routing and
notification-filter integration cases. The new cases cover twenty in-place
refreshes, live toolbar semantics, next-opening state and callbacks, unchanged-row
callback updates, forgotten/replaced pairings, account-scope dismissal and
revocation before recomposition. Debug/test builds passed in 64 seconds; final
packaged attribution was also verified after the incremental asset build.

Evidence: `captures/runtime/computer-menu-snapshot/` (ignored), including build
logs, JVM XML, instrumentation output and hashes. The emulator was stopped after
verification. ADB showed no physical Pixel; no real Mac/Pixel or visual acceptance
is claimed for this checkpoint. No additional AVD or signed APK was created.

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `98cabf022348434d321423447a392ec806f2c8d90463db74aa93e70db211bc00` |
| Test APK | `f4823ad403aca951998d148c6b44ebf45f9892693f28922649c2baee9c372a08` |
