# Identified terminal input — protocol and queue foundation

Checkpoint: 2026-09-30. Source contract:
[`204a11dfcc76280205e50406ab94270a1c152155`](https://github.com/manaflow-ai/cmux/commit/204a11dfcc76280205e50406ab94270a1c152155).
This implements the wire types, bounded outbox and RPC overloads needed for
`terminal.input.exactly_once.v1`. **Live capability-gated sending, acknowledgement
routing, reconnect retry and UI settlement are still to be integrated.** Existing
production callers continue the legacy input path. Signed 274 and the Pixel's
installed debug `1d5958f` predate this foundation.

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
scroll calls. Their existing public overloads produce unchanged legacy requests.
No request replay is added to the control channel.

`TerminalInputAcknowledgement` handles all seven binary/RPC statuses. RPC numbers
are decimal UInt64 strings. Missing `input_ack` is represented separately from
malformed, unknown-version, invalid-UUID or overflowing acknowledgement data; a
future sender must never treat malformed identity as a successful downgrade.

`TerminalLaneProtocol` supports identity-bearing input frames and has an explicit
opt-in acknowledgement decoder. Legacy lane readers keep their existing decoder
policy until capability/owner-aware acknowledgement dispatch is wired. Decoded
acknowledgements carry no renderable text. Acknowledgement framing requires the
fixed body length and expected zero retained/sequence counters.

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

**56 JVM tests passed**: six Swift-wire-golden/validation cases, eleven outbox
cases, four identified/legacy RPC cases, and 35 existing modifier, queue, input
lane, output lane, frame and control-RPC regressions. The RPC fixtures exercise all
five writing methods, reject a wrong terminal before any write and verify that an
ambiguous write is attempted only once by the control client. Golden envelope
checks cover every chunk split, all seven statuses and non-renderable ACK bodies.
No APK or signed build was produced for this foundation. Ignored evidence is in
`captures/runtime/input-delivery/`. Native identified-delivery and physical workflow
acceptance remain open.

## Required integration before enabling the capability

1. Put the sender/outboxes under the scoped session lifetime, keyed by login,
   account/team, canonical Mac/build and terminal UUID. Rotation/reconnect retain
   pending units; sign-out, owner retirement and disposal abandon them. Never
   route queued input to whichever terminal happens to be selected later.
2. Snapshot every payload before sending. Preserve order across typed input,
   paste/image operations, composer submission, mouse and scroll. An RPC must not
   overtake unacknowledged lane units.
3. Negotiate `terminal.input.exactly_once.v1` per freshly admitted connection.
   Start identified input only for a valid terminal UUID and supported host.
   Capability loss after an uncertain write must not trigger blind legacy replay.
4. Route kind-3 acknowledgements from both input-only and duplex lanes without
   advancing the terminal output cursor. Enforce stream and current-owner checks
   before applying them. Waiting for an acknowledgement needs a bounded timeout.
5. Retry busy/ambiguous transport outcomes with the same identity and bounded
   attempts. Reset retry counters on actual progress, so repeated busy replies
   cannot loop indefinitely. Handle refusal, disappearance and abandonment visibly.
6. Exercise dropped acknowledgements, busy bursts, partial writes, lane-to-RPC
   failover, host restart, capability downgrade, Activity recreation, Mac/account
   switching and late callbacks using a deduplicating fixture. Then verify on the
   actual Mac/Pixel before claiming delivery parity.

The repository-wide upstream refresh is still incomplete; see
[UPSTREAM_REFRESH_2026_09_30.md](UPSTREAM_REFRESH_2026_09_30.md).
