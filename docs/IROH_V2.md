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
initialization and Android build instructions. The upstream release publishes only an Apple xcframework; our pinned Android
arm64 checkpoint is now built and verified below.

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
   Ed25519 signatures verify with the JDK test provider. The Android native signing provider and protected scoped keys are now verified
   below. Control-service networking and enrollment remain to be integrated.
2. **Verified:** exact Android arm64 native library/bindings, context setup,
   native signatures, local QUIC and client admission. Include required other
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

## Android native module

`:iroh` is now a dependency of `:app`; app builds require its checked native
artifact. It uses the matching generated Kotlin
bindings, JNA 5.15.0 **Android AAR**, JDK 17, API 26 minimum and arm64 only.
`IrohRuntime.initialize` installs the application context once before endpoint
creation. Library pre-build verifies the pin, NDK, ABI and every receipt hash;
missing or mismatched artifacts fail instead of silently creating an unusable AAR.
The AAR includes the upstream MIT/Apache notices and build receipt.

Reproduce the native artifact by dispatching `Android build` on the working
branch with `native_only=true`, or running `scripts/build-iroh-android.py` with
the exact source/NDK. Download **a reviewed successful run**, not an arbitrary
latest artifact, to `build/iroh-android`:

```sh
gh run download RUN_ID --name cmux-iroh-android-arm64 --dir build/iroh-android
./gradlew :iroh:assembleDebug :iroh:assembleDebugAndroidTest
./gradlew :iroh:connectedDebugAndroidTest
```

Run only with the intended device attached. Native tests are entirely local:
three official signing vectors (plus altered-message rejection), and two
sequential bidirectional QUIC streams using `cmux/irx/1`, peer-key validation,
minimal endpoints, explicit loopback sockets and disabled port mapping. This
checks FFI/TLS/streams, **not** V2 directory enrollment or Mac admission.
The recorded native device results below verify the isolated module. Main debug
APK packaging is now checked separately below. Further ABIs and the integrated
app workflow still need verification before a complete signed release.

### Platform compatibility audit

At the pinned source, `storage/team-store.ts` confirms this is more than a UI
label: `listDirectory` grants mobile inbound peers only when `platform == ios`.
The current schema rejects `android`, and changing only that enum would still
omit Android from the Mac's inbound-peer directory. A supported upstream change
must cover schema/storage types, inbound rules, Swift directory decoding and
admission policy. No request claiming an iOS identity has been sent. An Android
client keeps its independent namespace and must not silently impersonate an
official installation.

### Native build checkpoint — 2026-09-28

GitHub run **36416151322**, source `82cf4c7`, successfully built the exact arm64
native library and matching Kotlin bindings. Artifact `cmux-iroh-android-arm64`,
ID `10967356904`, archive digest
`sha256:05ad2b78a647ed914989ae7150476dd43f8af222aab2a9dd20a823c027ffd002`.
Native library SHA-256:
`339ce4fe9ca83892a59fe1b3c214fc7ab9c7001be97486bf96113cbbf6a4f9e1`.
Generated Kotlin SHA-256:
`8be5c2349a2101dd59c8cf81a4b287ce964d8adfb93020adb93ab485fd679acd`.

The local JDK 17 module/AAR and instrumented APK build passed in **51 seconds**.
The missing-artifact check also fails with an actionable error. Test APK SHA-256:
`24ee198c4c3b2bbb8b924089db831a9df464e8a0f6830de9f9f93a040759e209`.
`zipalign -c -P 16` passed. Packaged arm64 ELF LOAD alignment is 16,384 for Iroh
and 65,536 for JNA; the actual Pixel uses 4,096-byte pages, so this packaging
check is not a runtime claim on a 16 KiB device. Build and packaging evidence:
`/tmp/cmux-iroh-android-build.log`, `/tmp/cmux-iroh-apk-verification.json`,
`/tmp/cmux-iroh-zipalign.log`. No new signed main-app APK was published.

The initial installation reached Play Protect while the screen was off. After
the user requested a new prompt, ADB installation succeeded; Play Protect was
not disabled. On the physical Pixel 6a, the two original native tests returned
**OK (2 tests), 0.688 seconds**. They verified all three official Worker signatures,
altered-message rejection, authenticated loopback peer keys, negotiated ALPN,
and two independent bidirectional QUIC streams. Original APK hash is above;
raw evidence: `captures/iroh/native-checkpoint/instrumentation.txt`.


### Client admission and wire framing — 2026-09-28

`IrxWire` and `IrxClientSession` now implement the current control descriptor and
grantless hello/admit exchange, bounded unsigned frame lengths (256 KiB), exact
UInt64 cursor serialization, strict UTF-8, serialized reads/writes, expected
remote-key and ALPN checks, a five-second deadline, and attributed native-close
codes with upstream automatic-redial classification. Raw application bytes after
the handshake remain untouched; input is never replayed. Post-admission remote
stream credit matches iOS (`bi=0`, `uni=40`) for shared/per-terminal events and
replacement headroom. The future endpoint supervisor must bind with zero initial
remote credit and deferred NAT traversal before passing connections to admission.

Five focused Android cases returned **OK (5 tests), 5.890 seconds** on the physical
Pixel. Cases cover fragmented/coalesced admission + following raw bytes, a wrong
authenticated-directory key, remote revocation, a native stalled read closed at
the five-second deadline, and frame bounds/EOF/version/UInt64 handling. The first
run failed JUnit initialization because one Kotlin expression-body method inferred
an exception return type; explicit `Unit` fixed the runner signature. That failed
run is retained in `captures/iroh/admission-initial/`; the valid run and source/APK
hash receipt are in `captures/iroh/admission-final/`. Build completed in 16 seconds.

