# Cloud companion parity

## Source audit — 2026-10-06

Scoped upstream revision: `c2715faa02c260b07012bc0b386597cfb333021d`.
The global parity pin is unchanged. This is evidence about that source tree,
not proof of the version currently distributed through the App Store.

The Cloud packages are wired into the iOS application, not just unused libraries:

- `ios/cmux/cmuxApp.swift` supplies the active Iroh installation device ID.
- `ios/cmuxPackage/Sources/cmuxFeature/CMUXMobileRootScene.swift` constructs
  `MobileCloudComposition`, holds the controller/bridge, supplies the Cloud tab,
  and connects Cloud workspaces to the shared workspace store.
- `MobileCloudComposition.swift` configures live coherent Stack tokens and the
  resolved team, private identity storage, a tunnel starter and terminal connector.
- `MobileAuthComposition.cloudAPIBaseURL` selects `https://cmux.com` in production;
  development uses its configured remote origin or the staging fallback.
- `CloudPrimaryTabView.swift` mounts the Cloud flow and manages scene lifetime.
- `CmuxTerminalClientCloudAdapter.swift` uses Rust `WireGuardNet` and
  `TerminalClient`, not the paired Mac's mobile RPC. It lists/creates workspaces
  and terminals, attaches output, sends input, resizes and disconnects.
- The optional system VPN is separately keyed for browser/private-port access;
  its UI is conditional on an embedded packet-tunnel extension. It is distinct
  from the in-process terminal tunnel.

The root owns account/team reset, restoration, foreground/background transitions,
the workspace bridge and a shell lease while the account has machines. A user
with no Cloud machines does not automatically enroll a terminal tunnel.

## Implemented API foundation

`CloudModels.kt`, `CloudApi.kt`, and `NativeCloudAccount.kt` now cover the scoped
`CloudMachine`, `CloudAPIRequestBuilder`, `CloudAPIResponseDecoding`, and
`CloudVMService` contracts:

| Operation | Wire contract |
| --- | --- |
| Catalog | GET `/api/vm`, machines, image kinds, plan/memory limits and resource pool |
| Create | POST `/api/vm`, retained caller-supplied Idempotency-Key, kind/provider/image/home/memory options, 16-minute deadline |
| Pause/resume/delete | POST pause/resume subpaths or DELETE machine; resume gets 16 minutes |
| Tunnel enrollment | POST `/api/vm/tunnel`, saved registry device ID, public key, fingerprint, terminal/browser purpose and name |
| Tunnel revocation | DELETE `/api/vm/tunnel`, fingerprint and purpose |
| Terminal attachment | POST machine attach-endpoint with `cmux-remote`, fingerprint and bounded capability slugs; 90 seconds |
| Invitation approval | POST machine cmux-remote/approve with invitation ID |

Requests carry one captured account/team's access/refresh pair and optional
`X-Cmux-Team-Id`. The native factory uses the existing account's coherent web
session snapshot and the exact verified team generation. No redirects, shared
cookies/cache, authentication retry or connection retry; responses are bounded
to 2 MiB even when chunked. Closing a client cancels pending calls. Preflight and
postflight ownership checks reject old successes, HTTP failures and refresh
failures. The future controller must close the client when its owner retires.

Machine IDs are encoded as one path segment. Unknown lifecycle states retain the
row while disabling unsupported destructive actions. Missing capability data
stays different from a supported empty set. Only an explicit Boolean
`trustedCarrier: true` permits carrier trust; missing invitations do not imply it.
Tunnel configuration, invitations and credentials use redacted object descriptions.

At the API foundation checkpoint this code was not yet mounted in the UI. The
machine-management mounting is recorded below. No development-time live account requests,
machine creation/change/deletion, tunnel enrollment, VPN activation or cloud
provisioning were performed. The terminal-sizing UUID is not a substitute for
the Iroh device-registry ID required by Cloud enrollment.

## Verification

**15 JVM checks passed**, zero failures/errors/skips: five model/decoder checks,
ten request/HTTP/ownership checks. MockWebServer coverage includes exact headers,
encoded paths, no cookies, refusal to follow redirects to a second listener,
mutation failure without retry, stable create identity, owner changes during
token acquisition and response delivery, rejected stale refresh errors, owner
closure/cancellation and an oversized chunked body. Pure checks cover machine
names/lifecycle/resources/limits, separate tunnel roles, capability sanitation,
required fields and strict carrier trust.

Initial main compilation and 14 checks passed in 24 seconds. Adding the native
adapter and stale-token-error regression produced the final 15-check pass in
7 seconds. Evidence: `captures/runtime/cloud-api/`. No APK/device run, emulator
or signed promotion was needed at this foundation checkpoint.

## Required continuation

1. Verify the mounted machine controller/account factory and private journal on
   Android across recreation, team/account changes and process recovery. Complete
   hidden machine persistence, shell/tunnel leases and the attachment operation
   gate. The machine-management portion is recorded below.
2. Expose the existing Iroh registry identity to Cloud; integrate the persisted
   terminal identity/configuration below, separately owned browser keys and pending
   revocation. Validate routes through the native tunnel before carrier attach.
3. Audit/build the Android Rust/JNI terminal client and in-process WireGuard
   transport with 16 KiB-compatible libraries. Do not route Cloud daemon traffic
   through the paired Mac RPC. Port invitation/trust and output-reducer contracts.
4. Project Cloud machines/workspaces/terminals into the retained Android navigation
   and terminal owners. Handle concurrent Mac/Cloud sessions, local scroll/resize,
   replay, input ordering, early input and lifecycle without cross-owner leakage.
5. Finish Cloud onboarding, private-network state and the plan/subscription
   experience. Verify and visually compare the implemented list/create/resource/
   lifecycle/error screens against iOS.
6. Investigate Android system-VPN private-port behavior alongside the user's
   Tailscale setup. Any unavoidable platform constraint needs concrete evidence.
7. At integration milestones, test the transport and screens against controlled
   fixtures; then verify authorized real-account/Pixel workflows, background and
   process recovery, account/team changes, revocation and signed upgrade.

Cloud notification behavior and any further workspace/file/task support require
the remainder of the upstream source audit. These are open requirements, not
exclusions from full parity.

## Retained machine controller — 2026-10-06

`CloudMachinesController.kt` ports the list/machine portions of
`CloudSessionController.swift` and list failure rules from `CloudSessionPhase.swift`
at the same scoped revision. A controller belongs to one account/team and runs on
its owner UI dispatcher. It owns its coroutine jobs independently of observers:
cancelling an observer's await does not cancel an admitted create or lifecycle
action. Explicit close cancels jobs, closes the API, retires connections and
clears all published rows/errors. Replaced list requests have generation fences.

- Refresh/failure retains the previous catalog, capabilities and limits. Destroyed
  machines disappear; removed machines and changed lifecycles retire their links.
- The first transient failure with no rows retries quietly. Retries use 2 seconds,
  then 5/10/20/40/60 seconds, with an eight-read budget. Missing/rejected login and
  ordinary 4xx failures do not poll; 408/429 and 5xx can retry.
- Provisioning polls every five seconds up to a 60-poll budget, then preserves
  the rows with a manual-refresh failure. Backgrounding cancels scheduled reads;
  foregrounding restarts unsettled work. In-flight catalog reads are retained.
- Only one create is admitted at a time, and only one lifecycle action per
  machine. Other machines can act concurrently. Pause/resume/delete admission
  checks the current catalog; unknown lifecycle states do not enable mutations.
