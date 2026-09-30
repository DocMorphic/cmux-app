# Identified terminal input — protocol, lanes and session sender

Checkpoint: 2026-09-30. Source contract:
[`204a11dfcc76280205e50406ab94270a1c152155`](https://github.com/manaflow-ai/cmux/commit/204a11dfcc76280205e50406ab94270a1c152155).
The protocol, lanes and retained sender are now integrated into the production
native session. Each freshly admitted connection negotiates the capability;
hosts without it retain legacy input behavior. Verification passed **97 focused
JVM tests and nine Android runtime checks** on API 37 / 16 KiB pages. These include
synthetic deduplicating hosts and real JNI/Iroh lanes. Physical Mac acceptance
of the new capability is still outstanding. Signed 274 predates this work.

## Reviewed upstream contract

- `MobileTerminalInputDelivery.swift`: surface UUID, stream UUID and positive
  UInt64 sequence; seven acknowledgement statuses; host ledger and phone outbox.
- `MobileTerminalInputFrame.swift`: header bit 30 selects the 40-byte identity,
  after any optional latency marker selected by bit 31. Payload length uses the
  low 30 bits. Text remains bounded to 16 KiB UTF-8.
- `MobileTerminalInputSender.swift`: per-host/terminal ownership, lane/RPC ordering,
  acknowledgement settlements and bounded retries.
- `CmxIrohTerminalOutputEnvelope.swift` and its codec: **CMXT kind 3** carries a
  34-byte acknowledgement body, with retained=0, sequence=0 and current=34.
  The transport-neutral mobile output object uses different counter conventions;
  binary frames must follow the envelope codec.
- `MobileHostIrxTerminalLaneServer.swift` and `MobileHostTerminalInputApplier.swift`:
  verify the target terminal, deduplicate/order identified input, and retain
  explicit legacy behavior when identity is absent.

## Android foundation

`TerminalInputDelivery` serializes the UUIDs in network byte order and the sequence
as an unsigned 64-bit value. Adding its RPC parameters requires the same explicit
`surface_id`; it cannot overwrite another identity. Internal overloads on
`MobileRpcClient` now support metadata on input, paste, image paste, mouse and
scroll calls. Their public overloads now dispatch through the foreground session when bound;
unbound clients retain legacy requests.
No request replay is added to the control channel.

`TerminalInputAcknowledgement` handles all seven binary/RPC statuses. RPC numbers
are decimal UInt64 strings. Missing `input_ack` is represented separately from
malformed, unknown-version, invalid-UUID or overflowing acknowledgement data; the
sender must never treat malformed identity as a successful downgrade.

`TerminalLaneProtocol` supports identity-bearing input frames and has an explicit
opt-in acknowledgement decoder. Native lanes bound to a canonical terminal UUID
now decode acknowledgements after accepting their initial replay. Input-only
lanes expose a bounded, non-dropping acknowledgement flow; duplex owners route
acknowledgements separately from the renderer. Acknowledgements cannot advance
the output cursor or enable a lane before its replay. Missing handlers refuse
identified sends before a write, and unexpected acknowledgements retire the
optional lane. Closed/paused owners reject late callbacks. Decoded acknowledgements
carry no renderable text. Framing requires the fixed body length and expected
zero retained/sequence counters.

Both lane owners expose `sendIdentified`: false means no write, while uncertain
writes throw. A successful write does not wait for its acknowledgement, allowing
the retained sender to pipeline input. These APIs alone do not negotiate the host
capability or apply acknowledgement stream/sequence rules; the session sender
must perform those steps before enabling them in the UI.

`TerminalInputOutbox` retains ordered units until confirmed. It supports cumulative
applied/duplicate acknowledgement, gap rewind/rebase, busy retry, rejected-unit
reporting, terminal-unavailable settlement and same-target mismatch recovery.
Wrong-stream, stale-negative and impossible future acknowledgements cannot clear
newer or unsent input. Limits are 16 MiB and 4,096 units per outbox; overflow refuses
new input while retaining older units. Owner admission, immutable payload snapshots,
thread confinement, retry timing and disposal belong to the session sender.

Once a unit has been sent, `everSent` permanently prevents payload merging under
that identity, including after a rewind or rebase. The reviewed Swift outbox's
merge condition checks its current `isSent` flag, which becomes false on rewind;
Android deliberately keeps a stronger immutability invariant to avoid merging new
keystrokes into an identity the host might already have applied. New unsent units
may still merge before their first send.

## Scoped session sender

`TerminalInputSender<P>` now owns the delivery loop. Its key has separate login
incarnation, user, team, canonical Mac/build and terminal UUID fields. The caller
must construct that key from admitted account/host state and supply immutable
payloads. It is confined to the session dispatcher; bindings, submissions and
acknowledgements must use that same dispatcher.

- Each freshly admitted transport receives a binding token. Replacing or closing
  it cancels the old pump, preserves pending identities and fences late sends and
  acknowledgements. Closing an older token cannot close the replacement.
- Lane input is pipelined. RPC waits behind earlier unacknowledged units; failure
  rewinds with the original identity, including when falling back from lane to RPC.
- Missing acknowledgements and sender write attempts have five-second deadlines.
  A started control frame finishes under the RPC client’s non-cancellable, bounded
  15-second write deadline to avoid corrupting the shared stream; its completion
  can therefore delay the outer timeout. Both absent
  bindings and unavailable paths have a 30-second limit. Retry delays are 250/500 ms;
  three consecutive failures without delivery progress settle and pause the stream.
  Busy/gap/mismatch replies and connection flapping cannot reset that budget.
- Only real delivery progress resets retry accounting. The caller gets delivered,
  undeliverable or abandoned tickets plus pending-byte/unit and failure state.
  A prior ambiguous write remains uncertain even if a later attempt is refused.
- The optional merge function combines only never-sent payloads and settles every
  merged ticket. Rewind/rebase never make an already-written identity mutable.
  Byte/unit limits remain enforced; pending tickets are independently capped at
  4,096 and retained terminal slots at 64. Idle unbound slots may be evicted.
- Capability loss abandons pending input instead of replaying it as legacy input.
  A confirmed unidentified success settles that unit but blocks later sends and
  invalidates the binding's identity support. A fresh negotiation is required.
- Owner retirement/close abandons pending tickets without transferring payloads.
  Explicit recovery starts a new stream for new input; abandoned payloads are
  never resubmitted. The session owner must close the sender before its scope.

## Production session integration

`NativeFeedSession` retains `NativeTerminalInputSession` across Activity recreation.
Admission captures login, account/team, canonical Mac/build, workspace and terminal;
queued operations never resolve their destination from a later UI selection.
Reconnect rebinds pending identities to the newly admitted client. Old-client lane
cleanup and late callbacks cannot close or acknowledge the replacement. Account,
Mac or terminal retirement abandons the old owner’s pending input.

All five public input methods share the sender. Payloads are immutable snapshots,
including copied image bytes. The retained queue reserves asynchronous media work
before later keys, mouse or scroll; RPC waits behind unacknowledged lane input.
Scroll responses retain their render-grid data. Both native lane types deliver
ACKs without sending them to the renderer. Advertised identity support with an
invalid terminal UUID or exhausted record capacity refuses input instead of
falling through to legacy delivery.

Typing and composer admission observe sender/queue failures. The terminal shows
an explicit Resume typing action, or requests reconnect when identity support was
invalidated. Resume starts new input and never resubmits abandoned payloads.
For hosts using the retained queue, the queue also owns composer delivery and
draft settlement. Activity recreation cannot cancel its observer and leave a
confirmed send falsely unconfirmed. Each subsequent image/text/file operation
resolves an admitted connection for the original owner; it cannot use another
Mac or terminal. The original operation identifier protects newer draft edits and
account replacements from late completion. Pending sends are persisted before
transmission; queue discard releases their reservation with a visible warning.

Legacy hosts keep conservative delivery: an uncertain operation is not replayed
without host deduplication support. Interrupted file-upload chunks also remain
conservative; this change does not automatically resume a chunked upload.

## Reproducible verification

`generate-terminal-input-delivery-fixtures.py` compiles the actual pinned upstream
Swift definitions in a temporary module. Only their internal module import is
removed; the type definitions are unchanged. Swift generates the legacy, marked,
identified and combined input frames, all acknowledgement bodies/RPC payloads,
and CMXT acknowledgement envelopes. The resulting JSON is a committed JVM test
resource, including Unicode text and UInt64-max sequence/marker values.

```
python3 scripts/generate-terminal-input-delivery-fixtures.py /path/to/cmux
```

**56 JVM tests passed in the initial foundation batch**: six Swift-wire-golden/validation cases, eleven outbox
cases, four identified/legacy RPC cases, and 35 existing modifier, queue, input
lane, output lane, frame and control-RPC regressions. The RPC fixtures exercise all
five writing methods, reject a wrong terminal before any write and verify that an
ambiguous write is attempted only once by the control client. Golden envelope
checks cover every chunk split, all seven statuses and non-renderable ACK bodies.
No APK or signed build was produced for this foundation. Ignored evidence is in
`captures/runtime/input-delivery/`. Native identified-delivery and physical workflow
acceptance remain open.

The subsequent lane integration batch passed **64 JVM tests**, including eight
new lane/owner tests. Compiled Swift acknowledgement envelopes exercise buffered
bursts beyond the 64-item flow capacity, byte fragmentation, all duplex envelope
splits, target mismatch, replay readiness, malformed/truncated input, pipeline
writes, ambiguous-write propagation, renderer/cursor separation and late callback
rejection. These are in-process wire fixtures; no native or physical identified
input check is claimed. Signed 274 and the installed Pixel APK are unchanged.

The session-sender batch passed **84 JVM tests**, including 20 new virtual-clock
sender checks plus the 64 protocol, queue, lane and RPC checks. A deduplicating
peer proves a dropped ACK causes repeated writes under one identity but one host
application. Checks cover RPC ordering, negative replies, write/ACK/offline
deadlines, reconnect budgets, late binding results, owner isolation, host-ledger
rebase, capability downgrade, immutable merges and explicit recovery. The initial
15-case run caught a redundant unavailable-lane probe caused by a stale wake-up;
the final batch includes that corrected ordering case. These remain synthetic
transport checks; no new APK or physical identified-input claim is made.
Evidence: ignored `captures/runtime/input-delivery/sender/`.

## Production integration verification and remaining acceptance

The integration batch passed **97 JVM tests**, including nine session-adapter
checks for all five RPC methods, image snapshots, old-client fencing, reconnect,
mouse/media ordering, owner isolation, downgrade recovery and invalid identities.
Debug and instrumentation APKs built successfully.

On the Android 17 / API 37 emulator with 16 KiB pages, **nine runtime tests passed
in 61.466 seconds**:

- Dropped RPC reply plus Activity recreation retained the exact identity and
  produced one application in the fixture host’s deduplication ledger.
- Busy replies stopped after the bounded retry budget; explicit Resume typing
  used a new stream for fresh input. Paused/recovered screens were inspected.
- Identified ACKs passed over real JNI/Iroh input-only and duplex lanes, with
  fragmented ACK frames and a usable independent control channel.
- Existing native lane/control, direct keyboard/target-switch, image-before-keys,
  and terminal mouse/scroll checks passed.

Debug APK SHA-256:
`1dc620de99ee7ea5646c1be581f7742e889414a6b2ed267e0c402cfdb21267cd`.
Instrumentation APK SHA-256:
`e74ba98fb115da40b8a6225fc3d9808e25a614160b63e9780b85eb54353e1462`.
Evidence is in ignored `captures/runtime/input-delivery/session/`. Account-clearing
instrumentation runs only on the emulator. These results do not establish the
installed Mac’s advertised capability or physical identified-input acceptance.

### Composer lifecycle follow-up

The follow-up passed **32 JVM tests** across the composer queue, draft repository
model, delivery, input queue and session adapter. Five new cases cover observer
cancellation, newer edits, owner retirement, failed persistence, late account
completion and original-owner connection resolution (the observer case also
checks ordering). Debug and instrumentation APKs built successfully.

**Six Android runtime tests passed in 366.801 seconds** on the API 37 / 16 KiB
emulator. Two new cases drop a text or image reply, recreate the actual Activity,
then verify identical retry identities, deduplication-ledger counts, image-before-
text ordering and settled drafts. The text case also checks persisted cleanup;
the recovered composer screenshot was visually reviewed. The earlier identified
typing/retry tests and two legacy composer rejection/reconnect checks also passed.

The emulator displayed a System UI service ANR during the first case. Choosing
Wait allowed the **same run** to resume and finish; no test or APK was restarted.
This is functional acceptance under an interrupted emulator run, not clean timing
or performance evidence. The owned emulator was stopped after completion.

Debug APK SHA-256:
`70f9ec6c0f532ac87e1e37980a08261dc7424f1530c2fd46c56308e4f360b244`.
Test APK SHA-256:
`eadf39a3d41726487172f1d06842a61d21813a3570831222f94aa8121fc0ff20`.
Ignored evidence: `captures/runtime/input-delivery/composer/`. Signed 274 and the
Pixel's installed `c763e2d` APK predate this composer follow-up.

Still required: physical Mac/Pixel acceptance and broader host restart,
capability downgrade and partial-write runtime
acceptance. Existing deterministic tests cover these protocol failure mechanisms;
they are not a substitute for live end-to-end evidence.

The repository-wide upstream refresh is still incomplete; see
[UPSTREAM_REFRESH_2026_09_30.md](UPSTREAM_REFRESH_2026_09_30.md).
