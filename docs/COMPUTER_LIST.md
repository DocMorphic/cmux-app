# Computer method sections and route captions — 2026-10-03

## Source comparison

Scoped reference: cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`, under
`Packages/iOS/CmuxMobileShellUI`:

- `MacComputerListSection.swift` and its tests: each computer appears once under
  its own configured method; nonempty Iroh, Tailscale, Direct sections in that
  order, retaining newest-first order within each section.
- `MacComputerSnapshot+Store.swift`, `MacComputerSnapshotDuplicateTests.swift`:
  only later same-named rows with confirmed offline presence get Older pairing.
  Unknown and online rows are never labeled stale; names are trimmed and folded.
- `DeviceTreeRouteDescription.swift`: method-specific endpoints; Iroh identities
  shortened to twelve characters, host/port routes and enabled direct addresses.
- `MacComputerRow.swift`: a diagnostic caption combines presence and route;
  reconnect rows and connected rows with unknown presence show just the route.
- `DeviceTreeView.swift`: the complete Computers screen additionally owns saved
  visibility, hidden rows, SSH sections, details navigation and reload behavior.

The global parity pin is unchanged. This source review covers the list projection
and captions, not the complete DeviceTreeView contract.

## Android implementation

`NativeComputerList` projects existing saved pairings, per-account/team method
preferences, the exact device/build in the authenticated directory, confirmed
Tailscale grants, and encrypted last-seen history. Only endpoint strings leave
the credential snapshot; no account credential JSON is retained in Compose state.
Tailscale captions require the current login and exact account/team/device/build.
Iroh uses a current exact directory entry first and its saved public lookup hint
second. These are diagnostic values: all connection authorization and routing
continue through the existing connector. A caption is not proof of reachability.

Saved Settings rows now have Iroh/Tailscale/Direct headings. Each saved row appears
once. Read failures show Connection settings unavailable rather than implying the
Iroh default. Legacy TCP pairings retain their actual Tailscale method. Disabled
direct addresses are skipped, IPv6 host/port captions retain brackets, and no
route is shown when the configured method has no corresponding endpoint (Iroh
may use the upstream generic host/port fallback). There is no route lookup across
builds or fallback to an arbitrary ambiguous directory row.

Rows are ordered by the newest available verified/presence last-seen timestamp;
equal/unknown timestamps retain input order. This differs from upstream's richer
persisted pairing timestamp model because historical Android pairings have no
creation/last-seen date. Dates are not invented for those records. Older pairing
is display-only and never merges, deletes or changes a saved pairing. Grouping
happens after marking duplicates, so identical names in different methods retain
the marker. Saved and discovery/reconnect rows share endpoint diagnostics and
reuse an exact saved row's older-pairing state where available.

Changing a method updates its section through the existing scoped settings flow;
reordering or status refresh does not itself invoke the row's selection callback.

## Remaining work

The full Computers destination still needs navigation/layout parity, hidden-row
integration, inline SSH sections, version warnings. The [saved reconnect follow-up](RECONNECT_COMPUTERS.md) now
retains offline Macs absent from discovery and reconciles exact saved/directory
rows. Existing separate Android features must be reconciled with the remaining
iOS surfaces. Production Pixel/Mac
acceptance is pending; presence-driven route refresh, workspace announcements and
push recovery are separate work. This change is not in signed build 468.


## Verification

**23 JVM cases passed** with zero failures/errors/skips: six new projection cases,
eight existing per-Mac method settings cases and nine presence/label cases. These
cover canonical device/exact-build resolution, ambiguous directory fallback,
current login/account/team grants, disabled direct addresses, unreadable settings,
legacy TCP behavior, ordering/grouping and confirmed-offline duplicate marking.

**Seven Android cases passed in 44.513 seconds** on the existing API 37 / 16 KB
emulator: two new list/diagnostic cases, three presence/encrypted-history cases,
and the existing direct-address editor and independent Details-button checks.
Method changes regroup rows without selecting a computer; an explicit row tap
selects the original exact pairing. Reconnect diagnostics do not repeat presence
or include stale workspace counts.

The method-section screenshot was visually inspected: headings, avatars, build
badges, endpoint captions, offline duplicate label and grey disconnected dots
render correctly. The long older-pairing caption uses the intentional one-line
ellipsis. The fixture renders production saved rows in isolation; the screenshot
does not establish complete Computers-screen or physical-device visual parity.

The initial debug/test build took 1m 9s; the final production integration build
and JVM rerun took 39s. Packaged attribution exactly matches the source asset.
The first UI dump raced emulator storage readiness and was recaptured. On cold
boot, the emulator's recurring System UI ANR dialog appeared before app testing;
closing the observed button restored a clean launcher, recorded before the tests.
No cause is established for that OS issue. No additional AVD was created, and the
existing one was shut down after testing. The Pixel remained absent from ADB.

Evidence: ignored `captures/runtime/computer-list/`, including build logs, JVM XML,
instrumentation report, before/after launcher dumps, screenshot and APK hashes.
No signed APK was produced for this feature; build 468 remains the last signed
milestone.

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `bc8976bc7b71e90ca78a3ae20046040f95e08f0a73395e815fa3a4fa893d89d2` |
| Test APK | `abc38217980648fc5f91357b6bb0b2b2bfe32432e28d0d255c6a3190d2fc4191` |