This remains an isolated module, not a live Mac session. Next: protected scoped
installation identity, enrollment/directory/relay credentials, endpoint lifecycle,
server event/surface lanes, and integration with `MobileRpcClient` and account UI.
Do not infer full app/device parity from these seven local native fixture passes.


### Protected installation identity — 2026-09-28

`IrohInstallationStore` now persists a fresh installation UUID and separate native
Ed25519 keys for the full environment/project/team/user/device/app/build scope.
The app namespace must equal the actual installed Android package, so debug and
release cannot borrow an official or another build's identity. A nonexportable
Android Keystore AES-256-GCM key wraps each 32-byte seed with every identity field
as authenticated data. Atomic records live in `noBackupFilesDir`; only hashed
scope names and the random installation ID are stored outside ciphertext.
Private storage hashing is an Android-specific length-delimited encoding, not the
Worker signing encoding. Proofs still require `IrohV2SigningCodec` canonical bytes.

Stored seed corruption, loss of the wrapping key, and a missing installation ID
for existing records fail without automatic replacement. Per-process synchronized
creation prevents competing keys across app/service store instances. Temporary
seed arrays are cleared and native key handles have explicit close semantics.
The future service lifecycle must close live endpoint/key owners on sign-out;
this store alone does not implement that service or remote revocation.

Four physical Pixel cases returned **OK (4 tests), 0.830 seconds**. They prove
restore/signature/endpoint continuity, separation of account scope fields,
foreign-package rejection, concurrent-load convergence, ciphertext scope binding,
tamper rejection, and no replacement after wrapping-key/installation-ID loss.
Each test used its own random store/Keystore alias and removed it afterward.
Build passed in 56 seconds. APK SHA-256:
`117aa80c4d85b888dcd5ad66df85bf544d89fa642f9327938638b1f19e3f8b43`.
Evidence and source hashes: `captures/iroh/identity/`. These four cases are separate
from the earlier seven native checks, not an eleven-case combined suite.

### Deployed backend audit — 2026-09-28

An unauthenticated GET of `https://cmux-iroh-v2.debussy.workers.dev/v2/health`
returned production revision **e0263f46a6698bf7d74e828b6200e4bfa963a3dc**, rule
`cmux.mac-peer-inbound.v1`, storage max 7/write 6. That exact public source was
fetched and inspected. Its platform enum and mobile inbound predicate still
accept only `ios` for mobile peers; this is not just a stale iOS reference pin.
`auth.ts` independently verifies Stack user identity and selected-team membership.
Mobile peers may use an independent app namespace; exact host namespace/build
matching is an additional rule for the separate Mac-to-Mac path.

The envelope builder now explicitly selects `IOS_COMPATIBILITY` for the existing
`ios` mobile wire discriminator, retaining the Android app namespace and adding
`(Android)` to the device display name. This is a compatibility profile for the
current unmodified host; it does not claim native Android backend metadata support.
Using the profile must preserve the ordinary account/team checks and the user's
host pairing opt-in; it must not claim an official app namespace or reuse keys.
The health request sent no account credentials or device metadata. No live
Android enrollment request has been made. iOS starts new identityGeneration at
**1**, restoring the server record's generation on subsequent sessions.


### Control transport and signed envelopes — 2026-09-28

Added `IrohV2ControlSocket` and `IrohV2ControlHttp` in the app module. The socket
correlates concurrent calls by request ID/schema, acknowledges delivery receipts,
forwards directory/revocation events through a bounded channel, and terminates
instead of dropping authority events under backpressure. Late replies cannot
complete later calls. Revocation errors terminate pending/future calls. HTTP
checks request IDs, retains typed error/retry metadata, limits response bytes,
cancels owned calls on closure and refuses redirects. Both transports disable
OkHttp automatic retries; uncertain mutations are not automatically repeated.
The service owner still needs to choose HTTP recovery and run reconnect/renewal.

`IrohV2SignedRequests` builds first-setup proofs, registration requests, and HTTP
proofs covering the exact unsigned setup plus operation. It checks challenge
expiration and the server's descriptor hash before signing, and validates returned
identity/key/generation/revocation fields before accepting enrollment. Normal
account traffic requires HTTPS at a root origin without embedded credentials or
query/fragment; loopback HTTP is allowed for local fixtures. Outbound request bodies
are capped at 16 KiB. Authorization headers reject blank credentials and CR/LF.
The descriptor builder requires our independent release/debug namespace, starts
at generation 1 and labels the Android device explicitly while selecting the
existing mobile wire profile described above.

**Nine focused JVM tests passed**, zero failures/errors/skips: five actual local
MockWebServer socket/HTTP cases and four envelope/identity cases. Official Worker
fixture signatures match exactly for socket setup, HTTP request and registration.
Other cases cover reversed responses, receipts/events, timeout without mutation
replay, revocation, HTTP redirects/limits/cancellation, challenge mismatch/expiry,
and all identity fields. Combined focused run: successful in 50 seconds. Evidence:
`captures/iroh/control-transport/` and `/tmp/cmux-v2-envelope-tests.log`.

