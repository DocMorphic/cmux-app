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
5. **SSH surface.** The comparison includes a new `CmuxMobileSSH` package, SFTP,
   shell SSH providers, tmux control and browser integration. Establish which
   features are wired into the shipped iOS UI and which host capabilities they
   require, then add the verified requirements to `PARITY.md`. SSH parity has not
   been established by the current Android work.

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

## Current delivery boundary

Signed build 284 is from Android commit
`0db3c178d69facb8468d62e6bca42e951d34c2b1`. It includes the retained identified-input
and composer work as well as the previous workspace/startup/recovery changes.
Full CI, downloaded signature/alignment and emulator upgrade/launch checks passed;
see `PIXEL_INSTALL.md`. The event-lane scope and optional-reader source fixes
postdate 284. None of this establishes full parity with the new upstream candidate.

The local installed Mac app's Info.plist reports version **0.64.25**, build **106**.
The source-commit mapping remains unverified. The Pixel subsequently received
debug `1d5958f` in place and passed live composer/direct input, process recovery
and Markdown preview checks; see `PIXEL_INSTALL.md`. That APK predates the new
identified-input contract and does not validate it against the installed host.

## Other confirmed implementation follow-ups

- `NativeChangesView.kt`: selected file and collapsed folder state use
  `remember(store)`, so a recreated RPC store loses that detail selection.
- `NativeScreen.kt`: terminal Files visibility and tapped artifact path use
  `remember(draftTarget, client)`, so a new connection discards those overlays.
- `ArtifactFilesSheet.kt` and `ArtifactFilePreview.kt`: inspect nested route,
  query/filter/sort, pager and scroll state together. Save bounded identifiers,
  revalidate ownership and refetch through the newly admitted RPC client.

After the protocol audit, continue these detail-state fixes, interrupted creation,
physical acceptance, accessibility/performance and background delivery. Server
push/FCM and the remaining limitations remain tracked in `PARITY.md`.
