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
alignment before packaging. This is a source finding; no Android Rust client build
has been attempted or claimed successful.