These are request/transport components, not an active enrollment session. Next
wire them into the scoped service owner: authenticated team selection, lifecycle
epoch guards, ready/challenge/register sequencing, ticket/relay renewal, consistent
paginated directory snapshots and pushed revocation, followed by native endpoint
and `MobileRpcClient` integration. No account token, installation descriptor or
registration request was sent to the live service in this milestone.

### Account control session — 2026-09-28

`IrohV2ControlSession` now owns one authenticated account/team incarnation. It
coordinates signed socket setup, ready/challenge/register, returned-device checks,
metadata synchronization, complete computer-directory snapshots and relay
credentials. A new service instance is required after terminal failure or closure.
A guarded account-scope predicate prevents replies from a previous login from
publishing into the new session. A separate authority watcher clears state and
closes transports after scope loss, even while a request holds the operation lock.

Socket upgrade/network failure can use signed HTTP with a fresh setup proof;
authentication rejection does not trigger this fallback. If an established socket
fails, subsequent requests use signed HTTP without automatically replaying the
interrupted operation. HTTP mode polls the directory while permission is valid.
Automatic promotion back to WebSocket and persisted service-cache recovery are
still pending.

Directory pages must agree on team and revision, stay within capacity bounds and
use nonrepeating cursors. Concurrent revision changes restart the read, and partial
pages are never published. Pushed updates use one conflated refresh worker with a
revision floor. Revoked peers disappear immediately; delayed registration cannot
restore this phone after its own revocation. A newer complete authoritative
snapshot can restore a peer after a subsequent permission grant. Directory and
relay expiry are checked independently of stalled requests. Ticket, relay and
directory renewal have independent retry delays; explicit server rate limiting
still applies to all operations. Relay tokens are omitted from state string output.

This service is not yet attached to the app's authenticated team selector, native
endpoint or computer UI. No installation descriptor or enrollment request has been
sent to the live service. The next integration must supply the protected scoped
key, Stack account/team identity and login-incarnation guard, acquire/package the
native dependency, then connect endpoint admission and the separate Irx lanes to
`MobileRpcClient`. Native endpoint lifecycle, persistent directory/ticket cache,
forced Stack refresh after rejected authentication, socket promotion, and actual
Pixel/Mac acceptance remain open.

Verification: **21 focused JVM tests passed**, zero failures/errors/skips, in a
combined run (Gradle 3m 10s): 12 control-session cases, the five existing transport
cases and four signing-envelope cases. Session cases cover enrollment, fresh HTTP
fallback proof/ticket usage, rejection without fallback, mixed-revision pagination,
cursor cycles, pushed revocation during registration, coalesced revision refresh,
expiry during stalled I/O, account changes during token/network waits, established
socket loss followed by HTTP recovery, and independent credential renewal.
Evidence/source hashes: `captures/iroh/control-session/`; local build log:
`/tmp/cmux-v2-session-tests.log`. These are local network fixtures, not production
account, phone-to-Mac, or full-app acceptance results.

### Authenticated teams and main APK packaging — 2026-09-28

`NativeAccountTeams` loads `/users/me` and `/teams?user_id=me` using the cmux
Stack client project. Settings now shows the selected team, available memberships
and refresh action. Like iOS AuthCoordinator, a missing/invalid selection resolves
to an actual membership; no user-ID-to-team-ID inference is made. Team changes
PATCH `selected_team_id` and require a matching account/selected-team response
before replacing the local scope. Rejected switches preserve the existing team;
loss of membership access clears scope. Login incarnation and a monotonic team
generation fence delayed responses, including switching away and back to one team.
Sign-out/disposal clears state and cancels HTTP calls.

An explicit authentication rejection refreshes the token once; the network layer
refuses redirects and does not replay uncertain PATCH requests. NativeAccount now
accepts an explicit forced refresh while preserving its existing stored-login guard.
These are local fixture checks; the new Settings UI has compiled but has not yet
been viewed on the Pixel or used with a live account. Team creation and persistent
team-cache behavior remain open.

The app now depends on `:iroh`, with arm64 ABI filtering so a JNA-only x86 payload
cannot install as though Iroh were available. CI restores the pinned native
artifact from a build-script-keyed cache or builds it from the exact source, Rust
and NDK versions. The normal Gradle receipt/hash verification runs in either case.
The new CI cache branch has not yet run remotely. Draft commits still do not trigger
APK builds. Fresh checkout/native checkpoint instructions are updated for Windows
and macOS. Other ABIs remain a release task, not an unavoidable Android limitation.

**35 focused JVM tests passed**, zero failures/errors/skips: 10 account/team cases
and 25 V2 session/transport/signing cases. The combined debug APK build passed in
23 seconds. It contains Iroh and JNA arm64 libraries, the original Iroh binary
hash matches the native receipt, and its MIT/Apache/JNA notices are packaged and
listed in the app's license dialog. `zipalign -c -P 16 -v 4` and APK v2 signature
verification pass. Debug APK SHA-256:
`2c13559ed8adbb2aa498f4250d999186ead09ad090734dd6215934087ec3bd4a`.
Local evidence: `captures/iroh/account-native-package/`; final log:
`/tmp/cmux-account-native-final.log`. An initial compile exposed the private Stack
configuration companion, which was fixed before the successful run. The signature
check needed the configured JDK path; rerunning with JAVA_HOME verified it.

The Pixel is no longer required connected for this local work; the user was told
it can be disconnected until the actual connection test. This APK has not been
installed on the phone, and no production enrollment was sent. Next connect the
resolved account scope and protected native key to the control session, native
endpoint/relay lifecycle, admitted Irx lanes and MobileRpcClient. The last published
signed APK remains build 157. Main-app native packaging alone is not live-Mac parity.

