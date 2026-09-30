# Upstream refresh candidate — 2026-09-30

The implemented reference remains `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.
During signed build 274 verification, the upstream HEAD API returned
[`204a11dfcc76280205e50406ab94270a1c152155`](https://github.com/manaflow-ai/cmux/commit/204a11dfcc76280205e50406ab94270a1c152155),
committed at 2026-09-30 06:57:50 UTC. The
[comparison](https://github.com/manaflow-ai/cmux/compare/4c5272e9153eca2033c9f40ac749f0c3a5bcb291...204a11dfcc76280205e50406ab94270a1c152155)
reports 323 commits ahead. Its files response hit the 300-file limit. The saved
response is a partial inventory; zero change counts in that capped response do
not establish unchanged content. The exact candidate was then fetched into the
partial upstream checkout. A local `git diff --no-renames --name-status` between
the two trees found **3,278 changed paths**, including **285** under `Packages/iOS`,
`Packages/Shared/CMUXMobileCore` and `ios`, plus relevant host files. This complete
path inventory is saved in ignored `captures/runtime/signed274/`; content-level
review is still incomplete. Keep the implemented reference until that review is done.

## Priority review areas observed in the comparison

1. **Terminal input and replay contracts.** Added shared
   `MobileTerminalInputDelivery.swift` and `MobileTerminalInputSender.swift`, changed
   `MobileTerminalInputFrame.swift`, and added shell
   `MobileShellComposite+ExactlyOnceInput.swift`. Inspect host handling and the
   corresponding delivery/sender tests before changing Android retry behavior.
   Preserve the invariant that uncertain terminal input is never blindly replayed.
2. **Event and connection lifetime.** Added `MobileEventLaneScope.swift` and changed
   independent-event, terminal-lane, output-frame and replay-response code. Review
   identity/revision fences and new replay surface tests against Android's existing
   scoped clients, grid and raw replay paths.
3. **Mac browser networking.** Added `BrowserServerRoute.swift`,
   `MobileTunnelLaneConnection.swift` and shell Mac-browser tunnel code. Verify
   advertised host capabilities and endpoint scope before deciding which Android
   browser paths require changes.
4. **Reconnect and Mac switching.** Changes include stored-Mac dialing, zero-touch
   dial races, connection recovery and Mac-switch state, with added discovery and
   recovery tests. Compare these with Android's saved native/Tailscale paths.
5. **SSH surface.** Normal signed-in iOS source paths expose the new
   `CmuxMobileSSH` package, mixed shell/tmux/cmux-tui providers, SFTP and browser
   integration. The source audit and implementation gates are now recorded in
   [DIRECT_SSH.md](DIRECT_SSH.md). Android direct SSH remains unimplemented;
   existing Mac tests do not cover it. Binary shipping status is not established.

Fetch the exact candidate into the existing partial upstream checkout, compare
complete trees locally, and inspect targeted file contents. Avoid broad content
searches that trigger indiscriminate blob fetching. Record source and test evidence
for each conclusion. Preserve the previous reference until this audit is complete.

## First reviewed contract: terminal input delivery

The candidate's `MobileTerminalInputFrame.swift` diff and full
`MobileTerminalInputDelivery.swift`, `MobileHostIrxTerminalLaneServer.swift` and
`MobileHostTerminalInputApplier.swift` were inspected.

- New opt-in capability: `terminal.input.exactly_once.v1`.
- Input frames reserve header bit 30 (`0x40000000`) for a 40-byte delivery identity:
  surface UUID, stream UUID and big-endian UInt64 sequence. The optional latency
  marker remains bit 31; payload length now uses the low 30 bits.
- RPC identities use `input_stream_id` and `input_stream_seq` beside explicit
  `surface_id`. The shared host ledger handles sequence order, duplicate detection,
  terminal identity and applied/busy/unavailable acknowledgements across paths.
- The inspected host applier explicitly returns `proceed` for absent delivery
  identity and emits no delivery acknowledgement for that legacy path. Existing
  input can continue using the legacy format while capability-gated support is
  designed and tested. This finding covers the inspected input path; the remaining
  connection/replay/output changes still need review.
- Android now integrates protocol/outbox/RPC, both ACK lanes and a retained
  production session sender behind freshly negotiated capability support. The sender and envelope codec were subsequently
  inspected, including kind-3 ACK counter conventions and retry/immutability
  requirements. See [TERMINAL_INPUT_DELIVERY.md](TERMINAL_INPUT_DELIVERY.md) for
  generated Swift wire goldens, 97 JVM/nine runtime checks and remaining physical
  acceptance. Composer settlement across recreation subsequently passed its focused
  JVM and Android runtime checks; see the same document.
  Preserve legacy behavior for hosts without the capability.

## Second reviewed contract: terminal event-lane scope

Inspected the candidate’s `MobileEventLaneScope.swift`, its four Swift tests,
`MobileCoreRPCSession+IndependentEvents.swift`, and `IrxServerEventLaneHub.swift`.
iOS stamps a phone-local binary marker before every merged terminal event frame,
then requires that event’s `payload.surface_id` to match the native lane. Markers
never cross the network and cannot be established by received payload bytes.

Android already decodes complete frames independently per native lane. It now
captures the `terminal:<UUID>` resource from `IrxIncomingEvents`, validates each
scoped frame before merging, and refuses wrong/missing terminal IDs, non-event
objects, malformed JSON and forged binary markers. UUID case and surrounding
whitespace follow the reviewed Swift scope semantics. Shared/non-UUID resources
retain their previous unscoped behavior. No marker protocol was added to the wire.

**19 focused JVM tests passed**, including two new scope cases plus existing
fragmentation, overlapping-reader, reader-limit, cleanup and control/event-client
checks. Tests use the native descriptor’s `terminal:` prefix; parsing a bare
resource UUID would not exercise the real transport. This is source/JVM evidence;
no new APK, native QUIC runtime or physical scope check is claimed. The source
fix postdates the signed milestone being built from `0db3c17`.

The related optional-reader failure policy is now implemented too. Malformed or
non-event sidecar payloads are ignored; they cannot settle pending control replies.
An optional reader’s EOF/reset stops its lanes while control RPC and fallback
control events remain usable. A new subscription can restart the reader; concurrent
lease subscriptions share it, while reasserting an existing subscription does not
reopen it. Unsubscribe releases its preparation record. Native disconnection,
control failure and account revocation remain authoritative session closure paths.

The follow-up passed **26 focused JVM tests**, including three new optional-event
cases and the scope, lease and control-client regressions. Tests explicitly wait
for the failed collector job to finish before checking restart, and verify actual
native-disconnection propagation after optional EOF. Neither this policy change
nor the scope filter is in signed 284. Native/physical event-lane acceptance and
broader event/replay/connection review remain open.

## Third reviewed contract: Mac browser networking

The candidate's tunnel wire, native client/host, destination policy, iOS lane
adapter, Mac network/router and per-computer browser route were inspected.
Android now implements the native framed handshake/raw byte client, listing,
half-close and lease-scoped lifetime. **33 focused JVM tests passed**, including
ten tunnel cases using fixtures compiled from unchanged pinned Swift wire files.
See [BROWSER_TUNNEL.md](BROWSER_TUNNEL.md) for exact sources and behavior.

SOCKS/router and asynchronous Android socket code are now implemented too:
18 focused JVM tests pass, including 69 Swift-generated loopback classifications,
plus an Android 17 / 16 KiB binary round-trip/half-close check. Session
capability/owner binding, policy refresh and cancellation now pass 40 focused JVM
tests. The dedicated-process WebView is now integrated into production presentation
with a foreground connection hold and owner retirement. Generated-host Android
checks cover routing/storage, return/reopen, rotation, the system file picker,
address entry and browser-subprocess recovery. The stable Mac lacks the advertised
tunnel capability; a separately staged nightly still needs native pairing and
physical interoperability checks. See `BROWSER_TUNNEL.md` for exact evidence and
remaining acceptance work.
This work postdates signed 284. The broad upstream reference has not advanced.

## Current delivery boundary

The direct SSH audit confirms normal Computers/Settings entry points at this
candidate. Old signed-out entry and per-host persistence-mode plans are superseded.
The phone must sign in, but a remote SSH server needs neither the Mac companion nor
a cmux account. Current source supports key management, trust/changed-key prompts,
multi-hop connections, mixed workspace providers, SFTP and SSH browser routing.
The pinned cmux-tui 0.13.4 installer must not be assumed to contain newer server
geometry/event fixes described in the PRD. See `DIRECT_SSH.md` for sources and
acceptance gates. The opt-in SSH engine experiment subsequently passed five tests
on API 26 and the same five on API 37 / 16 KiB pages, covering opaque Keystore
signing, encrypted imports, strict host-key checks, PTY/exec, SFTP and nested
forwarding teardown. It is separate from the delivered app. Production key/host
storage, connection lifetime and UI integration are next; biometric, physical and
real multiplexer acceptance remain open.

Signed build 284 is from Android commit
`0db3c178d69facb8468d62e6bca42e951d34c2b1`. It includes the retained identified-input
and composer work as well as the previous workspace/startup/recovery changes.
Full CI, downloaded signature/alignment and emulator upgrade/launch checks passed;
see `PIXEL_INSTALL.md`. The event-lane scope and optional-reader source fixes
postdate 284. None of this establishes full parity with the new upstream candidate.

The local installed Mac app's Info.plist reports version **0.64.25**, build **106**.
The source-commit mapping remains unverified. The Pixel subsequently received
debug `c763e2d` in place and passed live composer/direct input and saved-state
process recovery checks; see `PIXEL_INSTALL.md`, including its rapid input-mode
transition observation. The installed host's identified-input capability and
retry/deduplication behavior have not been established by that check.

## Other confirmed implementation follow-ups

- Changes now retains the selected path and collapsed folders above the RPC store,
  scoped to login/account/team/Mac/build/workspace. Fresh inventory validates the
  selected path before refetching its diff; a vanished file cannot select its old
  neighbor. See `WORKSPACE_SELECTION.md` for verification. Diff scroll, expansion
  and preview-overlay restoration remain follow-up work.
- Terminal Files visibility, tapped path, nested folders, selected preview page
  and gallery controls now use scoped saved navigation state. The newly admitted
  client scans the terminal before reusing any session route; content is refetched.
  Fourteen JVM and six Android checks passed, including real task restoration and
  a regression for stale session reads during flow replacement. See
  `WORKSPACE_SELECTION.md`. Scroll/zoom/document selection, oversized saved-state
  fallback and physical acceptance remain open.

After the protocol audit, continue these detail-state fixes, interrupted creation,
physical acceptance, accessibility/performance and background delivery. Server
push/FCM and the remaining limitations remain tracked in `PARITY.md`.
