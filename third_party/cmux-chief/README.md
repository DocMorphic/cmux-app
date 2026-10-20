# Chief protocol reference

Source: `manaflow-ai/cmux` at
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
Copyright Manaflow, Inc.; GPL-3.0-or-later. See LICENSE and the root NOTICE.md.
`manifest.json` records five wire-source hashes and four separate behavior/artwork hashes. This scoped reference does not
advance the global implemented or reviewed upstream pins.

The Kotlin port covers the Chief capability set, nine RPC request shapes, typed
response defaults, UInt64 sequence counters and shared structured questions,
answers and validation. It uses the existing authenticated MobileRpcClient and
checks the caller's current account/connection authority before transport
admission and after responses. Chief requests omit terminal selection tickets.
An ambiguous write is never automatically resent by this client.

Generate reference vectors using a local Git checkout containing that revision:

```sh
python3 scripts/check-chief-wire.py --upstream /absolute/path/to/cmux \
  --output /absolute/path/to/ignored-evidence
```

The script checks every hash, removes only unavailable external module imports,
and compiles the actual Swift models, coder, request builder and shared question
validation with a local recording RPC stub. No network request is made. The
committed vectors in `app/src/test/resources/parity/chief-wire.json` hold 21 model
cases, nine actual client requests and eight validation cases. Passing
`--vectors` explicitly regenerates a vector file for review.

`NativeChiefModelsTest` compares defaults, strict decoding, large counters and
question validation. `NativeChiefClientTest` exercises the real framed Android
RPC transport, request parameters/timeouts, owner retirement during token lookup,
late responses and cancellation without closing a shared connection. Together
with the existing RPC, attach-ticket and JSON cases, 101 focused JVM cases pass.
Main and instrumentation Kotlin compile.

The initial mounted Home/transcript/question port and retained session state are
now documented in `docs/CHIEF.md`. Twenty-one additional JVM session/form/race cases
and five Android production-composition/root cases pass. The generic conversation
UI comparison, complete lifecycle/restoration, localization/accessibility and real
host acceptance remain open. The installed custom Mac does not advertise Chief;
a supporting real host and Pixel checks are required.