### Native RPC transport boundary — 2026-09-28

`MobileRpcClient` now consumes a `MobileRpcTransport`. Its existing public TCP
constructor delegates to `SocketMobileRpcTransport`, preserving the legacy test
and host path. `IrxMobileRpcTransport` wraps an admitted session and feeds control
bytes into the same RPC decoder. A separate event reader receives complete
unframed event payloads. Concurrent control/event dispatch serializes delivery
sequence changes. Closure immediately fails pending requests; write failure or a
write deadline retires the connection because a frame may have been partially
sent. A response timeout never replays the request, and late responses cannot
complete a different call. Host-status token lookup preserves cancellation.

`IrxEventMultiplexer` gives each shared/surface stream its own frame decoder.
Partial bytes never cross streams or survive replacement. It limits active readers
to the shared lane plus 16 surfaces and closes readers on cancellation/capacity
failure. `IrxClientSession.acceptEvents` consumes a bounded/versioned incoming
stream descriptor with a deadline before exposing raw event bytes. The existing
app event-delivery/replay policy is unchanged. Surface-lane subscription opt-in,
control-stream repair and specialized terminal/input/artifact/browser lane adapters
still need integration with their app consumers.

`IrxEndpointRuntime` adds the native endpoint owner: it binds the installation key
with authenticated custom relays, remote stream credit zero, disabled port mapping
and deferred NAT traversal; waits for relay readiness; validates directory peer
keys through admission; and authorizes direct paths only after admission and the
caller's authority check. Relay token updates use upsert before expired URLs are
removed, and closing the endpoint retires owned sessions. This code compiles but
its authenticated relay lifecycle has not been exercised on the Pixel or live Mac.
The account/team lifecycle owner must still supply its scope/lease predicate,
rotate credentials, cancel obsolete work and construct this transport for the UI.

**All 351 JVM tests passed across 58 suites**, zero failures/errors/skips. The eight
new tests cover independent event/control delivery, immediate close, uncertain
writes, write deadlines, timeout without replay, multi-stream frame isolation,
replacement and reader capacity. Four existing TCP RPC tests also pass. The native
test APK compiled, including new method
`IrxAdmissionTest.acceptsSharedAndSurfaceEventStreamsAfterAdmissionWithoutConsumingTheirPayload`.
That new device test has **not run**. The initial compile caught a Kotlin Unit
return mismatch in the socket adapter; it was corrected before the successful run.
The combined full JVM/native-test build took 38 seconds. Local evidence:
`captures/iroh/rpc-transport/`; log: `/tmp/cmux-irx-rpc-regression.log`.

Native test APK SHA-256:
`a14874b06d3883c082f915258a43ec2d9f2a62e24d30ae762b3a57b8c68a9e8f`.
No new main APK was built/published in this step, no production enrollment was
sent, and the phone was not needed. Injected connector fixtures now skip automatic
real-account membership fetching. Next wire the protected identity/control service
and native endpoint into one account owner and the computer picker, then run the
real-device workflow. Native transport components alone are not foreground or
background application parity.


## Integrated account owner and Computers screen — 2026-09-28

The main app now routes native computer selection through the existing V2 and
Irx implementations:

- `NativeAppConnections` shares one process owner between the retained Activity
  ViewModel and the notification service. It refreshes actual Stack membership;
  sign-out/login incarnation and selected-team changes invalidate the old owner.
- `NativeIrohRuntime` creates one backend per verified team incarnation,
  publishes available Macs, fences stale startup completion, reconnects transient
  control failures and stops automatic enrollment retries after explicit access
  revocation. Manual directory refresh preserves healthy Mac connections.
- `NativeIrohBackend` owns the protected signing key, control session and lazy
  endpoint. Debug and release retain separate namespaces/build tags; both target
  production to connect to the user's production Mac. It refreshes relay tokens,
  uses Mac metadata or the directory's relay fallback, and closes the old endpoint
  before a replacement owner finishes cleanup.
- `MobileRpcConnections` supplies reference-counted client leases. The terminal,
  aggregate feed and notification service share one admitted wire per Mac;
  each lease retains its own subscription client ID. Closing one cancels its
  pending replies without closing another consumer's wire. Revocation closes
  pending admission and wakes all consumers. Retired leases cannot close a newer
  replacement connection. Terminal input is never replayed automatically.
- The Computers screen lists current selected-team Macs and keeps legacy QR
  routes under pairing options. Native selections save a credential-free
  `cmux-android://attach` locator scoped to user/team/device/build, then validate
  against a fresh directory entry before dial. Settings/saved feeds filter by
  account/team scope. Existing Tailscale fixtures remain injectable without
  starting real account discovery.
- Native subscriptions now request `surface_event_lanes: v1`; fragmented shared
  and terminal event streams feed the existing RPC event dispatcher. Closing
  a pending native dial cancels its isolated operation.

Verification: all **365 JVM tests across 61 suites passed**, with zero failures,
errors or skips. New checks cover independent leases/subscriptions, pending reply
cancellation, revocation fan-out, stale release versus replacement, interrupted
admission, account/team/host lookup checks, team change cleanup, late backend
creation after sign-out, directory expiry, scoped locator parsing, native dial
cancellation and the surface-lane subscription field. The directory fixture also
checks relay fallback retention. These are local deterministic/loopback checks,
not live account or relay evidence.

