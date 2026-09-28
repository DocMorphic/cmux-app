# Current Mac connection prerequisite — 2026-09-28

## Live observation and correction

On the original Mac, cmux **0.64.25** was inspected. The user explicitly approved
enabling native mobile pairing. **Enable iOS pairing** was turned on and the
panel reached **Iroh Ready**. The old approval restriction is resolved for this
action. Existing notification/folder permissions were not changed.

Settings still says **Show Tailscale QR**, but opens an Iroh-only panel with no
QR/manual TCP route. This matches the already-pinned source
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`:

- `Sources/Mobile/Pairing/MobilePairingModel.swift`: active v2 leaves attachURL
  and tailscaleLines empty and sets `v2Only`.
- `Sources/Mobile/Pairing/MobilePairingView.swift`: selects account-based Iroh
  instructions for v2Only. Legacy QR UI remains in the file.
- `Sources/Mobile/MobileHostV2Installation.swift`: current backend/identity setup.

**The earlier Tailscale-first acceptance plan was incorrect for this host.** A
retained QR codec is not proof the current host exposes it. Iroh is the first
native connection milestone, not a post-release enhancement. Existing Android
UI/RPC work remains useful, but neither its TCP fixtures nor build 157 has
established current Mac compatibility. Do not downgrade/patch the Mac or expose
a legacy listener as a shortcut and call that current iOS parity.

## Active source map

The active package is **`Packages/Shared/CmuxIrxTransport`** (note **Irx**).
`CmuxIrohTransport` retains some stable codecs/types and an older runtime;
porting its older runtime alone would miss the current path.

Read at the pin above:

1. `ios/cmuxPackage/Sources/cmuxFeature/MobileIrohV2Configuration.swift` and
   `MobileIrohV2InstallationStore.swift`: backend scope, own app namespace,
   installation ID, scoped Ed25519 identity and persistence.
2. `MobileIrxRuntimeComposition.swift` and `+Lifecycle`, `+Directory`, `+Dial`,
   `+Streams`: authenticated team ownership, epoch guards, discovery, sessions,
   lanes, sign-out and cancellation.
3. In `Packages/Shared/CmuxIrxTransport/Sources/CmuxIrxTransport`, read
   `V2/V2ControlService*`, `V2/V2WireSigningCodec.swift`, and
   `ControlPlane/V2WireModels.swift`: WebSocket/HTTP recovery, single-use
   enrollment challenge, signed proofs, directory revisions, relay credentials,
   and revocation. Then `IrxProtocol.swift`, `IrxAdmission.swift`,
   `IrxConnection.swift`, `IrxEndpoint.swift`, `IrxControlByteTransport.swift`,
   `IrxEventLaneIO.swift`, `IrxSurfaceEventLanes.swift`.
4. Worker `workers/iroh-v2/src/contracts`, `crypto.ts`, `rules.ts`, auth and
   registration handling. Mac admission is a separate authority check.

Confirmed contracts:

- QUIC ALPN **`cmux/irx/1`**, not legacy `cmux/mobile/1`.
- Control: 4-byte big-endian length + JSON, max **256 KiB**. Each stream starts
  with a versioned descriptor: control, keepalive, events, terminal,
  terminal_input, artifact, simulator_stream, or control_repair.
- Client opens control and sends version/protocol hello. Server admits against
  its current device list bound to the QUIC-authenticated key. Older optional
  grant fields do not replace this check. Denials close with `irx:<code>`.
- Admission timeout is 5 seconds. Missed application pongs retire a diagnostic
  stream; they do not alone justify input replay or declaring the session dead.
- Endpoint key and enrollment key represent the same installation. Never copy
  the Mac's private identity into Android.
- Production bootstrap: `https://cmux-iroh-v2.debussy.workers.dev`;
  WebSocket `/v2/control/socket`; `x-cmux-v2-setup` is base64url canonical JSON.
  Initial auth uses the user's Stack bearer token, cached scoped tickets use
  `IrohTicket`. No account data has been sent during this research/codec work.
- **Pinned Worker platform enum permits only mac / ios.** Establish Android
  enrollment compatibility before production requests; do not assume `android`
  is accepted or impersonate an official app namespace. Our app ID stays
  `io.github.docmorphic.cmuxapp`, with separate debug scope. Determine whether
  upstream support or a documented compatibility role is required.

## Reusable native dependency

Exact cmux dependency: `manaflow-ai/iroh-ffi`, tag **v1.2.0-cmux.1.ios17**, commit
`ee19f156667ca640b912108a45f8b5bb8d156fec`. Cloned/verified at
`/tmp/cmux-iroh-ffi`. It includes generated Kotlin/JNA bindings, Android context
initialization and Android build instructions. The release publishes only an
Apple xcframework; Android native binaries still need to be built.

Cargo requires Rust >=1.91 / edition 2024. Preserve Cargo.lock, the Iroh fork
`9816d2505ddd9c62a4c847be7c06b404175af5c4`, and noq fork
`05055f887961418285a39a41da72c5b6e738cbaf`. Do not substitute an arbitrary Maven
artifact. Upstream Kotlin uses JNA 5.15.0 and JDK 21 for its library build; our
app remains JDK 17. Investigate generator/runtime compatibility before changing
the app Java level. Rust/Android NDK were not installed here at audit.

Research excerpts: `/tmp/cmux-irx-audit`; upstream sparse clone:
`/tmp/cmux-official-source`. Recreate missing excerpts with exact `git show PIN:path`.

## Implementation sequence and actual progress

1. **Added:** `IrohV2SigningCodec` produces canonical enrollment/request bytes:
   UTF-16 sorted keys, exact Unicode/unescaped slashes, safe integer rules,
   base64url, invalid Unicode rejection. Four focused JVM tests pass; three
   unchanged official Worker proof vectors match byte-for-byte and their
   Ed25519 signatures verify with the JDK test provider. This does not implement
   an Android signing provider, persistent keys, networking, or enrollment.
2. Build exact Android arm64 native library/bindings reproducibly; initialize
   context and own lifetimes; test local QUIC/admission. Include required other
   ABIs at release. Never ship missing native libraries.
3. Implement Keystore-protected installation keys and account/team/build scope;
   enrollment/control requests, directory/revocation persistence; resolve the
   platform-metadata compatibility question above.
4. Put `MobileRpcClient` behind a cancellable byte transport preserving TCP
   fixtures. Integrate admitted control plus separate events/terminal/artifact
   lanes, host identity validation, and reconnect without uncertain input replay.
5. Add account-based computer discovery UI and run actual Pixel/Mac acceptance.
   Legacy pairing remains scoped to hosts that expose it.
6. Publish a combined signed APK with existing signer and increasing version.

Pixel viewer fixtures, signing vectors and QUIC loopback each prove only their
own layer. A complete native connection has not been demonstrated.
