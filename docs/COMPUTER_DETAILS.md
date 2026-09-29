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
- Per-computer connection-method selection, direct-only endpoint/address intents,
  route editing and remaining legacy Tailscale detail flows.
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