- A delete retires its link before sending. Actions reconcile through the server
  catalog after success/failure instead of inventing a local lifecycle. Create
  failure also refreshes, since a lost response may already have created a VM.

`CloudCreateFileJournal.kt` adds a durable Android reservation before the create
request. Same effective options reuse the UUID after failure or reopening;
changed options create a new identity, and acknowledged completion retires it.
The file is scoped by login/user/team (not transient team generation), stored
under the caller's private no-backup root, atomically replaced and fsynced before
transmission. Unreadable/corrupt state fails instead of silently minting another
key. A stale completion cannot remove a newer intent. The source's in-memory
pending-create contract and `web/app/api/vm/route.ts` idempotency header handling
were inspected; this is not evidence of a particular server retention duration.
No automatic create/lifecycle retry is introduced.

Verification: **29 JVM checks passed**, zero failures/errors/skips: controller 10,
journal 4, API 10, models 5. Main compilation passed in the same 39-second run.
Cases cover previous rows, quiet/terminal failures, retry/poll bounds, background
timers, superseded uncancellable reads, observer cancellation, duplicate admission,
create identity settlement, persistence failure, pre-start cancellation, parallel
machine actions, link retirement and late results after close. File tests cover
reopening, scope separation, normalization, stale completion, atomic write failure
and corruption. Evidence: `captures/runtime/cloud-controller/`.

At the controller checkpoint these objects were not yet constructed by the UI;
the mounting follow-up below supersedes that limitation. Actual
Android storage/process death, screen lifecycle, the native account adapter,
connection retirement callbacks and live API behavior still need integration
evidence. Tunnel/attachment serialization, hidden-machine state, Cloud screens,
Rust/WireGuard, system VPN and real-account acceptance remain open. No APK/device
run, emulator, signed promotion or live Cloud operation occurred at this checkpoint.

## Machine-management UI mounting — 2026-10-06

`NativeCloudViewModel` now constructs the native account API, durable create
journal and controller for the exact verified account/team. It observes both
team state and credential revisions, closes the old owner before publishing a
replacement, and retains the controller across Activity recreation. Opening the
Cloud tab activates catalog reads; after activation, foreground and owner changes
refresh as appropriate. No terminal tunnel is enrolled by this mounting.

`NativeCloudScreen` and the third primary navigation tab implement the inspected
`CloudSectionView.swift` / `CloudCreateMachineSheet` source structure:

- Cloud is machine management; rows do not open a second terminal screen.
- Retained list/loading/empty/error states, pull-to-refresh, retry, machine names,
  lifecycle and resource readouts; pause/resume and explicit delete confirmation.
- New Machine sheet with the source's desktop/base choice, server memory ladder,
  8 GiB fallback/default, known disk-size labels, locked choices and resource-pool
  usage. The controller receives the selected effective options.
- Busy states prevent duplicate submission, and pending creation survives the
  observing screen's cancellation. Server failures and recovery actions remain
  visible. Plan information opens the public pricing page; the native subscription
  sheet is not implemented.
- Account/team replacement remounts the screen so old dialogs/selections cannot
  submit through a new owner. Back returns to the retained Workspaces route;
  incoming pairing/notification links leave Cloud. A hidden terminal does not
  count as visibly selected for notification clearing.

The initial Cloud tutorial, tunnel/private-port controls, Cloud computers and
workspace projection, terminal transport, tablet-specific composition, visual
comparison and accessibility acceptance remain open. The workspace-projection
footer is not shown before that integration exists. Source-code mounting is not
proof that account API calls, mutation settlement or UI behavior work on a device.

Verification: **18 focused JVM checks passed**, zero failures/errors/skips
(presentation 4, controller 10, journal 4). Main/instrumentation compilation
passed; the combined focused run took 19 seconds, a navigation follow-up compile
10 seconds, and corrected fixture semantics/theme compilation 2 seconds. Three
Android cases are queued for the integration batch: selected size/usage and
create, delete confirmation/reconciliation, and the selected Cloud tab without
workspace search. Those cases have **not run**. Evidence:
`captures/runtime/cloud-ui-source/`. No APK, emulator, Pixel install, signed
promotion or live Cloud action occurred. The next installed development build
will perform real catalog reads when the signed-in user opens Cloud; create and
lifecycle operations require their explicit UI action.

## Tunnel identity and configuration — 2026-10-06

`CloudTunnelIdentity.kt`, `NativeCloudIdentity.kt` and `CloudWireGuardConfig.kt`
implement the installation identity and in-memory configuration boundary from
`CloudDeviceIdentity`, `CloudDeviceIdentityResolver`, `WireGuardKeyPair` and
`WireGuardQuickConfig` at the same scoped upstream revision.

- One random `android-<uuid>` fingerprint and X25519 terminal key per installation,
  separate from account, push, terminal-sizing and Iroh registry identities. The
  inspected server accepts 1–128 URL-safe fingerprint characters; no Apple device
  identity is claimed. Only the public key belongs in enrollment requests.
- Android Keystore AES-256-GCM seals the identity with versioned authenticated
  context; ciphertext lives in the app's no-backup directory. Reads never create
  a replacement Keystore key. Corruption, unavailable keys and failed persistence
  fail without silently changing an enrolled identity. A process monitor and file
  lock serialize resolve across store instances/processes; fsync and atomic replace
  complete before resolve returns. The store has no account-logout deletion path.
- The iOS composition shares the fingerprint with the optional system VPN, but
  `CloudSystemVPNController` mints a **new browser key on each enable**, saved inside
  that VPN configuration. It does not persist a second independent installation
  fingerprint or reuse the terminal key. That browser lifecycle remains to port.
- Nonempty server configuration receives the local private key and 25-second
  keepalive. Empty/whitespace configuration uses enrollment addresses/routes,
  MTU 1200, host prefixes and bracketed IPv6 endpoint. The API decoder now permits
  an empty string while continuing to reject absent/non-string configuration.
  Duplicate/misplaced key settings and injected multiline fallback fields fail.
  Configurations and identities have redacted descriptions. Completed configuration
  stays in memory; no system interface, shell command or VPN is started here.

The native `cmux-wg` parser remains authoritative for address/route validity and
trusted-carrier coverage. This Kotlin preparation must never be used as proof
that a route is inside WireGuard. The factory is ready for the tunnel owner but
is **not yet mounted**; opening Cloud still only activates machine catalog reads.

Verification: **25 JVM checks passed**, zero failures/errors/skips, with main
compilation in 24 seconds: identity 5, configuration 5, models 5 and API 10.
Coverage includes the RFC 7748 public-key vector, encrypted file reopening,
parallel resolvers, tamper/unavailable-key/corruption failures without replacement,
failed atomic persistence, dual-stack configuration and strict API fallback
decoding. JVM storage tests use an AES-GCM test cipher; Android Keystore,
cross-process contention, actual process/power failure, native parsing and live
enrollment still require integration evidence. No APK, emulator, signed promotion,
cloud provisioning or tunnel activation occurred. Evidence:
`captures/runtime/cloud-tunnel-identity/`.

### Native transport build finding

The upstream `cmux-terminal-client` provides a C ABI, persistent daemon enrollment,
raw snapshot/output/resize/exit callbacks, workspace/catalog operations and an
in-process userspace WireGuard/TCP stack. Android should use that transport and
feed raw output into its renderer. Callback clearing waits for in-flight delivery;
callbacks must not clear themselves, and tunnel free must follow all clients.
Explicit carrier trust requires the native literal-IP/AllowedIPs check, with no
ordinary OS fallback.