The build caught the existing large `NativeScreen` reaching the JVM method-size
limit; sign-in and Computers views were extracted to separate composables before
successful compilation. Runtime checks against the Pixel/Mac are next. Stream
repair, specialized lane consumers, account cache/team creation, network recovery
and full feature/UI/device acceptance remain open. No signed release was published
in this checkpoint; build 157 remains the last published APK.

Build command: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`
completed successfully in 65 seconds. APK signature and 16 KiB ZIP alignment
verification passed. Local receipt/XML/build log: `captures/iroh/native-discovery/`.

- `app/build/outputs/apk/debug/app-debug.apk`: SHA-256 `8c497cc07cd2a14250f31981d8170736712fecbf4a131510d356e84801e6af4f`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`: SHA-256 `890cd0f059c7dd70527094ea46d9727954fca6805c46125fb93a3b427a1ca53d`


## Authentication and connection recovery follow-up — 2026-09-28

Compared the pinned iOS `V2ControlService+Connection.swift`,
`V2ControlService+HTTP.swift`, `V2ControlService.swift` and Worker error contracts
against the Android account owner. The following missing recovery behavior is
now implemented:

- A setup HTTP 401 or explicit `unauthorized`/`ticket_expired` reply gets one
  forced Stack-token refresh and a new signed setup proof. It does not trigger
  HTTP fallback merely because authentication failed. Repeated rejection is
  surfaced and stops automatic enrollment until the user refreshes/signs in.
- An explicit operation authentication rejection renews the API ticket with a
  freshly refreshed Stack token, then retries the rejected operation once. HTTP
  retry proofs use fresh nonces; the original request ID is preserved. Ticket
  renewal itself is bounded. Lost replies/timeouts do not take this replay path.
- Scope is checked after token refresh, before sending any recovery request.
  A sign-out/team change during refresh cannot publish or use the old authority.
- A nested native dial timeout or retired child operation becomes a normal
  connection failure while the UI/service coroutine is active. Actual caller
  cancellation still propagates. This prevents a dial deadline from silently
  ending the foreground reconnect effect.
- The runtime preserves explicit revocation/authentication reasons when backend
  cleanup also cancels transport work. An operation's `retryable=false` flag is
  no longer treated as a blanket ban on starting a fresh control session;
  challenge expiry/proof replay can start fresh, while identity/access failures
  stop. Startup retries honor server retry delays, including numeric/date HTTP
  Retry-After and rate-limit defaults, independently of exponential backoff.

The focused verification covers control-session authentication, account-owner
cancellation/revocation, and reconnect delay policy. This follow-up does not
establish live production connectivity and does not implement socket promotion,
control-stream repair, persistent discovery/ticket cache, or specialized lane
consumers. The last full-suite/build checkpoint remains e1117b7 (365 JVM tests);
its APK hash above is unchanged. No APK was rebuilt or published for this source
follow-up, consistent with batching APK builds at device/release milestones.

Verification result: **30 focused JVM tests in 3 suites passed**, zero failures,
errors or skips; final run took 17 seconds. Local XML/log/source receipt:
`captures/iroh/auth-recovery/`. No Pixel was attached during this check.


## Control-stream replacement and complete-connection observation — 2026-09-28

Audited the pinned `IrxControlByteTransport.swift`, `IrxConnection.swift`,
`IrxProtocol.swift`, `MobileCoreRPCSession.swift` and
`MobileRPCControlFrameResendPolicy.swift`. Android now implements:

- A native `control_repair` stream on the existing admitted QUIC connection,
  with a version-checked acknowledgement consumed before exposing RPC bytes.
  Replacement is bounded to five seconds. Late acknowledgements cannot install
  a lane after its deadline or after owner close.
- A separate decoder per control-stream generation. Replacement discards old
  partial frames and late native reads, resumes parked readers on the new stream,
  and retires both old stream halves with protocol error code 7. Retired halves
  are reset/stopped independently and cleanup is bounded.
- An independent native whole-connection closure observer. Pending requests and
  event consumers are woken even if an individual control read has not returned.
- Silent-request timeout epochs: concurrent unanswered requests contribute one
  silence window. Any received envelope resets the consecutive silence count.
  The first silent timeout can attempt replacement; two distinct silent windows
  retire the connection if replacement is unavailable.
- The official explicit read-only resend allowlist. Still-pending reads written
  to the old generation are resent in their original write order. Input, paste,
  viewport-reporting replay, resize, subscriptions, artifact fetch/scan, unknown
  methods and other mutations are never automatically resent; uncertain pending
  actions return an unknown-outcome error. The request that already timed out
  remains timed out.
- A `mobile.events.probe` RPC checks the replacement after acknowledgement. Any
  correlated RPC answer, including a host error, verifies it. No answer closes
  the session instead of looping through replacements. Resent writes retain a
  bounded deadline so a stalled resend cannot wedge future requests.

**Verification:** all **390 JVM tests across 64 suites passed**, zero failures,
errors or skips. New checks cover frame separation, late reads/acknowledgements,
old-stream reset during repair, read-only resend versus uncertain input,
concurrent timeout epochs, traffic during a slow request, unavailable repair,
unanswered probes, stalled resends, and native-closure notification. The first
full run failed an existing token-rejection test while connecting its loopback
socket, before reaching the authentication assertion. That test now checks the
credential boundary with an in-memory wire and asserts no frame was sent. The
three existing real TCP framing/auth/error tests still pass. The initial failure
log/XML is retained under `captures/iroh/control-repair/initial-run/`.

