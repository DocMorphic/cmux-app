# Mac Power / Keep Mac Awake

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`:

- `MobileShellComposite+Caffeine.swift`, `MobileCaffeineStatus.swift`,
  `MobileCaffeineSettingsContent.swift`, and `SecondaryMacSubscription.swift`.
- `Sources/TerminalController+Caffeine.swift` and `MobileHostService.swift`.

## Contract and implementation

Computer Details has a **Mac Power / Keep Mac Awake** control. It borrows only an
existing connection for the current account/team and exact directory
endpoint/record/device/build. Opening Details does not dial or select a Mac.
The host identity is checked before inspecting `caffeine.control.v1`. A
disconnected Mac shows connection guidance; an unsupported Mac shows update
guidance. Unknown state shows loading or Retry, never an actionable Off switch.

The setting controls cmux's process-scoped idle-system-sleep assertion. The Mac's
display can still turn off. `caffeine.status` and `caffeine.set` return a strict
Boolean `enabled`; the returned value is authoritative even if it differs from
the requested value. Requests have five-second deadlines.

While a set is pending the switch is optimistic and disabled. A per-session
operation gate and a shared per-Mac mutation gate prevent concurrent sets. A
failed, timed-out or malformed set response makes the value unknown and triggers
one bounded status read. The set is never automatically resent. Failed
reconciliation exposes a read-only Retry action.

Each visible session owns a `caffeine.status.changed` subscription. Stream IDs
isolate events, strict Boolean parsing rejects malformed payloads, and revisions
prevent old read/set replies from overwriting a newer event. A ten-second status
backfill recovers dropped events. Subscription retry uses the same stream ID;
the host's subscription implementation is explicitly idempotent per stream ID.

Account/directory revocation and connection replacement invalidate the session.
Leaving the page or backgrounding the Activity cancels operations, clears the
optimistic state, attempts a bounded unsubscribe and releases only its lease.
The next visible session starts with a fresh identity/capability/status read.

## Verification (2026-09-29)

The final build passes 38 focused JVM tests: nine Mac Power cases, six shared
connection-pool cases, ten per-computer cases and thirteen account/runtime cases.
Power coverage includes unsupported/mismatched hosts, strict Boolean payloads,
duplicate taps, authoritative differing replies, lost/malformed set responses,
failed/opposite-state reconciliation, read-only retry, stale replies versus events,
other streams, revocation, shared mutation admission, subscription retry and
disconnect cleanup.

All six Android 17 arm64 emulator UI cases pass in **22.053 seconds**: three
power-control cases plus the three existing Computer Details cases. Confirmed
and unknown-state fixture screenshots were inspected. The first emulator run
froze before finishing its first test; the emulator reported hung QEMU CPU/main
threads and was terminated. The successful rerun used the same APKs with
1536 MiB RAM and two cores instead of the default 2 GiB/three cores. Both logs
are retained; the interrupted run is not a pass.

Main/test APKs built, and main APK native ELF/RELRO and 16 KiB ZIP checks passed.
No native library changed. The main APK was installed successfully on the Pixel
over USB. The phone remained asleep, so real-Mac status/toggle/event round-trip,
background/resume and restoration of the Mac's original keep-awake setting are
**not yet physically verified**. No phone sleep or Mac power setting was changed.
The Pixel's USB-awake setting remains `0`. Published signed build 157 is unchanged.

| Artifact | SHA-256 |
| --- | --- |
| Main debug APK | `6cf3036991277a434a9e8d751bb905f6326b2f189750b5d636f3bd82a07e040d` |
| App test APK | `fa73a31bd7b219eb5b7e3713258c85e0e330b41cd26e5e046f1810e58b5fc15c` |

Build logs, XML, screenshots, alignment output and device receipt are retained in
ignored `captures/runtime/mac-power/`.

## Remaining broader parity

This checkpoint implements the detail control. The subsequent
[computer indicators checkpoint](MAC_POWER_INDICATORS.md) seeds and observes
keep-awake state for connected UI feeds and renders it in computer rows. Local
appearance and connection role/count presentation are also implemented in later
checkpoints. Live acceptance, direct-only routing and account-wide Forget/revocation
remain open work. See
[COMPUTER_DETAILS.md](COMPUTER_DETAILS.md) and [PARITY.md](PARITY.md).

## Saved Tailscale controller reuse (2026-10-06)

Scoped saved Tailscale Details and onboarding now reuse the verified feed-owned
power controller without dialing or owning its run loop. Focused tests cover
single-connect reuse and revocation fencing; UI/physical acceptance remains queued.
See [Computer Details](COMPUTER_DETAILS.md) for checks and remaining scope.