At `c2715faa`, `ghostty-vt-sys/build.rs` maps iOS/macOS/Linux/Windows Rust targets
to Zig targets but has **no `aarch64-linux-android` mapping**. Merely selecting the
Android Cargo target would leave Zig's host target selected. Its bindgen invocation
also needs Android target/sysroot review. The next native build must pin the source,
adapt that build boundary, link through the NDK, and verify 16 KiB LOAD/RELRO
alignment before packaging. This was a source finding at the identity checkpoint;
the subsequent native build attempts and Android adapter are recorded below.

## Android native client bridge — 2026-10-06

The manual `cloud-native.yml` workflow builds a native checkpoint on hosted Linux,
without APK/signing/publishing or any account/VM access. The driver exports cmux
`c2715faa` and its actual Ghostty submodule `324c0273` without changing the input
checkouts. It uses upstream `rust-toolchain.toml`, Zig 0.16.0 and NDK r28c/API 26.
The Android build adapter explicitly supplies Android Ghostty archives and the
NDK target/sysroot to bindgen. The exported client builds as a cdylib; internal
Ghostty symbols must remain hidden from the app's separately pinned renderer.
The checkpoint gate checks the C ABI symbols, AArch64 and 16 KiB LOAD/RELRO
alignment, and records source/patch/script hashes and dependency metadata.

`cloud_terminal_jni.c` and `CloudNativeTerminal.kt` cover tunnel start/free,
trusted route validation, remembered/invitation connections, raw output,
attach/detach, session/workspace/terminal catalog, workspace/terminal creation,
input, resize request/ack and exit. JNI uses standard UTF-8 arrays, bounds incoming
arguments and outgoing terminal/catalog bytes, and frees owned C strings. Errors
do not expose native invitation/configuration text. Callback byte storage is
copied before returning; Java global references are released only after the
upstream synchronous callback clear. Allocation/delivery failure marks the output
stream unusable instead of silently losing VT bytes.

The Kotlin boundary is blocking and must run on IO workers. It admits input and
catalog calls concurrently while attachment changes and close hold an exclusive
lock. This keeps a slow catalog from blocking typing and still prevents handle
retirement during any admitted call. It rejects use after close, disconnects clients before freeing the shared
tunnel, installs raw delivery before attach and requests viewer size priority.
Callbacks only enqueue; they never reenter native code or run UI/user code. The
queue has byte/event limits and fails explicitly on overflow. Attachment generations
wake and reject old consumers without consuming a new terminal's snapshot. Both
JNI and queue failures stop further input admission. The daemon state directory is
created by upstream with its own private-directory policy.

`CloudTerminalOutputReducer` matches iOS grid-before-replay, reset on subsequent
snapshot, output, resize and exit ordering. Writes are split at the existing
Android Ghostty 2 MiB append boundary without changing bytes. The renderer's
existing 1,000-cell dimension bounds still need review when mounting Cloud output.

Verification: the JNI source passes the NDK arm64/API 26 C11 check with
`-Wall -Wextra -Werror`; compiled JVM descriptors match the JNI methods and
`onOutput(I[BII)V`. Main Kotlin compilation passed (15 seconds), and **8 focused
JVM checks passed** (9 seconds, then 6 seconds after adding immediate queue-health
admission; 24 seconds for the eight-case concurrent-input follow-up). Tests use a fake C-ABI boundary and cover retirement order/idempotence,
private route rejection, attachment generations, input/exit/error admission,
overflow, waiting-reader reset and chunked replay ordering. They do **not** verify
native linking, real callbacks, native transport, renderer pixels or Android runtime.
Evidence: `captures/runtime/cloud-native-adapter/`.