The full JVM suite plus `:iroh:assembleDebugAndroidTest` succeeded in 32 seconds.
New native method
`IrxAdmissionTest.controlReplacementConsumesAckPreservesBytesAndObservesWholeConnectionClose`
compiled, covering fragmented repair ACK/raw bytes, retired old read, and native
connection closure. **It has not run on the Pixel.** The previously added native
shared/surface event-stream method also still needs a physical run.

Local final XML/build/source receipt: `captures/iroh/control-repair/`.
Native test APK SHA-256: `27413faf44cc6857c863d7c1cbd1de3884050d91c9f45f21f8cec94dc03dd71e`.

The main debug APK remains the e1117b7 artifact; no main/release APK was rebuilt
or published for this step. No authenticated account enrollment or live Mac
terminal workflow has been verified. Diagnostic keepalive probes (and early
positive whole-connection silence evidence), socket promotion/cache, specialized
lane consumers, and broader feature/UI/device acceptance remain open. Without
running application probes, a repair timeout is inconclusive about whole-peer
silence; Android does not infer peer death from a missed diagnostic pong.


## Lifecycle-aware diagnostic keepalive — 2026-09-28

Android now sends sequence-correlated `keepalive` ping/pong frames on a reusable
native diagnostic stream. A timeout, malformed reply or stream end retires only
that stream; the next attempt opens a fresh one. Native connection closure remains
independently observed. A missed pong alone never closes the Mac session.

Probing follows the foreground UI and the enabled notification service, with
separate owners so one consumer cannot pause another. Backgrounding without that
service pauses probes and retires their stream. Activity revisions invalidate
prior silence evidence even when rapid lifecycle transitions are conflated.
Detected scheduler gaps also reset coverage and retire the old diagnostic stream.

Control-repair timeout can now use positive silence evidence: uninterrupted active
probing spanning the request, at least two completed missed cycles, at least two
full interval/deadline periods, and no received application bytes on any lane.
A reset/error response on the replacement stream remains inconclusive about whole
connection silence. Native control, repair, diagnostic and event reads all update
one synchronized inbound activity clock. Production timing uses the admitted host
values with bounds (interval 1–60 seconds, deadline 0.25–30 seconds); normal host
5-second/2-second settings are unchanged.

RPC parameters are copied before suspending for authentication. The serialized
request and resend classification use the same snapshot, preventing caller edits
from turning a viewport-changing replay into an automatically resent read.

**Verification:** all **398 JVM tests in 65 suites passed**, zero failures, errors
or skips. The full suite and `:iroh:assembleDebugAndroidTest` succeeded in 35 seconds.
Coverage includes correlated/stale pongs, malformed replies, lane reuse/replacement,
background/resume, scheduler suspension, activity-based silence decisions,
repair timeout versus stream reset, and parameters mutated during authentication.

New native method
`IrxAdmissionTest.keepaliveFramesUpdateActivityAndRetirementPreservesControl`
compiled. It exercises native ping/pong framing, inbound timestamps, retiring the
diagnostic lane and then using the original control stream. **It has not run on
the Pixel.** The event-stream and control-repair native checks remain pending too.

Evidence: `captures/iroh/keepalive/` (local ignored logs, XML and source receipt).
Native test APK SHA-256: `cc3be526a41729102dc4aaf0475ed3b23684b7ac76f8707955adad9fffbaa752`.
The main debug APK remains the e1117b7 artifact; no new main or release APK was
built/published here. Actual account enrollment, live Mac use, Android lifecycle
and background delivery still require physical acceptance. Relay fallback after
NAT authorization failure, tolerant optional event-lane acceptance, socket
promotion/cache and specialized lane consumers remain open.


## Optional event-stream isolation and relay preservation — 2026-09-28

Further audit of pinned `IrxServerEventLaneHub.swift` and `IrxConnection.swift`
corrected Android behavior that was stricter than the iOS companion:

- Direct-path authorization failure no longer discards an already admitted relay
  session. Caller cancellation still propagates, and the endpoint rechecks account
  authority after the attempt. This follows the upstream optional-promotion rule;
  actual failed NAT traversal over a working relay remains physically unverified.
- A bad, unsupported, truncated or timed-out uni-stream descriptor is stopped with
  code 2 and acceptance continues. Cleanup has a two-second bound. Native accept
  failure/whole-connection closure still terminates the connection consumer.
- A reset event reader discards only its unfinished frame. Oversized event frames
  stop only their lane with code 5; healthy shared and surface streams continue.
- The hub now admits 32 surface readers (matching iOS), with an overall 40-reader
  bound matching native uni credit. Excess readers are refused with code 3 so the
  host can fall back, rather than closing the entire session.
- Overlapping streams for the same surface retain independent readers until each
  ends. Complete frames from the old stream remain deliverable. This supersedes
  the earlier Android policy of immediately cancelling an old reader by resource.

**Verification:** **11 focused JVM tests across 3 suites passed**, zero failures,
errors or skips. Cases cover interleaved fragments, overlapping streams, reset and
malformed stream isolation, limit refusal while other streams continue, native
accept failure, RPC framing/auth/error behavior and transport ownership. The full
398-test checkpoint remains the preceding keepalive commit; it was not rerun here.

