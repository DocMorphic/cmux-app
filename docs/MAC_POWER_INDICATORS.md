# Keep-awake indicators for computers

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`,
`MacComputerRow.swift` (`caffeineIndicator`): a small orange cup with the
accessibility label “Keeping Mac awake”, present only for confirmed live state.
The upstream `MobileHostService.swift` indexes subscriptions by stream ID;
multiple topic streams may coexist on one client/connection.

## Implemented

Settings computer rows, discovered computer rows and entries in the computer
selector display the shared original Android cup icon when their exact Mac/build
has a live, capable feed with confirmed keep-awake enabled. False, unknown,
unsupported, reconnecting and offline states display no cup. Discovery and a
selected computer filter alone cannot show an active indicator. The icon has no
power mutation action; power changes remain in Computer Details.

Each verified foreground feed session starts a read-only observer on its existing
client when `caffeine.control.v1` and a build identity are available. An older
saved pairing without a build tag can use the tag returned by its authenticated
host status; the observer verifies that exact device/build again before reading.
It never opens another connection. The notification service is separate and does
not start these UI-only observers.

The observer reuses the existing Mac Power status implementation: subscribe
before reading, strict Boolean replies/events, unique owned stream IDs, revision
protection against late replies, five-second requests, a ten-second backfill for
missed events, and bounded unsubscribe. It does not call `caffeine.set` or expose
a mutation callback. A failed power read clears the indicator without stopping
workspace/notification reads. The enclosing feed owns its client; the observer
only removes its own stream on exit.

Pause, replacement, revocation and reconnect discard keep-awake state while
retaining independently useful workspace/notification snapshots. The connection
projection also requires a connected, capable source, so a surviving foreground
terminal cannot promote stale power state from an offline feed.

## Subscription cleanup

Inspection while adding the observer found that the feed's previous `remove`
closed its lease before coroutine cleanup. The feed subscription itself also had
no retained stream ID. This could leave subscriptions on a shared wire still held
by another consumer. Each feed now owns a stream ID and unsubscribes within a
750 ms bound. Removal cancels its monitor, which releases the lease in `finally`
after cleanup, instead of closing the lease too early. Power-stream cleanup has
its own 750 ms bound and does not close the feed's client.

## Verification

Thirty-seven focused JVM tests pass: fourteen feed coordinator cases, ten Mac
Power cases, seven connection projection cases and six connection-pool cases.
New coverage includes independent Macs and stream IDs, malformed power replies,
unsupported hosts, legacy pairings with missing/non-text versus real build IDs,
no power mutations from the observer, disconnected/paused/retired state,
workspace access after a failed power read, and three pause/resume cycles that
remove both owned streams without closing another consumer's shared wire.
Existing power revision, mutation and lease tests also remain green.

Eight Android 17 arm64 emulator UI cases pass in **12.985 seconds**: four Mac
Power cases and four Computer Details cases. The indicator case verifies its
accessible label while enabled, then disappearance on disconnect, reconnect,
confirmed Off and unknown state. The original cup asset was inspected in a
standalone row fixture screenshot. That fixture does not exercise the full
Settings/picker layout or establish physical/iOS visual parity.

Main and test APKs build successfully. Native ELF/RELRO and 16 KiB ZIP checks pass
on the final main APK; no native library changed. These are the same final APKs
used for the eight UI cases. All emulator processes were stopped afterward.

| Artifact | SHA-256 |
| --- | --- |
| Main debug APK | `e48f6be8449eb44b4d3b6b47ad856284dc78bab7d4e766ac9e5312d85b1faa99` |
| App test APK | `491605f25ad485b5106d8e6a998244e66cf0d88c4e4087c16d1e4ecb39ae12f3` |

Logs, JVM XML, screenshots and artifact hashes are retained in ignored
`captures/runtime/mac-power-indicators/`. The Pixel disconnected from USB during
this work; this APK was **not installed on the Pixel**. Its last verified installed
checkpoint remains `f0dfc7f`. No user appearance, Mac power or phone sleep setting
was changed. Live Pixel/Mac acceptance and restoration of any future test power
changes remain separate gates. Signed published build 157 is unchanged.

## Remaining parity

The full companion goal remains active. Method/direct-route controls, version
compatibility and account-wide Forget/revocation remain separate computer-detail
work. A published signed release and complete physical/visual acceptance are
still required; local debug tests do not establish those outcomes.