Hosted attempt [37447383957](https://github.com/DocMorphic/cmux-app/actions/runs/37447383957)
failed before Rust compilation because a nested Ghostty step could not find `zig`
on PATH. The driver now prepends its pinned Zig directory. Follow-up
[37447939408](https://github.com/DocMorphic/cmux-app/actions/runs/37447939408), source
`9b1b8f34`, then compiled Ghostty and most Rust dependencies before failing in
`cmux-pty`: Android lacked the Linux-gated `ptsname_r` helpers and libc has no
`getdtablesize`. The exported-source patch now includes Android in the two
`ptsname_r` helpers, uses checked `sysconf(_SC_OPEN_MAX)` before fork and bounds
the portable descriptor loop. The return convention was checked against
[Bionic's implementation](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/bionic/pty.cpp)
and the installed API 26 NDK headers. This enables compilation of the shared
crate; the Android companion does not start local PTYs. Runs `37449188907` and
`37449713438` subsequently exposed the missing exported Ghostty submodule layout
and Linux errno accessors in two directory readers. The driver now restores the
expected layout and patches both readers to Bionic's `__errno` under Android cfg.
Their exact source anchors were verified against the pinned source. Run
[`37450983946`](https://github.com/DocMorphic/cmux-app/actions/runs/37450983946)
at `819b6943` **passed**: upstream Rust C ABI, JNI linking, required C exports,
hidden Ghostty exports, AArch64 and 16 KiB LOAD/RELRO checks. Both libraries were
downloaded into `build/cloud-terminal-android`; all eight artifact hashes and
current builder/JNI source hashes matched the receipt. Independent local alignment
verification passed for both libraries. This is a native compilation checkpoint,
not APK packaging or Android runtime acceptance. The JNI library is 17,536 bytes;
the client library is 32,817,848 bytes. Evidence:
`captures/runtime/cloud-native-checkpoint/`.
The compilation cache key now includes the builder and JNI source so each porting
fix can save its additional compilation progress, while restoring earlier caches.

Before real Cloud Iroh/WSS acceptance, audit Android DNS/context and TLS-root
initialization for the separately linked Rust library. The existing Iroh FFI
initializes its own library; do not assume that initializes this cdylib's globals.
The pinned crate sources were downloaded and SHA-256 checked against Cargo.lock
for this audit (`/tmp/cmux-cloud-crate-audit/`). Concrete integration requirements:

- `iroh-dns` 1.0.3 requires `install_android_jni_context` before a system resolver
  is created, with JavaVM and global application-context references valid for the
  process lifetime. Its `ndk_context` state belongs to this linked library.
- `rustls-platform-verifier` 0.7.0 `src/android.rs` requires `init_with_env` (or its
  runtime/refs alternatives); uninitialized global access panics. It uses JNI
  0.22.4 and the `rustls-platform-verifier-android` 0.1.1 Java component, which must
  be packaged with keep rules. The README's older `init_hosted` sample does not
  match these pinned source APIs. `iroh-relay` delegates its optional platform
  verifier here; the pinned cmux composition uses its embedded WebPKI default.
- The upstream WebSocket provider passes no custom TLS connector. Its native-root
  route uses `rustls-native-certs` 0.8.4 and `openssl-probe` 0.2.1; the latter's
  Android certificate-file default is a Termux path. This cannot be treated as
  proven Android system trust. Wire an initialized platform verifier for WSS and
  test trusted and rejected certificates; do not disable TLS verification.

### Android DNS and TLS adapter

The exported-source driver now adds a process-owned Android initialization module
to the Cloud cdylib. It initializes `rustls-platform-verifier` using JNI 0.22's actual
API, retains a global application context for `iroh-dns`, serializes repeated calls,
and catches JNI-closure panics at the boundary. JNI checks that the verifier Java
class exists and refuses tunnel start/connect until initialization succeeds. The
explicit account tunnel-preparation function supplies the application context.

Android WSS uses a Ring-backed Rustls connector with the initialized platform
verifier, hostname/chain validation intact. Plain WS retains upstream behavior;
Iroh's embedded-root default is unchanged. Only new dependency edges to versions
already in the pinned Cargo.lock are added; the hosted cargo build remains locked.
The checkpoint includes the matching verifier 0.1.1 AAR and hashes both Android
adapter sources. Gradle packaging and shrinker keep rules are now wired below.

Run [`37452130548`](https://github.com/DocMorphic/cmux-app/actions/runs/37452130548)
at `e3322507` **passed**, including the Rust runtime adapter, WSS connector and JNI
linking. The downloaded checkpoint replaced the earlier generated checkpoint in
`build/cloud-terminal-android`: all nine artifact hashes and five adapter/builder
hashes matched, and both libraries passed local 16 KiB alignment verification.
The exact source patches and locked manifest edits applied locally, and the NDK C
syntax check passed. The compiled Kotlin initialization descriptor matches JNI.
**26 focused JVM
checks passed** with main compilation (24 seconds): native ownership, handshake and
Cloud API regression cases. They do not execute Rust or the Android verifier.
Native linking alone does not establish Android DNS, trust-store or real transport
acceptance. Add device checks for initialization, current-network DNS, and trusted
versus rejected WSS certificates before claiming runtime acceptance. Evidence:
`captures/runtime/cloud-android-runtime/`. Gradle was stopped after verification;
no emulator or APK build was started.

The native files are now wired into Gradle packaging as described below. Next:
implement account-owned enrollment/approval/attachment cancellation,
common-workspace projection and renderer/input integration. Caller cancellation
must retire any late native connection result; wrapper methods alone do not supply
that coroutine ownership. Android/fixture and authorized Pixel/live account gates
remain open. The earlier foundation checks did not start an emulator or rebuild an APK.

### Android packaging checkpoint

Run [`37454996751`](https://github.com/DocMorphic/cmux-app/actions/runs/37454996751)
at `d7cc7281` passed. The downloaded checkpoint has **756 verified artifact hashes**,
**410 notice components**, no missing notice texts and passing 16 KiB LOAD/RELRO
checks for both libraries. The first collector run identified missing published
crate texts and accidentally counted hidden license-CI workflows as notices;
the collector now excludes CI/source files and uses verified source supplements.
All 31 committed supplemental texts preserve their recorded source bytes.
See `third_party/cloud-notices/README.md` for the pinned source mapping, Ghostty VT
import audit, embedded SIMD/Unicode attribution and Rust runtime texts.

Gradle now packages both arm64 libraries, the matching verifier AAR and license
assets. Its portable gate checks source/toolchain pins, all adapter and artifact
hashes, unexpected JNI files, inventory integrity and missing notice texts.
The verifier JNI classes have keep rules, and Cloud appears in the app's license
viewer. APK CI calls the reusable Cloud native workflow, which restores a complete
checkpoint on an exact source key or builds it on a cache miss. The same verifier
runs before APK construction. The standalone Python verifier additionally checks
ELF alignment. The workflow YAML parses and Gradle configuration passed (9 seconds).

The two collector regressions passed: runtime/build dependency closure excludes
dev-only edges; nested notices survive while external symlinks, CI and source files
are excluded. `CloudAndroidRuntimeTest` exercises real repeated/concurrent JNI
initialization beside the existing Iroh library and the native invalid-config
error path, without enrollment or VM creation.

**Debug and instrumentation APKs built successfully in 1 minute 17 seconds.** The
packaged verifier class is present; all **749 notice assets** match checkpoint
hashes. Both Cloud libraries exactly match the checkpoint after reproducing AGP's
`llvm-strip --strip-unneeded` step. All **21 packaged arm64 libraries** pass ELF
alignment verification, and `zipalign -c -P 16 -v 4` passes. APK SHA-256:
`e4ce299ea9f72e6600918ea20d5d58346627ddc7dfbcf0c432c6644ebfb024b6`.
The local build was from the packaging changes on `d7cc7281` (dirty source stamp).
Evidence: `captures/runtime/cloud-packaging/`.

The real-library Android test compiled but **has not run**: no ADB device was
connected. A Pixel connection request is pending. Initialization, current-network
DNS and trusted/rejected WSS acceptance remain unverified. No emulator was started,
Gradle was stopped, and superseded generated Cloud checkpoints were removed.
No signed APK was published. Account-owned tunnel/session mounting, common workspace
projection and terminal renderer/input integration remain the next implementation
work; packaging alone does not provide a usable Cloud terminal.

### Account-owned attach and approval foundation

`CloudMachineHandshake` starts native connect and invitation approval concurrently,
requiring both before publishing READY. It follows the iOS two-second polling,
150-attempt/five-minute bounds, transient retries, invitation expiry and explicit
trusted-carrier behavior. Account cancellation and approval failure settle promptly
even while native connect is blocking; the independent native worker closes any
late handle. Cancelling an observer does not steal or close the owner's session.
The machine owner must explicitly close the attempt on retirement.

`prepareNativeCloudTunnel` resolves the encrypted terminal key and enrolls with the
shared Iroh registry device ID, checking the current account/team before and after
IO. At this foundation checkpoint the startup function and handshake were not yet
invoked by the mounted UI; the lifecycle integration below supersedes that status.

Verification: **18 JVM checks passed** (8 handshake and 10 Cloud API); main and Iroh
instrumentation Kotlin compilation passed in the final six-second run. Checks include
approval/connect ordering, real blocking-worker cancellation and late cleanup, native
failure, HTTP retry/expiry, polling bounds and observer cancellation. The new Android
identity-read test compiled but was not run. Evidence:
`captures/runtime/cloud-machine-handshake/`. Native transport, account tunnel leases,
workspace/renderer integration and real device acceptance remain open.


### Account-owned tunnel lifecycle — 2026-10-06

`NativeCloudViewModel` now refreshes the catalog with the authenticated foreground
shell, starts a terminal tunnel only when machines exist, and retains it across
primary-tab changes. Backgrounding, sign-out and account/team replacement retire
machine admission immediately and release the tunnel on an IO worker. This follows
`CMUXMobileRootScene.cloudShellLeaseWanted` and `CloudSessionController` at the scoped
revision above. Machine catalog requests remain owned by their existing controller.

`CloudTunnelController` bounds enrollment and native startup to 30 seconds. A
blocking native startup does not delay account retirement: its late handle is
closed rather than published. Failures survive visibility changes and offer explicit
retry; unexpected enrollment cancellation also offers retry. The Cloud screen shows
connecting, connected and failure/retry states.

`CloudMachineConnections` lazily shares one attach/approval owner per machine and
retires affected links on catalog removal/lifecycle changes. `CloudNativeSession`
fences new input before waiting for admitted blocking catalog calls to drain.
Known-daemon state is private and isolated by user/team; it survives transient
login and generation changes. All shared native handles still have one owner.

Cloud-first startup now calls `IrohInstallationStore.loadOrCreateDeviceId`, using
the same synchronized registry identity as computer discovery without opening a
scoped signing key. Existing malformed IDs or lost IDs with remaining scoped keys
still fail without replacement. The read-only `storedDeviceId` contract is unchanged.
This removes the first-login race between independent Cloud and computer discovery.

Verification: **37 focused JVM checks passed**: tunnel lifecycle 9, shared machine
connections 2, native terminal ownership 8, attach/approval 8, machine controller 10.
Main, app instrumentation and Iroh instrumentation Kotlin compilation passed in
39 seconds. The checks include real blocking-worker timeout/late cleanup,
foreground replacement, parent cancellation, explicit retry, immediate input
rejection during a blocked catalog read, and daemon identity scope isolation.
The new Android concurrent Cloud/computer registry test compiled but has not run.
Evidence: `captures/runtime/cloud-lifecycle/`.

No ADB device was connected, no emulator or APK build was started, and no live
Cloud request or signed release was performed during this checkpoint. The app now
owns the tunnel lifecycle, but the workspace bridge does not yet consume its lazy
machine connections: common workspace/catalog projection, terminal renderer/input
mounting, private-network/plan UX and actual Android transport acceptance remain
open. A READY tunnel badge alone does not prove an attached terminal works.


### Owned workspace catalog and shell projection — 2026-10-06

`CloudWorkspaceCatalog.kt` ports `SessionCatalog.swift`, `TerminalCatalog.swift`,
`CloudTerminalTransport.swift`, `CloudAddress.swift` and `CloudWorkspaceProjector.swift`
at the same scoped revision. The snapshot follows terminal → first tab → pane →
screen → workspace, then sorts by workspace order, screen index, pane order and
tab index, with stable terminal order for ties. Unplaced terminals remain reachable
under an `unassigned` row. Tab names precede program titles and home-relative paths;
otherwise terminals are numbered within their workspace. The address namespace uses
the upstream group separator, keeping Cloud ownership distinct from Mac/build IDs.
The projection produces this app's shared `NativeWorkspace`/`NativeTerminal` models.

The C ABI's direct result shapes are decoded: snapshots, wrapped or legacy bare
workspace lists, terminal lists and creation results. Missing required inventories,
duplicate selectable identities, malformed UTF-8 and oversized catalogs fail rather
than becoming authoritative empty lists. As in the iOS native adapter, unavailable
or undecodable snapshots fall back to concurrent workspace/terminal lists; caller
cancellation never starts fallback calls. Native calls execute on IO workers.

`CloudWorkspaceController` retains account-owned rows and catalogs. It only reads
running machines after the terminal tunnel is ready. Losing that tunnel retains
last rows as reconnecting; failed reads retain rows as unavailable and retry at
5/10/20/40/60 seconds, capped at a minute. Non-running machines publish authoritative
empty inventories without dialing; removed machines stop their reads and lose rows.
Read generations and account checks reject late results. `NativeCloudViewModel`
now wires machine and tunnel changes into this controller. Failed initial handshake
attempts are replaced for the next catalog request so automatic retry can actually
recover. Successful machine links remain shared.

Verification: **22 focused JVM checks passed**, main compilation passed in 25
seconds. Catalog decoding/projection 6, catalog lifecycle 5, shared connections 3,
handshake regressions 8. Tests cover hierarchy/order, detached terminals, names,
legacy wire shapes, invalid inventories, address namespace, cancellation versus
fallback, tunnel admission, pause/removal, retained rows, timed retry and superseded
uncancellable reads. Evidence: `captures/runtime/cloud-workspace-catalog/`.

The catalog is now consumed by the account owner, but its projected rows are not yet
mounted in the shared list. Next integrate the `NativeWorkspaceDisplayRow`/sort/filter
and sidebar paths, Cloud selection and the existing terminal surface/composer.
Creation result decoding does not yet provide a workspace/terminal mutation flow.
Single-slot attachment, ordered output delivery, early input, resize/repaint and
reconnect selection still require their bridge implementation. No live Cloud request,
Android runtime test, APK build, emulator or signed promotion occurred in this
checkpoint; ADB still reports no device. This is not a usable Cloud-terminal UI yet.


### Single-slot terminal attachment — 2026-10-06

`CloudTerminalAttachment` ports the attachment/input/replay rules from
`CloudWorkspaceBridge.swift` at the scoped revision. One owner serves one machine's
selected terminal. A serial native slot and monotonic selection generation fence
blocking attaches, while native attachment tokens fence send/resize/detach. Switching
terminals cancels the previous consumer and drops its queued input. A late old attach
must finish before the replacement can attach; its cleanup cannot detach the newer
native token. The machine pool owns the underlying session, so closing a view's
attachment does not close the catalog link.

One consumer delivers output in order onto the owner's dispatcher. The last phone
grid is sent before flushing early input. Early input is copied and bounded to
8 KiB; a live attachment accepts a bounded 256 KiB queue (including an ordinary
64 KiB paste). Rejected or unconfirmed input clears pending bytes and reports a
failure; it is never automatically replayed. Tunnel loss preserves desired selection
and geometry but discards the old pending queue. New early input can wait for the
foreground link to return. Explicit replay force-detaches and re-attaches even the
same terminal, so it obtains a new daemon snapshot.

A resize away from the last snapshot grid schedules a repaint after 400 ms; repeated
resize events coalesce, and a fresh snapshot cancels the repaint. Exit stops input
and releases the slot. Account close immediately fences input/output and schedules
native cleanup without blocking the UI dispatcher. Linkage failures become a
recoverable attachment failure.

Verification: **25 focused JVM checks passed**: attachment owner 8, native ownership
9, handshake regressions 8. The final main/test/instrumentation compilation and
focused run passed in 10 seconds. Checks cover ordered replay/output, copied early
input, first viewport, old/new selection isolation, overflow and rejected-input
non-replay, reconnect, resize debounce/snapshot cancellation, exit/account close,
large live paste and unavailable runtime. A real two-worker blocking-attach test
proves the old attach cannot overtake its successor; native token tests prove stale
send/resize/detach cannot affect a new attachment. Evidence:
`captures/runtime/cloud-terminal-attachment/`.

This owner is implemented and tested through injected links, but it is not yet
mounted in the Android renderer/navigation. Next use one owner per machine,
connect it to the retained Cloud account/catalog, and supply bytes and selection to
the common Ghostty surface, toolbar and composer. Shared list/filter/sidebar rows,
workspace/terminal creation, persistence and full UI behavior remain pending. Pixel
runtime/transport evidence is absent: no ADB device was connected; no emulator, APK
build or signed promotion ran. Passing ownership tests is not proof of live Cloud
terminal operation.


### Shared workspace and terminal UI — 2026-10-06

Cloud workspace rows now appear in the common All Computers list and routed
sidebar, using shared sorting, search, machine filters, pane selection and workspace
presentation. Opening a row captures its account-owned catalog; stale navigation
cannot open a same-ID workspace in a replacement account. Cloud rows do not expose
unsupported Mac mutation actions or invent unread/activity metadata.

`CloudTerminalHost` retains one attachment slot per machine and supplies the
existing Ghostty surface, toolbar, keyboard and composer through
`CloudRenderedTerminal`. Ordered snapshot/output bytes feed the real renderer;
initial snapshots suppress bells. Terminal switches fence the previous display's
input and preserve bounded in-memory drafts. Early input is available while the
Cloud attachment opens. Backgrounding rejects input; reconnect waits for an
authoritative catalog before restoring the selected terminal. Removed terminals
and paused machines report their unavailable state. Failed catalog links are
retired by identity so an old failure cannot close a replacement connection.

Verification: **92 JVM checks passed across 14 suites**, zero failures/errors/skips,
covering the new Cloud navigation and connection retirement plus shared sorting
and routed-sidebar regressions. Debug and instrumentation APKs built successfully
in the same 1m25s milestone. Captured navigation-owner admission then compiled and
packaged in a 46-second final rebuild. APK ZIP alignment passes at 16 KiB; native
receipt/pin gates passed. Final debug APK SHA-256:
`258a127cb8b709efc11f37e7561b66eb1d2ac7814482e865e59a20b009d45ba3`.
Evidence: `captures/runtime/cloud-ui-integration/`.

`CloudTerminalScreenTest` compiles a real-Ghostty/shared-composer fixture with an
injected daemon link, Unicode/colored output, terminal switching and background
input rejection. It has **not run**: ADB reports no device. No emulator or signed
release was produced. Compiling this fixture is not evidence of actual native
Cloud connectivity, Android rendering or input delivery.

Next complete Cloud workspace/terminal creation, full computer-picker and scoped
return navigation, selection restoration and hidden-machine persistence. Audit
Cloud-only onboarding, plan/tutorial/private-port controls and owner fencing of
retry callbacks. Then run Android fixtures and authorized real-account Pixel
workflows, including DNS/TLS, reconnect and process recovery, and compare UI states
against iOS. The shared UI is mounted; full parity and live transport remain open.


### Computer selection and sidebar return — 2026-10-06

The common computer dropdown now includes Cloud machines, their names, status and
selected state. Like the iOS deferred menu used by Mac/SSH, each opening captures
its presentation and callbacks; taps recheck the current account and machine.
Catalog name changes update the toolbar while an open menu retains its original
labels. Removed-machine rows become disabled. A missing saved Cloud host is shown
as unavailable, with All Computers remaining an explicit choice.

Cloud host identifiers now survive the existing saved computer-selection path and
routed-sidebar presentation round trip. Loading, disconnect or removal does not
broaden a selected Cloud scope to Mac, SSH or another Cloud machine. Workspace and
notification sources, refresh, status rows, sorting/filter context and Mac/SSH
creation menus honor that scope. Switching computers retires the previous Cloud
view. Cloud empty guidance and toolbar text no longer suggest pairing a Mac.
Cloud Retry also captures its catalog owner, preventing a retired callback from
resetting a replacement account's same-ID connection.

Verification: **84 JVM checks passed in 13 suites**, zero failures/errors/skips,
including Cloud sidebar round-trip/removal admission, computer pairing and shared
sidebar regressions. Main and Android test compilation passed in 52 seconds. The
new Android dropdown case covers captured names/callbacks, selection, live toolbar
renaming and rejection after removal, but has **not run**. Evidence:
`captures/runtime/cloud-computer-scope/`. No ADB device, emulator, APK rebuild or
signed promotion. Full screen/process recovery and real-account acceptance remain
pending; preserving the computer filter is not terminal process restoration.

Continue with Cloud workspace/terminal creation, hidden-machine persistence,
terminal selection/process restoration and Cloud-only onboarding, followed by the
queued Android fixtures and physical workflows.


### Workspace and terminal creation — 2026-10-06

`CloudWorkspaceCreation` follows the Creating section of the pinned iOS
`CloudWorkspaceBridge.swift`. The account owns the request lifetime and permits
one create at a time. Admission requires the current running machine and an
authoritative connected catalog. The native C ABI receives `CREATE_WORKSPACE`
with no name, or `CREATE_TERMINAL` with the explicit real workspace ID and no name.
No create operation is automatically retried.

An acknowledged workspace ID is published immediately as a non-authoritative row,
selected before the following catalog read returns. The retained route picks its
starter terminal once the authoritative inventory arrives. New Terminal refreshes
the inventory and selects the acknowledged terminal only in its actual workspace.
The synthetic `unassigned` row creates a real workspace first and uses its starter
terminal; its synthetic ID never goes to the daemon's create-terminal operation.
Machine lifecycle epochs and account retirement reject late completions. A later
navigation choice prevents an earlier completion from reopening its destination.

Cloud targets now participate in the shared New Workspace menu and routed sidebar,
including empty machines and explicit computer scope. The terminal pane's shared
picker exposes New Workspace and New Terminal with current admission/busy checks.
Single-Cloud-target + creates directly. Failures distinguish unconfirmed creation
from acknowledged creation whose inventory has not loaded; both ask for refresh
before another attempt. An interrupted link or absent runtime becomes a visible
failure. Explicit reconnect clears the creation banner and refreshes the inventory.

Verification: **100 focused JVM checks passed across 15 suites**, zero
failures/errors/skips, with final main/instrumentation compilation in 9 seconds.
Eight creation-owner tests cover optimistic selection/starter loading, duplicate
taps, real and unassigned workspace paths, lost acknowledgments, failed follow-up
reads, removal/re-addition, pause/background admission, account retirement,
interrupted links and missing runtime. Sidebar cases cover empty-machine creation,
computer scope and stale/busy/disconnected actions. The new Android shared-menu
fixture compiles but has **not run**. Evidence:
`captures/runtime/cloud-workspace-creation/`.

No live daemon mutation, Pixel workflow, emulator, APK rebuild or signed promotion
was performed. The creation UI and native call path are mounted; actual Android
creation/selection/input and process-death behavior still need integration evidence.
Next finish hidden-machine persistence, terminal selection/restoration, Cloud-only
onboarding and the remaining upstream behaviors, then run the queued fixtures and
real-account acceptance.


### Saved Cloud computer visibility — 2026-10-06

The pinned iOS `CloudSessionController.swift` stores only hidden machine IDs,
partitioned by API origin, user and effective team. `MobileCloudComposition.swift`
supplies that scope; login/session generation is excluded. Successful machine
inventories prune deleted IDs. `CloudWorkspaceBridge.swift` mirrors the stored
choice into the common host filter, and the Computers screen owns the switch.

Android now follows this behavior through `CloudMachineVisibility` and the existing
Computers management destination. Preferences are partitioned by a hash of the
structured origin/user/team tuple, surviving login and team-generation changes.
New machines are visible by default. Loading/failed inventories preserve stored
choices; only authoritative machine lists prune removed machines. Failed writes
preserve the published choice and report an error without terminating the account's
catalog/tunnel observer. Actual Android disk/process-failure behavior is unverified.

Hidden machines remain listed in Computers with an accessible Show switch. They
are removed from the workspace list, computer dropdown, sorting/filter choices,
create menus and routed-sidebar inputs. Hiding the open machine closes its terminal
host and input path; hiding the explicitly selected computer clears that selection.
A late create result cannot reopen a hidden machine. The all-hidden state keeps
Computers reachable and explains how to show the machines again. Hiding does not
pause/delete a remote machine, revoke the account or alter its catalog membership.

Verification: **28 focused JVM checks passed across five suites**, zero
failures/errors/skips, with main/instrumentation compilation in 21 seconds.
Five visibility checks cover reopening, login/generation stability, account/team
separation, successful-only pruning, unknown/retired callback rejection, failed
writes and structured scope collision avoidance; existing catalog/creation/sidebar
checks also passed. The Android management switch fixture compiles but has **not
run**. Evidence: `captures/runtime/cloud-computer-visibility/`. ADB reports no
connected device; no emulator, APK rebuild, signed promotion or live Cloud action.

Next implement and verify terminal selection/process restoration and Cloud-only
onboarding, finish the source-to-behavior audit, and run the queued Android/Pixel
acceptance workflows. This checkpoint completes the visibility implementation,
not physical parity acceptance.


### Cloud terminal selection and activity restoration — 2026-10-06

The pinned iOS `MobileWorkspaceLastTabStore.swift` and `MobileShellComposite.swift`
remember the phone's last tab per workspace, independent of remote focus. Android
Cloud now reuses `NativeWorkspaceLastTabs`, including its 512-workspace bound,
unchanged-write suppression and credential transaction admission. Keys include the
verified user/team, a Cloud-namespaced machine ID and the workspace. Reopening a
workspace selects the remembered terminal; a retained/incomplete catalog cannot
disprove that choice. Authoritative removal permits fallback inside that workspace.
Explicit terminal picks supersede remembered selection.

`CloudScreenCheckpoint` adds a bounded destination-only record to Android's
`SavedStateHandle`, installed by the ViewModel factory's `CreationExtras`. Android
activity/process recreation can restore its saved login/user/team/machine/workspace/
terminal IDs. No input, credentials, buffers or mutation commands are checkpointed.
Restoration waits for foreground, a running machine and an authoritative connected
catalog. Account/login/team mismatch, hidden machines and confirmed removal discard
the intent; a paused machine waits without being resumed automatically. Explicit
navigation or an incoming pairing/notification link cancels the pending restore.
This uses Android's saved activity state; it does not promise reopening a terminal
on an unrelated cold launch or after clearing app data.

A freshly created workspace can resolve its starter through the same remembered
selection path. An open terminal removed from the catalog safely becomes a fallback
or waiting pane instead of constructing an invalid zero-selection picker. Switching
Cloud machines now retires the previous view's foreground/input admission. The
currently selected host alone regains that admission after foregrounding. Last-tab
write or restore failures report a navigation error.

Verification: **53 focused JVM checks passed in eight suites**, zero failures,
errors or skips, with final main/instrumentation compilation in 23 seconds.
Six Cloud restoration checks exercise persisted tab ownership, retained versus
fresh inventory, foreground/readiness gates, removed/paused/hidden machines,
account mismatch, within-workspace terminal fallback and bounded checkpoint
validation. Existing last-tab, catalog, creation and navigation checks also passed.
Evidence: `captures/runtime/cloud-terminal-restoration/`.

Actual Pixel activity/process recreation, saved-state delivery, live reconnect and
input admission remain unverified; ADB reports no device. Cloud composer drafts
are still bounded in memory and need the persisted-draft behavior audited/ported.
The destination checkpoint does not claim draft restoration. Continue Cloud-only
onboarding and the remaining native/UI audit alongside those acceptance gates.


Integration packaging for the accumulated Cloud selection, creation, visibility and
restoration changes also passed: debug and instrumentation APKs built in **57
seconds**, including DEX generation and native receipt gates. Debug APK ZIP
alignment passes at 16 KiB. SHA-256:
`22f312ea16bb32fdaf207d5929e131257427bc4e2c107e6f76ba559339d58fc6`.
No runtime installation, emulator or signed release promotion occurred.


### Persistent Cloud composer text and native send receipts — 2026-10-06

The scoped iOS `MobileShellComposite.swift` serializes draft writes and preserves
newer typing when an earlier send finishes. Android Cloud now binds its common
composer to `TerminalDraftRepository`, using the existing encrypted credential
store. Targets isolate production origin, login, user, team, machine and terminal;
a team generation change alone does not change the key. Host/view teardown retains
text. Confirmed terminal removal discards its binding; logout invalidates old
bindings through the repository generation. Cloud currently accepts text drafts;
this does not add Cloud image transfer.

Composer submission persists a pending-delivery marker before any bytes leave the
ordered input queue. A failed write prevents transmission. The Cloud attachment
returns a receipt only after the native send accepts those bytes. This establishes
native transport admission, **not remote command execution**. Failed, interrupted
or uncertain delivery retains text with an unconfirmed warning; recovery never
resubmits automatically. New typing survives completion of an older submission.
Cancelled/timed-out queued input is removed before a later connection can send it.
Keyboard input retains its existing immediate queue behavior.

Verification: **35 JVM checks passed in four suites**, with zero failures, errors
or skips. Main and instrumentation compilation passed; the final instrumentation
compile took two seconds. Checks cover draft scope/reload, pending markers, failed
storage, owner retirement, global clear, native rejection and cancelled/timed-out
queues. The common-renderer Android fixture additionally checks that pending text
was journaled before native submission; it compiles but has **not run**. Evidence:
`captures/runtime/cloud-composer-drafts/`.

ADB reports no device. Actual encrypted Android storage, activity/process recovery,
live Cloud delivery and the renderer fixture remain physical/runtime acceptance
gates. No emulator, APK rebuild, signed release or live Cloud action was performed
for this batch. The four latest native checkpoint CI runs are green; the latest is
[37454996751](https://github.com/DocMorphic/cmux-app/actions/runs/37454996751).


### Cloud basics introduction and replay — 2026-10-06

`NativeCloudFlow.kt` now follows `CloudFlowView.swift` and
`CloudOnboardingView.swift` at the scoped upstream revision. The Cloud tab starts
with a three-page introduction (workspace topology, system VPN and private key),
with Skip, Continue, Back and Get started. Completion uses the upstream v2
per-install milestone, separate from the Mac introduction and account state.
A failed preference commit leaves the introduction open with a retry message.
The pager retains its page through Android saved-state recreation.

After completion, Cloud management offers a Cloud basics replay sheet. Closing or
finishing replay does not write completion or create/change machines. Tab ownership
and the existing account-owned connections stay in the shell. The layout switches
to side-by-side copy/illustrations on wide or compact-height displays, falling back
to scrollable stacked content for enlarged text. Progress has a spoken step label;
illustrations are decorative. Android key copy reflects the actual Keystore-backed
Cloud identity encryption.

The VPN page explicitly reports that Android system-VPN private-service access is
not yet available. It does not show a working toggle or imply that the terminal's
in-process tunnel routes other apps. This is an outstanding implementation gap,
not an unavoidable Android difference. Plans/subscription and full Cloud-only
first-run shell behavior still need audit/acceptance.

Verification: main compilation passed (34 seconds), then **23 existing focused
JVM checks passed** across onboarding state, Cloud machine controller and creation
presentation (zero failures/errors/skips). Android test compilation passed in the
same six-second invocation. Three new Android UI checks cover failed persistence
and retry/recreation, pager restoration and completion, and replay without writes.
They compile but have **not run** because ADB has no device. No visual matching or
TalkBack/runtime acceptance is claimed. Evidence:
`captures/runtime/cloud-introduction/`. No emulator, APK rebuild, live Cloud
operation or signed release promotion occurred.


### System VPN route/storage foundation — 2026-10-06

Scoped iOS source: `CloudSystemVPNController.swift`, `CloudSystemVPNPreferences.swift`
and `CloudVPNRoutePolicy.swift` at `c2715faa02c260b07012bc0b386597cfb333021d`.
The optional VPN has a fresh key per enable, enrolls under `browser`, keeps the
installation fingerprint, and is distinct from the terminal tunnel. It survives
leaving the tab/backgrounding but is removed on account/team retirement. Pending
server cleanup is retained rather than evicted. The platform must validate the
actual installed configuration as well as the structured enrollment fields.

`CloudVpnRoutePolicy.kt` implements private-only routes (RFC 1918, CGNAT and IPv6
ULA). It rejects default/public/loopback/link-local ranges, DNS settings, hooks and
application/table overrides. The installed text is independently checked, including
section placement and bounded address/route lists. Numeric parsing does not resolve
hostnames. This is a route-policy check; the platform WireGuard parser still owns
complete key/endpoint/configuration syntax validation.

`CloudVpnStore.kt` provides an encrypted atomic profile and browser-peer cleanup
journal in no-backup storage. Enrollment identities must be committed before POST.
Unknown/interrupted enrollment remains pending. Installing a profile retains its
cleanup entry; retiring the profile does not acknowledge remote revocation. Only
the exact attempt/owner/fingerprint can settle cleanup, so stale callbacks cannot
erase a replacement. A pending identity blocks replacement enrollment for that
owner/fingerprint; capacity never evicts unresolved entries. Failed writes preserve
the old state, malformed ciphertext fails closed, and reloading a profile checks
its private routes again. The native factory uses a separate Android Keystore
alias and authenticated-data context from the terminal identity. No credentials
are stored in cleanup records. These are prerequisites; **the VPN controller,
platform service, consent UI and lifecycle integration are still unimplemented**.

The new fallback-configuration test exposed an existing terminal-tunnel bug:
`CloudWireGuardConfig.fromFields` rejected normal Base64 public-key `=` padding.
That field now permits padding while retaining line/control/comment-injection
checks. Other fallback fields retain their original restrictions.

Verification: **19 JVM checks passed in four suites**, zero failures/errors/skips;
main and Android test compilation passed. The nine new checks cover route boundaries,
malformed numeric input, divergent server text, encrypted reload, interrupted
cleanup, write failure, capacity/owner isolation and stale completions. Final run:
six seconds. Evidence: `captures/runtime/cloud-system-vpn-foundation/`. No live
VPN enrollment or activation, APK rebuild or emulator was performed; ADB has no
connected device. Android Keystore and service behavior still need physical proof.

The official [WireGuard embedding library](https://www.wireguard.com/embedding/)
`com.wireguard.android:tunnel:1.0.20260102` was downloaded for inspection only and
is **not yet an app dependency**. Its six arm64/x86_64 libraries pass the existing
16 KiB LOAD/RELRO gate. The artifact also contains 32-bit libraries, which were
reported separately by the verifier and are outside this app's supported ABIs.
AAR SHA-256: `2b9c16db026496123e4db695d26d03d1958a201096c7c4c89b21077dc70f3119`.

[Android allows only one active VPN service per user/profile](https://developer.android.com/develop/connectivity/vpn).
The eventual explicit-enable UI must explain replacing Tailscale/another system
VPN. The in-process terminal tunnel does not occupy that slot. Next implement the
serialized account-owned controller, owner-authorized cleanup retries, platform
WireGuard service/foreground notification, OS consent and onboarding/settings
controls; then verify private web access and Tailscale transitions on Pixel.


### System VPN lifecycle controller — 2026-10-06

`CloudSystemVpnController.kt` now owns explicit enable, disable, account binding,
platform-state callbacks and cleanup. It is separate from the foreground terminal
lease. Binding an account never enrolls; enable requires current account admission
and previously granted OS consent. Each enable generates a fresh browser key and
journals the peer identity before POST. The native adapter supplies the Iroh
installation device ID and shared fingerprint, enrolls only under `browser`, and
captures a coherent token pair while the account is admitted. That retained pair
is exposed solely through a browser-peer DELETE closure, so account replacement
cannot substitute another user's credentials into cleanup. Tokens remain in memory.

All install/stop/revoke transitions share a gate. Timeout/account retirement
invalidates startup immediately and requests platform interruption; a slow operation
keeps the gate until it actually finishes and any late installation is stopped.
A replacement cannot overtake it. Local stop precedes profile retirement and server
revocation. Failed stop keeps both profile and cleanup identity durable. Revocation
gets three bounded attempts, while enrollment POST is never retried automatically.
Cleanup handles at most eight peers per transition; unresolved owners remain in the
journal and are eligible when their authenticated access is available again.
Current-operation cancellation reports failure rather than leaving a timerless
Preparing state. Attempt-tagged platform callbacks cannot disconnect a newer VPN.
Application lifetime closure requests cleanup through an independent worker.

Verification: **22 focused JVM checks passed across four suites**, with zero
failures/errors/skips; main and Android test compilation passed. Eight controller
checks cover consent/no implicit enrollment, fresh keys, stop/revoke ordering,
account changes, captured old-account cleanup, unknown enrollment, cleanup retry,
non-cooperative late install and timeout, late enrollment after sign-out, initial
signed-out reconciliation, failed local stop, stale callbacks, current cancellation
and parent lifetime termination. The final invocation took eight seconds. Evidence:
`captures/runtime/cloud-system-vpn-controller/`.

**This controller and native account adapter are not mounted yet.** Their platform
boundary is exercised with fixtures, not Android's VPN service. Next implement the
WireGuard service and foreground notification, bind an application-lifetime owner,
wire OS consent and onboarding/settings controls, and reconcile persisted enabled
state and actual service callbacks. Backlog cleanup scheduling beyond the bounded
transition batches also needs integration. No live account request/VPN activation,
APK rebuild or emulator occurred; ADB still reports no Pixel. Full Cloud VPN parity
and the overall goal remain unverified.


### Android VPN service and Cloud controls integrated — 2026-10-06

The pinned official WireGuard Android library is now an app dependency.
`NativeCloudVpnPlatform` parses the private configuration using its Config API,
resolves endpoints on IO, and supplies literal endpoints to GoBackend. A shared
native lock serializes native transitions against upstream service destruction;
DNS runs before that lock. Account/permission/generation admission is checked
before service start, immediately before native activation and after activation.
A startup reservation retains service ownership even if cancellation wins the
suspending wait. The controller then tears down a late start through its gate.

`NativeCloudVpnService` subclasses the upstream service, adds a low-importance
foreground notification with Disconnect, holds a shared-connection reference and
reports attempt-scoped stop events. It is not exported, requires BIND_VPN_SERVICE,
and declares the Android systemExempted foreground type. The unused upstream base
service is removed from the merged manifest. Always-on is explicitly disabled and
the service is not sticky; boot/start restoration is not yet claimed.

`NativeCloudVpnRuntime` is owned by the shared native connections. The active service
retains that owner across activity/tab closure, observes verified account/team
changes and stops old routes before binding another account. Account setup failures
can be retried. Revocation refreshes a coherent pair only while the original scope
is still current; retirement uses the captured original owner, never replacement
credentials. The Cloud management panel and Cloud basics VPN page now expose real
status, explicit enable/disable, retry and Android VPN consent. Consent completion
is tied to the captured team scope, so an account switch cannot enable a replacement
account silently. The control explains that enabling replaces another system VPN,
including Tailscale. No VPN is enabled automatically on visiting the page.

Verification: **22 focused JVM checks passed**, main/test compilation passed,
and debug/instrumentation APKs built (integration 66 seconds, final APK update 26
seconds). All **22 packaged arm64 native libraries** pass 16 KiB LOAD/RELRO checks;
APK ZIP alignment passes at 16 KiB. The protected service manifest, exclusion of
root/kernel executables and packaged WireGuard notices were inspected. Final APK
SHA-256: `269d2c6add618e1ddbbaa6fb691d2c6bce2545d07bf38a6b23d898e4e8398370`. Evidence:
`captures/runtime/cloud-system-vpn-platform/`.

Two additional Android backend checks compile into the test APK: loading the actual
Go library/version without VPN activation and parsing a generated dual-stack private
configuration. They have **not run**. ADB reports no Pixel, so actual consent,
foreground-service startup, private browser reachability, notification disconnect,
account switching, background survival and Tailscale transitions remain unverified.
There was no live Cloud enrollment or device VPN activation, no emulator and no
signed release promotion. Remaining implementation/audit work includes persisted
service restart behavior, bounded backlog cleanup scheduling, and full source/UI
comparison. The overall parity goal remains active.