The focused suite plus `:iroh:assembleDebugAndroidTest` succeeded in 33 seconds.
New native method
`IrxAdmissionTest.badOptionalDescriptorsAreSkippedAndLaterEventsAndControlStillWork`
compiled but **has not run on Pixel**. It sends malformed/unsupported descriptors,
then verifies a valid event and bidirectional control traffic. Previously compiled
native event, repair and keepalive checks still await physical execution too.

Local evidence: `captures/iroh/optional-streams/`.
Native test APK SHA-256: `107e6dd36424bdd4f7c3861157053f31c44338589b9fd6e5e7a6bb73b2a07514`.
No main/release APK was rebuilt or published. Authenticated enrollment, live Mac
traffic and comprehensive feature/UI acceptance remain unverified. Next native
implementation work includes specialized terminal/input/artifact lanes and runtime
socket promotion/cache; these changes do not establish full parity by themselves.


## Dedicated render-grid input lane — 2026-09-28

Audited the pinned `MobileTerminalInputFrame.swift`, `MobileIrohTerminalLane.swift`,
`CmxIrohTerminalOutputEnvelope{,Codec,Decoder}.swift`,
`MobileTerminalLaneCoordinator.swift`, `MobileShellComposite+TerminalLane.swift`
and `MobileHostIrxTerminalLaneServer.swift`.

The mounted Android terminal now uses an independent `terminal_input` stream
when render-grid output is selected and the underlying transport supports native
lanes. The descriptor targets the exact UUID surface on the existing admitted
Mac session, without an output cursor. Readiness requires the host's first empty
CMXT replay envelope; its independent UInt64 baseline is not compared with the
render-grid/event cursor. Partial, invalid or unexpected output never enables
input. Stream opening and readiness each have a five-second deadline.

The CMXT decoder preserves unsigned sequences, bounds output to 256 KiB, validates
magic/version/reserved bits and sequence/length invariants, and retains arbitrary
partial chunks. Input uses exact UTF-8 length framing with a 16 KiB bound. The codec
also supports the official optional UInt64 measurement marker, but production does
not send markers without capability negotiation (currently sends unmarked frames).

One owner per mounted terminal performs up to three opening/reopening attempts.
Before readiness, on unsupported legacy transports, and for oversized operations,
input uses existing RPC. Paste remains on its dedicated paste RPC. After a native
write is attempted, failure propagates to the existing paused-input UI: the same
text is never silently resent through RPC. Successful native writes indicate
transport submission, not a separate host execution acknowledgement, matching iOS.

The consumer's RPC lease owns the lane coroutine. Closing that lease, selecting a
different terminal, or removing the screen retires the lane; another consumer's
lease stays usable. Late opening results after lease cancellation are closed before
being exposed. EOF/reset retires only the input lane. Output still arrives through
the existing render-grid event subscription.

**Verification:** all **410 JVM tests across 67 suites passed**, zero failures,
errors or skips (30 seconds). Coverage includes every binary split, UInt64 values
above signed Long, malformed headers/bounds, Unicode and frame limits, fragmented
readiness, invalid/truncated baselines, pre-readiness and oversized fallback,
uncertain writes without retry, cancellation, late open results, and independent
lease ownership. The initial focused 17-test run also passed before cancellation
checks were added.

`:app:compileDebugAndroidTestKotlin` succeeded in 21 seconds. New method
`NativeTerminalInputLaneTest.nativeInputUsesIndependentStreamAndControlRpcRemainsUsable`
compiled, using actual Iroh endpoints, admission, the production native transport
and RPC client, a fragmented host readiness frame and exact Unicode input, then a
control RPC on the same session. **It has not run on the Pixel.** It does not read
or clear saved account credentials. This checkpoint built no APK; the main debug
APK is still e1117b7, and the published signed release is still build 157.

Local XML, compiler logs and source receipt: `captures/iroh/terminal-input/`.
Dedicated byte-output terminal lanes, artifact/simulator lane consumers, input
latency marker negotiation, socket promotion/cache, actual account enrollment and
live Mac/UI/device acceptance remain open. This is one implemented native path,
not evidence that the full app goal is complete.


## Dedicated terminal output and replay barriers — 2026-09-28

The mounted byte/hybrid terminal now opens a duplex `terminal` stream on its
existing admitted Mac connection, with the exact surface UUID and last delivered
UInt64 cursor. Render-grid-only terminals retain the separate input-only path.
The stream requires an initial CMXT replay matching the requested cursor; later
replay envelopes, truncated output, invalid ranges and missing baselines retire
the lane. The initial replay has one five-second deadline across all fragments.

Native output and event bytes share `TerminalStreamMirror`'s delivered cursor.
Already delivered overlap is trimmed in bytes, including inside UTF-8 sequences.
Hybrid alternate-screen grids remain authoritative. Native frames cannot cross
an outstanding RPC replay barrier: a gap pauses and closes the lane, requests
an authoritative replay, then reopens from the new cursor. Late native reads after
pause/cancellation cannot mutate the mirror or re-enable input. Without a known
baseline cursor, the existing event/RPC path remains in use until a replay can
establish one. Stream attempts are bounded to three; persistent optional-lane
failure does not create an RPC resynchronization loop.

Once the output replay is accepted, the same duplex stream carries small direct
keystrokes. Paste and oversized operations keep their existing RPC path. An
attempted native write never falls back automatically after uncertain delivery.
Surface, viewport, connection and output-mode changes retire the old owner, and
RPC lease cancellation closes its feature stream independently of other leases.

