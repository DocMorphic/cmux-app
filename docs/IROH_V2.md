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
