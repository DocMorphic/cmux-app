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