Mirror counters now use UInt64 with checked addition. Android's default JSON
parser rounds integers outside signed Long through Double, so incoming control
and independent-event JSON (and request parameter snapshots) now use a tokenizer
that preserves those integer tokens as BigInteger. Nested arrays/objects retain
exact cursor values; ordinary strings, small numbers and decimal/exponent parsing
keep their normal behavior. Binary CMXT counters remain unsigned end to end.

**Verification:** all **418 JVM tests in 69 suites passed**, zero failures, errors
or skips. The full JVM suite plus `:app:compileDebugAndroidTestKotlin` succeeded in
44 seconds. New checks cover replay/chunk framing, wrong cursors, repeated replay,
truncation, overlapping native/event bytes above signed Long, a gap followed by a
fresh RPC baseline, paused owners receiving late native reads, bounded failures,
input/output coexistence, and precise nested JSON cursor parsing.

The native loopback fixture now also compiles
`NativeTerminalInputLaneTest.nativeDuplexTerminalStreamsReplayChunksAndInputAlongsideControl`.
It opens actual Iroh endpoints and verifies an unsigned cursor descriptor,
fragmented replay, subsequent output, exact Unicode input and a concurrent control
RPC. **Both loopback methods still require execution on Pixel.** No account
credentials are read/cleared by this fixture. It is not proof of live Mac parity.

Local XML, logs and source receipt: `captures/iroh/terminal-output/`.
No APK was built or published; the main debug artifact remains e1117b7 and signed
release remains build 157. Remaining work includes artifact/simulator streams,
input latency marker negotiation, socket promotion/cache, broader terminal/UI
fidelity and actual authenticated Pixel/Mac acceptance. The full goal remains open.


## Native artifact download and combined device build — 2026-09-28

Audited pinned `MobileChatEventSource.fetchArtifactChunks`, terminal artifact
calls, `ChatArtifactLaneDescriptor`, `MobileArtifactLaneFetchLoop` and
`IrxArtifactLane`. Android previews and exports now mint a descriptor through the
same selected terminal/chat `artifact.fetch` authorization, with
`transport: "iroh_artifact_v1"`. Only the returned opaque resource ID is presented
on an independent `artifact` stream, with offset zero. Paths and scope are never
substituted. Descriptor size is checked against selected metadata/preview limits;
its ISO date is validated while the host remains responsible for expiry.

The stream reads at most 64 KiB at a time and awaits each consumer write before
reading again. The final chunk is held until clean EOF confirms the exact size;
truncation, excess bytes and empty non-EOF chunks fail. Empty files complete with
one empty final delivery. A descriptor/open failure or failure before the first
byte may restart the original authorized RPC at offset zero. After bytes arrive,
failure aborts rather than splicing in another file version. Consumer write errors
and caller cancellation never trigger fallback. Existing private partial-file
cleanup and atomic completion remain in use. Each native read has a 30-second
inactivity deadline; expired reads use the same before/after-data failure rules.

Artifact streams belong to the consuming RPC lease and close independently from
control, terminal and event streams. Legacy TCP keeps the existing chunked RPC
path. Uploads still use the separately implemented attachment upload RPC.

**Verification:** all **425 JVM tests across 70 suites passed**, zero failures,
errors or skips. Full JVM plus Android instrumentation compilation took 41 seconds.
New tests cover terminal/chat scope, mint/open/first-read fallback, after-data
failure, deletion of partial previews, EOF validation, empty files, backpressure,
consumer failure and cancellation.

New native method
`NativeArtifactLaneTest.mintedCapabilityStreamsBoundedRawFileAndLeavesControlUsable`
compiled. Its actual Iroh loopback fixture requests a descriptor over control RPC,
checks the opaque resource/offset, streams a 130,123-byte file and then checks a
control RPC. **It has not executed on Pixel yet.** Before device testing, the native
fixtures were corrected to grant additional bidirectional stream credit after
admission; leaving the original one-stream allowance would block feature lanes.
The production Mac already grants application stream credit after authorization.
This fixture fix also applies to the pending terminal, keepalive and repair tests.

### Combined APK checkpoint

Built the main debug APK and both instrumentation APKs once for the accumulated
native recovery, input/output and artifact work (37 seconds). After correcting
fixture credit, rebuilt only the two instrumentation APKs (23 seconds). Main APK
signature verification and 16 KiB ZIP alignment passed. All 14 packaged viewer
asset hashes and the pinned arm64 Iroh library hash matched their receipts.

- `app/build/outputs/apk/debug/app-debug.apk`: SHA-256 `78430e94298fd62df214838050476d94ff56dfc321526cc1055873852bb58d1e`.
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`: SHA-256 `a42dcb5829f8d41a3b436144e10d8e91fb911b2db26309b34814ab06d8f7df3a`.
- `iroh/build/outputs/apk/androidTest/debug/iroh-debug-androidTest.apk`: SHA-256 `17a1ff7b5d8c7effcd8938d679f65fff306439601b720f541c7456ee52cfb02c`.

These debug APKs have **not been installed or exercised** on Pixel. The phone was
absent from ADB; a new reconnect/unlock request was sent after the build was ready.
No release was published; signed build 157 remains the published release.
Local build/test/verification logs, XML, APK hashes and source receipt:
`captures/iroh/artifact-and-device-build/`.

Remaining work includes physical native fixtures and actual account/Mac testing,
simulator streams, latency marker negotiation, socket promotion/cache and broader
UI/terminal/notification fidelity. Full goal completion remains unproven.
