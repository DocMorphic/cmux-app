# Computer connection presentation

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`,
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MacComputerDetailView.swift`,
`connectionSection`, `connectionPhrase`, `isForeground` and `workspaceCount`.

Computer Details now displays **This phone**, **Role** (only for the verified
foreground computer) and **Workspaces**. Settings computer rows also show their
own connection state, including background feed connections. Discovery remains a
separate availability label; seeing a computer in the directory does not mean
this phone is connected to it.

The presentation combines existing verified foreground and feed snapshots. It
performs no RPC, acquires no lease, and does not select a computer. Callers pass
only pairings allowed by the current account/team. Sources must match the exact
saved pairing, and device/build identities never fall back to sibling builds.
Ambiguous duplicate identities produce no connection projection. The selected
computer filter alone does not establish the foreground role.

A verified foreground session supplies its current workspace count. Otherwise,
the exact feed's last confirmed workspace snapshot supplies the count, including
while offline. An unknown count displays an em dash; a confirmed empty list
displays zero. This intentionally avoids presenting an unqueried computer as
having zero workspaces. Connection status updates independently of cached counts.

## Verification

Twenty-six focused JVM tests passed (six connection projection cases, eleven
computer checks and nine feed model cases). They cover exact foreground identity,
background connections, reconnect with retained counts, unknown versus empty
snapshots, sibling builds, stale pairing sources, retired scopes and ambiguous
identities.

Eight Android 17 emulator UI cases passed in **77.372 seconds**: four Computer
Details and four appearance cases. The new case drives connected, reconnecting
and background-only states and verifies the foreground role disappears. Its
screenshot was inspected. Existing independent connection checks, scoped private
addresses, Details-versus-selection behavior and appearance editing still pass.

The first build and UI run used the initial inline Settings row status. A final
row-only adjustment moves each computer's status below its name, so a long status
does not crowd the Details button. Main/test APK builds and native ELF/RELRO and
16 KiB ZIP checks pass on the final artifacts. The generated class signature was
checked to confirm this adjustment was included. The eight-case UI run was not
repeated after this row layout adjustment; the detail/model implementation and
tests are unchanged. No native library changed.

| Artifact | SHA-256 |
| --- | --- |
| UI-tested main APK | `04e83013ef238fbd29e9a17e2bcfb562e361ef18db199d3110ca4af241da742c` |
| Final main APK | `5fa655e710afed89f511c5a595f3b005157350f7f8dc5862f13f092506b1fe6b` |
| Final test APK | `f155ed60ff25e77c52dfff1e9dd7620d0351ee533d4df61a9c1fc3cf2204f313` |

Logs, JVM XML, screenshots and artifact receipts are in ignored
`captures/runtime/computer-connection/`. The emulator was shut down after tests.
The final APK installed successfully on the Pixel and its on-device SHA-256
matched. The foreground notification service was running afterward. The Pixel
was outside cmux, so its UI was not taken over; no user appearance or Mac power
setting was changed and USB-awake remained `0`.
Physical Mac/Pixel acceptance, including status changes across live reconnect,
remains a separate gate. Signed published build 157 is unchanged.

## Remaining work

Keep-awake row indicators are implemented by the subsequent
[power indicators checkpoint](MAC_POWER_INDICATORS.md). Method/direct-route controls,
version compatibility and account-wide Forget/revocation remain separate parity work. This presentation
does not claim completion of the full computer-detail or companion goal.

## Independent Mac connection admission — 2026-10-04

The shared RPC pool previously held one global coroutine mutex throughout dial
and authenticated host validation. A stalled Mac could therefore delay new
connections to every other Mac in that account. A regression reproduced this:
the healthy Mac's acquire timed out after 1,000ms while another dial was gated.

Admission now serializes by connection key. Different Macs can dial and validate
independently; callers for the same key still share one validated wire and own
independent leases. The pool tracks every in-progress candidate so scoped
revocation and account teardown retire all affected wires. The 64-key capacity
includes active connections and pending admissions, and reservations are released
on success, failure or cancellation. No transport work runs under the shared
state lock. Host identity, account/route permissions and compatibility checks
still complete before a candidate can become a borrowed connection.

**50 JVM tests pass:** 13 pool, 21 Iroh runtime and 16 saved-Tailscale runtime
cases. New coverage checks independent dial/probe progress, cancellation of a
same-Mac waiter, successful wire sharing, selective candidate revocation,
closing multiple candidates, and capacity reclamation. Runtime cases use the
production managers with fixture transports: a stalled Mac does not block a
healthy sibling; revoking the stalled Mac's discovery/grant preserves the
sibling's shared lease and subsequent workspace RPC.

The original regression failed before the fix and passes afterward. Gradle's
focused JVM build finished successfully in 22s. Original logs, XML and source
hashes are retained in ignored `captures/runtime/connection-admission/`.
No emulator was started and no APK was built or installed for this pure Kotlin
connection change. Signed 517 is unchanged. ADB listed no physical device;
Pixel/Mac browser/reconnect acceptance and the full production account/network
graph remain unverified by these fixtures.
