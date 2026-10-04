# Codex laptop handoff — 2026-09-28

**Continuation update:** work returned to the original Mac on 2026-09-28 at the
user's request. Windows commit `9849010` was pulled without conflicts. It adds
portable Gradle setup, LF-preserved asset hashes, remote POSIX path semantics,
and a focused runtime runner. See `WINDOWS_DEVELOPMENT.md` and the follow-up in
`SYNTAX_CHECKPOINT.md`. The pause and outstanding four-test statements below
describe the original handoff, not the user's current instruction to continue.
The user has since explicitly approved enabling native mobile pairing, and the
Mac panel reached Iroh Ready. The four outstanding viewer fixtures now pass on
the physical Pixel (10.981 s), including visible Mermaid/Vega pixel checks; see
`SYNTAX_CHECKPOINT.md` for precise scope and APK hashes.
Read [IROH_V2.md](IROH_V2.md): the live host requires Iroh, superseding the old
Tailscale-first plan. Historical listener-approval restrictions below are resolved.

Read this first, then `PARITY.md`, `ANDROID_TESTING.md`, `RESEARCH.md`, and
`PIXEL_INSTALL.md`. This is a continuation of an existing Android implementation,
not a request to scaffold another prototype. The dated sections of the other
documents are historical; newer verified entries supersede older pending claims.

**Delivery update — 2026-10-03:** signed development build **494**, source
`6e4a449b8feb20a5097e51d787078a9326d7c7f5`, passed CI, independent packaging checks,
the new Android 17 ART class gate and a signed-out 486 → 494 upgrade/cold launch
on the existing API 37 / 16 KB arm64 emulator. See [PIXEL_INSTALL.md](PIXEL_INSTALL.md)
for artifact/hashes and [PARITY.md](PARITY.md) for feature evidence. The goal remains
active; physical Pixel acceptance, authenticated upgrade, configured push and
broader source/visual parity remain open. The table below records the original
September 28 handoff rather than the current APK.

## Latest checkpoint — real keyboard URI grants (2026-10-04)

[KEYBOARD_URI_GRANTS.md](KEYBOARD_URI_GRANTS.md) verifies the existing image-input
permission lifetime using a real platform keyboard and private provider in a
separate test APK/UID. Three Android cases pass in one final run (21.865 s): delayed
copy after editor removal, rejected/throwing receivers, and input-queue cancellation.
Actual provider reads succeed while owned and are denied before/after the grant.

The fixture is absent from app DEX/manifest, and the production APK is unchanged.
The original emulator keyboard was restored, the fixture keyboard disabled, and
the existing AVD stopped. Earlier fixture startup, readiness-probe and System UI
ANR failures are retained in the evidence. No new AVD, app or signed build here.

**Next:** physical Pixel/Mac browser and Gboard acceptance, clipboard grant behavior,
whole-process recreation, HTTPS/native-account acceptance and the broader source
parity audit. Android feed and push configuration remain open. Build 494 remains
the last signed milestone; the last unsigned-release/ART gate is `786264d`.

## Latest checkpoint — partial attachment providers (2026-10-04)

[COMPOSER_PARTIAL_PROVIDERS.md](COMPOSER_PARTIAL_PROVIDERS.md) brings ordered
partial attachment preparation to native terminal, SSH and New Task composers.
An unreadable provider no longer drops later readable files. Ownership changes,
cancellation and draft-write failures still stop the batch. New Task keeps a
partial-failure notice after successful items are added; direct terminal delivery
keeps its existing stop-on-error behavior.

Five JVM tests and 15 distinct Android cases pass across targeted runs. Two initial
test preconditions incorrectly expected a MIME lookup exception; Android returned
null. Both corrected task paste/upload flows pass, including a final 35.903 s run
checking the retained notice. Final debug/test assembly and engine packaging pass.
The single existing emulator is stopped; no new AVD or signed build was created.

**Next:** physical Pixel/Mac browser and provider acceptance, external grant
lifetimes, drag/drop, whole-process recreation, HTTPS/native-account acceptance
and the broader source audit. ADB still shows no physical Pixel; the reconnect
question remains pending. Android feed and push configuration remain open. Build
494 remains the last signed APK; the last unsigned-release/ART gate is `786264d`.

## Latest checkpoint — composer system Paste (2026-10-04)

[COMPOSER_SYSTEM_PASTE.md](COMPOSER_SYSTEM_PASTE.md) closes the shared edit-menu and
hardware attachment-paste gap in native terminal, SSH and New Task composers.
Images/files enter existing attachment staging; URI/caption text is not inserted.
Ordinary text preserves native selection and composition. Disabled, oversized and
retired-owner actions cannot send attachments to another draft.

Nine selected Android cases pass across two runs, including actual floating-menu
clicks and a complete pasted-image task upload to the loopback RPC fixture. The
initial seven-pass/two-failure run exposed a test window-lookup issue; the corrected
menu cases passed in 31.291 s. Debug/test builds pass, screenshots were inspected,
and the existing emulator is stopped. No signed milestone or release build here.

**Next:** physical keyboard/provider acceptance, remaining multi-item provider-failure
and drag/drop behavior, whole-process recreation, HTTPS/native-account acceptance
and the broader source audit. Android feed and push configuration remain open.
Build 494 is still the last signed APK; the last unsigned-release/ART gate is
`786264d`. Do not repeat the pending Pixel reconnect question.

## Latest checkpoint — content-process crash acceptance (2026-10-04)

[NOTICE_CONTENT_CRASH.md](NOTICE_CONTENT_CRASH.md) verifies an actual native content
crash through the production renderer and archive UI. The same Android app process
survives; the old private context is cleared; Retry performs a new exchange and
renders fresh content. The archive's error wording now covers rendering failures.
Two final Android cases pass (52.278 s), plus the earlier extension-recovery case in
the initial run. On the API 37 / 16 KB emulator the actual callback is `onKill`;
`onCrash` itself is not runtime verified. Debug/test build and packaging checks pass.
The existing AVD is stopped. No release or signed milestone was built this turn.

**Next:** whole-process recreation, real HTTPS/native-account exchange, physical
Pixel/Mac acceptance and broader parity. Android feed and push configuration remain
open. Build 494 remains the last signed milestone; the last unsigned-release/ART
check is `786264d`. Do not repeat the pending Pixel reconnect question.

## Latest checkpoint — partition cleanup and callback safety (2026-10-04)

[NOTICE_PARTITIONS.md](NOTICE_PARTITIONS.md) records actual browser partition
cleanup under two top-level sites. Both normal retirement and extension recovery
clear the retired context's cookies, localStorage, IndexedDB and Cache Storage
while preserving a separate context. A real disconnect crash exposed by the test
was fixed by revoking page eligibility immediately and deferring session teardown
until Gecko finishes its port callback.

Final checks: **52 JVM tests and five Android cases passed** (90.184 s). Final-source
debug/test and unsigned release builds, engine packaging and the Android 17 / 16 KB
release ART gate pass. The fixture is absent from both app APKs. The existing AVD
is stopped; no new virtual device or signed milestone was created.

**Next:** whole-runtime crash behavior, real HTTPS/native-account exchange and
physical Pixel/Mac acceptance. Android feed, push configuration and broader parity
remain open. Build 494 remains the last signed milestone. ADB currently shows no
Pixel; preserve app data and do not repeat the pending reconnect question.

## Latest checkpoint — extension recovery (2026-10-04)

[NOTICE_RECOVERY.md](NOTICE_RECOVERY.md) records recovery without an app restart.
Native acquisition receipts survive a bundled-extension restart in memory. Old
pages close; a new preparation clears their exact private contexts before creating
a fresh session. Clearing the old native delegate fixed a real lost-reconnection
failure. Final checks: 52 JVM, 11 Node and all five Android renderer/Compose cases
passed (76.339 s), including old-context cleanup and rejection of a late exchange.
The existing AVD is stopped. Debug packaging passes; the earlier unsigned release
predates the last delegate fix, so final-source release/ART remains a batch gate.

**Next:** actual browser partitioned-state cleanup, whole-runtime crash behavior,
real HTTPS/native-account exchange and physical Pixel/Mac acceptance. Android feed,
push configuration and broader parity are still pending. Build 494 remains the
last signed milestone. Check ADB when needed; do not repeat the pending reconnect
question merely because the Pixel is absent.

## Latest checkpoint — launch announcement integration (2026-10-04)

[NOTICE_LAUNCH.md](NOTICE_LAUNCH.md) records concurrent launch preloading, retained
renderers, current-account/content/visibility checks and acknowledgement only on
actual sheet appearance. The launch sheet uses the loaded session directly;
failed pages remain unseen without a retry loop. Archive Retry keeps its separate
fresh exchange. The production debug and unsigned release build, 52 focused JVM
checks, engine package checks and release ART gate pass. Consult the linked
checkpoint for precise Compose runtime results and retained fixture failures.

**Follow-up:** extension recovery is recorded above. Browser partitioned-state runtime,
real HTTPS/native-account exchange and physical Pixel/Mac acceptance. No Android
notice feed or new signed milestone was configured. Build 494 is still the last
verified signed APK. Push configuration and broader parity remain open. Do not
repeat the pending Pixel reconnect question; check ADB when needed.

## Latest checkpoint — production private notice renderer (2026-10-04)

[NOTICE_RENDERER.md](NOTICE_RENDERER.md) records the main GeckoView integration,
private cookie leases and retained archive detail renderer. It is wired to the
native account broker. Rotation/theme reuse the page; Retry creates a new exchange.
The actual production renderer passed two Android fixtures (26.436 s), including
cookie/script isolation, owner cancellation and scoped cleanup; six existing
UI/restoration cases also passed (52.128 s). Final focused JVM tests: 45 passed;
helper/extension tests: 9 passed. Release ART, 19-library alignment, package pin /
licenses and signed-out cold launch pass. The existing AVD is stopped.

A real integration bug was fixed: pinned Gecko 157 cookie expiry is milliseconds,
not seconds. Keep native validation and readback guards. The main APK now includes
Gecko and grows to about 241 MB debug / 228 MB unsigned release. There is still no
Android notice feed and no new signed milestone (494 remains current).

**Follow-up:** launch preloads and archive Compose testing are recorded above. Continue actual
partitioned-storage, extension-loss recovery and HTTPS/account acceptance. Current
extension-loss behavior closes all owned pages and requires an app process restart;
do not describe that recovery as complete. Physical Pixel/Mac browser acceptance,
push configuration and the full parity audit remain open. The Pixel was absent
from ADB; do not repeat the already pending reconnect question.

## Latest checkpoint — main toolchain and release runtime (2026-10-04)

[ANDROID_TOOLCHAIN.md](ANDROID_TOOLCHAIN.md) records the completed main migration
to AGP 9.1.1, Gradle 9.3.1, Kotlin/Compose 2.4.20 and compile SDK 37. Min 26 /
target 36 remain unchanged. A small SDK 36 library retains the API 26–27 fingerprint
fallback. Nine route lambdas were separated after the first assembled release
failed ART verification; the revised unsigned release passes the existing gate.
Final checks: 1,539 JVM passes / 4 explicit fixture skips, six Android runtime
cases passed, native/ZIP alignment and pinned assets passed, signed-out cold
launch passed. Archive rendering was visually checked after excluding a captured
window transition. The existing AVD is stopped with settings restored.

The later checkpoint above adds the production renderer and main engine dependency.
The toolchain migration remains its prerequisite; launch preload and broader
lifecycle/partitioned acceptance are still open.
Do not repeat the isolated engine experiments without a concrete failing case.
Physical Pixel/Mac browser acceptance, authenticated signed upgrade and push
configuration remain open. Build 494 remains the signed milestone; no new signed
CI build was dispatched. Scoped research does not advance the global parity pin.

## Immediate continuation — What's New

Build 494 / run **37154456102** completed successfully; no build is in progress.
Its stable artifact is **11285640563**. Independent source/run/hash/signature,
viewer/native alignment, manifest and NOTICE checks passed. Actual signed launch
reached sign-in, setup guide opened and Back returned; no crash or compatibility
warning. First-install time survived. The existing AVD holds stable494 and was
stopped/reaped with settings unchanged. Evidence: `captures/runtime/build494/`.
The Pixel was absent; no authenticated or physical upgrade claim is made.

Candidate 486 remains rejected for a release ART VerifyError, fixed by `555e4b8`.
Builds 488 and 491 failed before ART due to CI paths and data capacity. Fixes
`063c5a8` and `6e4a449` were verified by 494; do not continue polling old runs or
recommend their artifacts. Docs-only updates need no signed rebuild.

Next source implementation: [WHATS_NEW.md](WHATS_NEW.md). The model/catalog,
atomic file store, build metadata, retained UI owner, Settings archive/detail and
native launch sheet are implemented. 29 JVM tests and 7 successful Android UI
executions passed across portrait, landscape and 150% text. The first two UI
timeouts came from a System UI ANR; after observed recovery the unchanged tests
passed. Final source removes a live account-refresh gate so notices work offline;
debug/test APKs rebuilt successfully. No Pixel or signed upgrade was verified.
The existing emulator is stopped, original settings restored and all processes
reaped. Evidence and APK hashes: `captures/runtime/whats-new-ui/`.

Continue the **incomplete web announcement path** using [WHATS_NEW_WEB.md](WHATS_NEW_WEB.md).
The origin-confined native-to-web session broker and renderer-neutral load lifetime
are implemented and passed 16 JVM tests (10 exchange, 6 deadline/lifecycle). They
are not connected to a renderer and made no real account exchange. Synthetic
loopback tests establish no physical account/cookie acceptance.

The profile experiment is now recorded in `WHATS_NEW_WEB.md`: cookie isolation and
targeted clearing passed, but deletion after destroy threw, and profile names and
cookies were missing after restart despite directories remaining on disk. The
initial invalid persistence assertion failed; the revised probe records observations
and confirms only public-registry cleanup, not disk erasure. The standalone
`notice-spike` now evaluates GeckoView157 with its own newer toolchain. Public
cookie seeding fails for named private contexts; the scoped bundled extension
passes actual isolated-request checks; the extended script-exclusion check passed
in 7.363 s. Process-death storage absence passed on the exact same origin/context.
Public per-context cleanup clears web storage but leaves private cookies. The
combined scoped extension cleanup now passes (17.719 s): A is empty and B stays
intact. Capture an owned private lease before close, require the tab gone before
clearing explicit private-cookie attributes, and use the public web-storage clear.
The public-only failure remains reproducible. The native checker
was overly broad: NATIVE_ALIGNMENT.md records the Bionic whole-LOAD exemption,
19 passing Python checks and actual old-JNA negative control. All 13 Gecko libraries
pass the corrected gate. The main-toolchain prerequisite is now complete as
recorded above, without an extra AVD. Continue production renderer/cookie seeding,
theme, navigation and the existing 10 sec / 20 sec lifetimes in launch/archive UI,
including partitioned cleanup, account lifecycle, package/licenses and acceptance. Current native catalog has no web pages or configured feed; the
explicit archive placeholder is temporary, not acceptable as finished parity.

Two-host private cleanup now passes (1 test, 13.292 s): A's cookie and all three
storage values vanish at both `127.0.0.1` and `127.0.0.2`, while B retains its
values at both. This verifies top-level cross-host visits, not embedded third-party
partitions. Evidence: `captures/runtime/notice-multi-origin/`; experiment packages
removed and existing AVD stopped. Continue partition/account/lease
lifecycle checks before renderer integration.

Basic HTTPS/Secure transport now passes (1 test, 14.611 s) with actual TLS requests,
untrusted-certificate rejection before fixture CA trust, no Secure cookie on HTTP,
correct per-context cookies after returning to HTTPS, and empty page-script cookie
strings. The existing HTTP regression also passes (1 test, 7.567 s). The separate
fixture temporarily changes only its own Gecko DNS/CA state and restores it; never
copy those test controls or its synthetic key into production. Experiment packages
removed and AVD stopped; evidence `captures/runtime/notice-https/`. No Pixel or real
account exchange acceptance. The main renderer remains unfinished.

Native fitting now passes 4 portrait, 3 large-text and 1 landscape UI checks;
see the follow-up in WHATS_NEW.md and captures/runtime/whats-new-fitting. The
initial System UI ANR caused two appearance timeouts; unchanged tests passed
after observed recovery. Settings restored, AVD stopped/reaped.
Debug replay/suppression and physical modal/lifecycle/
TalkBack and signed-upgrade acceptance remain open. Signed494 is still current;
no APK assembly, emulator or signed build ran for the web-core checkpoint.
All processes are stopped/reaped, the Pixel remains absent, and the previous
reconnect request is pending. Keep the goal active and do not repeat that request.
Batch the next signed milestone after coherent implementation work.

## User's objective and working preferences

Latest local feature: [DIAGNOSTIC_FAILURES.md](DIAGNOSTIC_FAILURES.md) adds typed
failure codes to existing debug/durable logs and RPC connection/disconnection.
Twenty-three JVM tests, debug assembly and release Kotlin compilation passed;
no emulator was needed. Raw native Iroh telemetry and broader taxonomy remain open.

Previous local feature: [EMPTY_WORKSPACES.md](EMPTY_WORKSPACES.md) adds the shared
Mac/SSH empty-state scaffold and owner-scoped 30-second Mac retry. Thirty JVM and
two Android tests passed; normal portrait screenshots were reviewed. Exact source
mapping, hashes and remaining live acceptance are recorded there. No Pixel was
available for this checkpoint. Signed build 494 now includes this and onboarding.

Previous local feature: [ONBOARDING.md](ONBOARDING.md) implements all five introduction
scenes with durable milestones, explicit completion, Settings replay, saved page/
method/draft state, connection selection and the scoped Keep Mac Awake offer.
The first-run gate requires a verified account; offline replay is informational
and routes connection to Settings. Existing pairing/help, notification service,
foreground connection and ownership checks are reused. No FCM configuration is
claimed. See [ONBOARDING_AUDIT.md](ONBOARDING_AUDIT.md) for the scoped source mapping.

Signed build 494 includes this tour and the preceding compatibility/Computers/setup-guide
features. Next validate the authenticated first-run and
replay workflow against the Mac/Pixel, including account changes, QR cancellation,
keep-awake and app restart. Where the phone remains unavailable, continue the
remaining source/visual audit and Android push integration plan. The delivery
choice in PUSH_DELIVERY.md is still pending; do not configure a provider without
that choice and credentials. Physical/native/browser/keyboard/accessibility and
signed-in upgrade checks remain pending; preserve Pixel app data. The goal is
active; do not repeat the already-pending Pixel reconnect request.

- Deliver a fully functioning unofficial Android companion matching the official
  cmux iOS app's UI and behavior, including native pairing, workspaces, terminal
  rendering/input, files, browser, notifications, and settings.
- Target phone: **Google Pixel 6a, Android 17**. The user previously signed into
  cmux and connected Tailscale, and tried the original helper-based app. That is
  not proof of current native-protocol acceptance. Physical USB/debugging access
  was requested but not established in this session.
- The user says they obtained permission to use upstream. Preserve GPL and
  third-party attribution, keep the project described as unofficial, and use the
  real cmux logo/assets already in the repository.
- Commit and push coherent features regularly. Use focused checks between commits;
  build/test/publish APKs at combined milestones, not for every feature commit.
- Be precise about coded vs tested vs shipped. Never call the app complete because
  fixtures pass, promise zero defects, or give an unsupported completion estimate.
- The previous session spent too long polishing individual features before a
  complete live workflow was proven. Prioritize a usable, installed native path:
  **pair → workspace → terminal input/output → reconnect → notification**.
  Preserve the full parity goal; do not redefine a helper prototype as completion.
- The old Codex goal is paused on the original laptop. This handoff packages work;
  it does not resume that goal or start work on another machine automatically.

## Repository and delivery state

| Item | Value |
| --- | --- |
| Private repository | https://github.com/DocMorphic/cmux-app |
| Working branch | `feature/local-mac-bridge` |
| Draft PR | https://github.com/DocMorphic/cmux-app/pull/1 |
| PR title at handoff | Android cmux companion: native pairing and mobile RPC (draft) |
| Original checkout | `/Users/dharmaydave/me/cmux-app` |
| Last commit before the handoff feature | `6d8a213acd610b4ede1bff45132c35fd414c7ca7` |
| Last signed APK | Build **157**, source `d53abe9b05fc5912dc820738a725360096e7aa80` |
| Successful release workflow | https://github.com/DocMorphic/cmux-app/actions/runs/36360218971 |
| APK SHA-256 | `a847a2f9465cac4ac62883b6401217fac4624bd1f3b85223fbbfb127b1cd1e33` |
| APK size | 9,218,260 bytes |
| Signing certificate SHA-256 | `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` |

The source branch is substantially newer than build 157. Recent Files, shared
Markdown, native text controls, and raw syntax work are **not in that APK**.
No release APK was built or published for this handoff. Keep PR #1 a draft until
the acceptance criteria justify changing it. Inspect current remote state after
cloning; Git history is the source of truth for the handoff commit SHA.

Old temporary download, only while the original Mac/server and tailnet are up:
`http://100.121.78.98:58467/app-release.apk?build=157`. It is not a portable or
permanent release URL. Source APK was `dist/native-d53abe9/app-release.apk`;
served copy was `dist/native-00de40d/app-release.apk`.

Clone the actual work branch:

```sh
gh repo clone DocMorphic/cmux-app -- --branch feature/local-mac-bridge
cd cmux-app
git status --short
git log -5 --oneline
```

Authenticate GitHub on the new laptop normally if necessary. Do not copy tokens
from this laptop. Reuse the existing PR, and attach it to the new Codex task if
that tool is available.

## What transfers through Git, and what does not

All implementation source, tests, vendored assets, provenance, licenses, scripts,
and handoff documentation are committed. `docs/SYNTAX_CHECKPOINT.md` preserves
the current verification facts in portable form.

Ignored/local files do not transfer: `captures/` screenshots and raw logs,
`dist/` APKs (the APK extension is ignored), Gradle caches/build outputs, SDK/AVD,
temporary upstream clones, `local.properties`, pairing/account state, helper
token, and release keystores. Recreate builds and emulator evidence as needed;
do not claim to have inspected old screenshots after a fresh clone. Original
evidence remains on the old laptop. Do not force-add ignored secrets or APKs.

Release signing uses existing GitHub Actions secrets
`CMUX_APP_RELEASE_KEYSTORE_BASE64` and `CMUX_APP_RELEASE_PASSWORD`. A local release
build requires `CMUX_APP_RELEASE_KEYSTORE` and `CMUX_APP_RELEASE_PASSWORD`; use CI
instead of exporting/replacing the private key. Debug has an independent package
suffix `.debug`. Preserve the existing release signer and increasing versionCode
so upgrades work; the build currently takes versionCode from `GITHUB_RUN_NUMBER`
or defaults to 2 locally.

## Source research and how to recover it

- Product: https://cmux.com ; iOS: https://cmux.com/ios ; guide:
  https://cmux.com/docs/ios . Primary code: https://github.com/manaflow-ai/cmux .
- Latest audited upstream pin:
  **`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`**, Sep 27, refreshed Sep 28.
  Recheck upstream drift before a release, but preserve reproducible pins for
  parity comparisons and assets. Upstream is GPL-3.0-or-later; see `LICENSE` and
  `NOTICE.md` plus bundled third-party notices.
- iOS code is primarily `ios/cmuxPackage`, `Packages/iOS`, and
  `Packages/Shared/CMUXMobileCore`. Original sparse clone:
  `/tmp/cmux-official-source` (not transferred).
- Clone upstream separately with `--filter=blob:none --no-checkout`, configure
  sparse paths, then check out the pin. Use `git ls-tree -r --name-only PIN` to
  locate an exact file, then `git show PIN:path` for sparse/unmaterialized sources.
  Broad `git grep` against a partial tree triggered expensive lazy downloads.
- Upstream PR **#10576**, merged Aug 23, removed the iOS GUI agent-chat screen,
  transcript, and composer. Do not rebuild that removed feature as a parity gap.
  Retained `mobile.chat.artifact.*` names support the terminal Files gallery and
  previews. Older local docs mentioning missing GUI chat are superseded.
- Raw syntax dependency: `raspu/Highlightr`, version 2.3.0 at
  **`05e7fcc63b33925cd0c1faaa205cdd5681e7bbef`**, pinned by upstream
  `ios/cmuxPackage/Package.resolved`. Old clone: `/tmp/cmux-highlightr`.
  `scripts/sync-raw-code-highlighter.py <Highlightr-checkout>` reproduces the
  unmodified highlight.js 11.11.1 full 192-language bundle and Xcode dark/light
  styles. Manifest hashes and MIT/BSD licenses are committed.
- Markdown uses a **different**, upstream shared 11-asset bundle (including a
  limited highlight.js common build, Mermaid, Vega, CSS, and shell), reproduced by
  `scripts/sync-markdown-viewer.py`. Do not substitute it for the full raw-code
  highlighter. Hashes are under the respective assets directories.
- iOS source names to begin with: `CmxPairingQRCode`,
  `CmxAttachTicketCompactCoder`, `MobileCoreRPCSession`, `DeviceTreeView`,
  `MobileTerminalRenderGridFrame`, `GhosttySurfaceView`, `TerminalInputTextView`,
  `NotificationFeedView`, `CmuxMobileBrowser`, `CmuxMobileChanges`,
  `TerminalArtifactFilesSheet`, `ChatArtifactFolderView`,
  `ChatArtifactViewerDestination`, `ChatArtifactViewerActionsMenu`.

## Architecture map

Production Kotlin: `app/src/main/java/io/github/docmorphic/cmuxapp/`.
JVM tests: `app/src/test/`; Android fixtures: `app/src/androidTest/`.

| Area | Entry points / important files |
| --- | --- |
| App shell, primary navigation | `MainActivity`, `NativeScreen`, `NativePrimaryNavigation`, `NativeSearch` |
| Account, pairing, transport | `NativeCredentials`, `PairingCode`, `TailscaleRoute`, `NativeConnector`, `MobileFrameCodec`, `MobileRpcClient` |
| Workspace state and hierarchy | `NativeFeed*`, `NativeWorkspace*` |
| Terminal rendering | `RenderGrid`, `RenderGridView`, `TerminalGridPainter`, `TerminalGlyphLayout`, `TerminalDisplay`, `VtTerminal`, `GridVtReplay`, `TerminalStreamMirror` |
| Terminal input/scroll | `TerminalKeyboardView`, `TerminalInputQueue`, `TerminalComposerDelivery`, `TerminalHardwareInput`, `TerminalKeyEncoding`, `TerminalTransport`, `TerminalScrollMotion`, `TerminalViewport` |
| Notifications | `NativeNotification*` (feed, ledger, delivery, service, receiver) |
| Browser | `NativeBrowserView`, `BrowserPageSurface`, `BrowserInputQueue`, `BrowserInteraction`, `BrowserPageGeometry`, `BrowserStreamRecovery`, `BrowserScrollMotion` |
| Tasks/drafts/attachments | `NativeTaskComposerView`, `Task*`, `AttachmentFiles`, `ComposerAttachment` |
| Changes | `NativeChangesView`, `Changes*` |
| Terminal Files | `ArtifactGallery*`, `ArtifactFilesSheet`, `ArtifactRpc`, `TerminalArtifact*`, `ArtifactPathSheet` |
| Shared previews | `ArtifactFilePreview`, `ArtifactPreviewFiles`, `ArtifactShare`, `ChangesPreviewContent`, `MarkdownPreview`, `MarkdownRemoteImages`, `ArtifactText*`, `ArtifactSyntax*` |
| Legacy helper | `bridge/server.mjs`, `BridgeClient`, `BridgeScreen`, `BridgePairingStore` |

The native path is the intended product. The helper polls plain text and exists
as an optional older route; it cannot prove native protocol or full terminal
parity. Vendored Termux terminal emulator sources live in `third_party/termux`.

## Current implementation, known gaps, and prioritization

The table at the top of `PARITY.md` is the detailed area-by-area checklist.
Much of the app is implemented and fixture-tested: account/QR parsing and framed
RPC, workspace hierarchy/mutations, styled grid and VT terminal fallback, direct
IME/composer/attachments, feed and Android foreground-service notifications,
browser controls/input/gestures, task drafts/composition, Changes, Files, previews.
That is not a completed real-device acceptance run.

1. **Close the handoff test gap without starting a redesign.** Reproduce the four
   outstanding syntax/text/Markdown cases below on a responsive emulator or
   authorized test device. Preserve strong assertions; investigate failures.
2. **Prove the primary native workflow on the Pixel and real cmux Mac.** Follow
   the account/identity/Tailscale handshake, workspace, terminal input/output,
   reconnect, and notification path. Inspect current cmux version/capabilities.
   Gather actual errors before changing protocol or proposing workarounds.
3. **Build/publish a combined signed integration APK**, using the existing CI
   signing setup; verify source SHA, package/version, certificate, artifact hash,
   upgrade behavior, and exercised feature path. Do not promise stale build 157
   contains current source. CI success alone is not Pixel acceptance.
4. **Complete remaining parity by source comparison.** Iroh transport is missing
   (v3 QR parse only); v2 Tailscale is implemented. Complete Ghostty/inline graphics
   and pixel scrolling, input modes/rich IME paste, notification server-push
   fallback, browser downloads acceptance, settings network diagnostics/reset,
   Files incremental remote viewing and large-file performance, richer document
   previews, lifecycle/accessibility/battery checks, and UI detail remain open.
5. Source audit says browser downloads currently save on the **Mac** or invoke a
   Mac save dialog; inspected mobile RPC has no download transfer/save-panel
   endpoint. Verify live behavior/upstream changes before declaring an Android
   platform limitation or inventing a new endpoint.

Do not treat arbitrary SF Symbol differences, incomplete implementation, or
fixture-only validation as objectively unavoidable platform differences.

## Exact interrupted feature: raw syntax highlighting

This handoff commits the previously uncommitted implementation, explicitly with
runtime verification incomplete. See `SYNTAX_CHECKPOINT.md` for all 11 outcomes.

- Ported iOS policy: 57 extensions → 35 IDs; `.hs` and `.purs` → Haskell.
  Recognized files highlight through 1,500,000 bytes; unknown language automatic
  detection is strictly below 256,000 bytes. Oversize raw files retain text and
  show an expandable upstream-style Highlighting off pill.
- `ArtifactSyntaxHighlighter` runs bundled JS/CSS in an off-screen WebView;
  displayed text remains a native selectable TextView. No external resources,
  native JS bridge, file/content access, or network loads. Source is JSON data;
  escaped highlighted DOM is converted to UTF-16 foreground/style runs.
- Preserve CR/CRLF, Unicode, and literal markup. Validate unchanged original text,
  contiguous complete ranges, bounds, opaque colors, and style flags before
  applying spans. Late results are fenced by document identity/cancellation.
- `ArtifactSyntaxSpan` changes foreground and monospace bold/italic only; retain
  selection, font size, search background, and viewport. Shared Mutex serializes
  workers; 15-second worker/parse timeout and cleanup exist. Cancellation/renderer
  fault paths were reviewed, not fault-injected; no full recovery claim.
- No worker cache yet (iOS caches its Highlightr actor). Do not optimize based on
  timing from the old severely swapping host without representative measurements.
- Exact iOS files: `Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Artifacts/`
  `ChatArtifactSyntaxHighlightPolicy.swift`, `ChatArtifactSyntaxHighlighter.swift`,
  `ChatArtifactTextViewCoordinator.swift` (EOF/generation fences and preserving
  selection/scroll when applying styles), and highlighting status-pill policy.

### Last verification, and what to do next

The full JVM result is **307 tests, 0 failures/errors/skips**. Debug and Android
test APK assembly succeeded. The mapping and all three vendor hashes were checked;
all 35 mapped language IDs exist in the 192-language engine. The new five-case
Android syntax fixture compiled and four cases passed. One failed at a clipboard
read; the broader run had three failures and an unfinished final case.

The first Android launch crashed before tests due to BIND APPLICATION ANR. After
cold boot, the 11-case run recorded **7 passes, 3 failures, 1 started/no result**.
A screenshot showed a **System UI isn't responding** modal covering and dimming
the app. Keyguard was false. It plausibly explains null clipboard access and
gutter-pixel failure, but this has NOT been proven with a rerun. Never relabel
these as passing. The old emulator and instrumentation are no longer running;
all old exec session IDs are invalid. Start a fresh environment on the new laptop.

After confirming foreground focus and no system modal, rerun these exact methods
(class prefix `io.github.docmorphic.cmuxapp.`):

```text
NativeArtifactSyntaxTest#nativeLateColoringPreservesSelectionSearchFontViewportAndClipboard
NativeArtifactTextTest#searchWrapsAndJumpsToMatchesAndLineControlsMoveActualViewport
NativeArtifactTextTest#selectionAndCopyContentsPreserveNewlinesUnicodeAndExcludeLineNumbers
NativeMarkdownPreviewTest#sharedRendererDisplaysTablesCodeMermaidAndVegaAndSwitchesToRaw
```

The syntax selection case reached/passed its text, selection, font, viewport, and
search-span assertions before failing on `primaryClip!!`; it still counts as a
failed test. It saves `files/artifact-syntax.png` only after all assertions. The
old local `captures/artifacts/artifact-syntax.png` is **invalid** (58-byte missing
file error, not a PNG). Capture it afresh after a pass, verify magic, and inspect.
The oversize test uses small content with large declared size: policy/UI proof,
not large-file performance. Do not silently expand assertions into broader claims.

## Implementation lessons worth preserving

- Files requests retain immutable terminal/session authorization; keep relative
  paths for Mac resolution. Never broaden access scopes when opening descendants.
  Changes has separate fingerprint/revision validation; do not conflate the two.
- File sharing exports original streamed bytes with read-only FileProvider grants,
  cleans cancelled partials, and lets exported snapshots survive sheet closure.
- Path tap parity: 1,217 Swift reference cases (246 Unicode) matched Android's
  actual ICU widths. Count state parity: 6,400 transitions plus 33 path cases.
  Generation scripts are committed; preserve their upstream provenance.
- Native raw viewer: one continuous selectable buffer, UTF-16 line starts, exact
  newline/Unicode copy, logical line numbers, literal non-overlapping search.
  Wrap/font persist independently for CODE/LOG/PLAIN; default 15 sp, bounds 8–28,
  logs default no-wrap. Don't alter source text to implement the gutter.
- HorizontalScrollView ignores child width for wrapping: constrain TextView
  measurement and set wrap width before the first measure, not only onSizeChanged.
  Gutter drawing needs Canvas save/restore around super.onDraw and invalidation
  on ancestor scrolling, otherwise hardware-cached line numbers disappear.
- Pinch accumulates native font locally and cancels child gesture on start.
  Test spans must exceed Android's threshold (used ±15% → ±40% of screen width).
  Await actual layout/scroll and a distinct reopened native view in fixtures.
- Sheets are separate windows: `findArtifactTextInWindows()` uses WindowInspector,
  not only Activity decorView. Test hosts need Surface and safe drawing insets.
- Shared Markdown needs a scoped dark ContextThemeWrapper; legacy Activity theme
  otherwise yields wrong CSS. Allow initial data/about main-frame loading, then
  block unauthorized navigation/resources. Relative images/local Markdown links
  remain unresolved in the inspected iOS host too.
- Remote Markdown images require per-image consent. Transport uses validated DNS
  addresses for the actual HTTPS connection, port 443, private/reserved-address
  rejection, same-host redirect limit 3, image MIME allowlist, decoded 8 MiB cap.
  Live HTTPS success, crash recovery, full zoom/accessibility remain unverified.
- Terminal input queues must not replay uncertain keystrokes after timeouts.
  Retain ownership/generation guards on reconnect, activity changes, and late RPCs.

## Build/test operations on the new laptop

Requirements: JDK **17**, SDK platforms **37.0 and 36**, build-tools **36.0.0**, platform-tools,
Node **22** for helper tests, Python 3 for source generators. Wrapper: Gradle 9.3.1;
AGP 9.1.1; built-in Kotlin/Compose compiler 2.4.20. Android min 26, target 36,
compile 37 except the API 26–27 fingerprint adapter (compile 36). See
[ANDROID_TOOLCHAIN.md](ANDROID_TOOLCHAIN.md). Android
17/API 37 was used for emulator acceptance; choose an image for the new CPU.
Set JAVA_HOME and ANDROID_HOME for that machine; do not copy old absolute paths.

```sh
node --test bridge/*.test.mjs
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest
# At a combined runtime checkpoint:
./gradlew --no-daemon --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest
adb -s DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w -r -e class 'FULL_CLASS#METHOD' \
  io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Use comma-separated fully qualified selectors for a focused batch. Inspect the
instrumentation **`OK (N tests)` / failure summary**, not merely adb's exit code.
For captured fixtures, use `adb exec-out run-as io.github.docmorphic.cmuxapp.debug
cat files/NAME.png`, then check the resulting file is a PNG before viewing it.

The old Mac has only 8 GiB RAM and reached ~8.7 GiB swap during failed runs.
Emulator + Gradle together made timeouts/ANRs worse. Stop the owned emulator
before Gradle; run one worker, then launch emulator after builds finish. Do not
kill other user apps/agents. A stale `cmux_ready` snapshot had startup failures;
cold boot with `-no-snapshot-load -no-snapshot-save` was used, but the system modal
still appeared. Do not reuse those timings as an app performance benchmark.

CI `.github/workflows/android.yml` deliberately skips draft PR builds and has no
push trigger. A combined release checkpoint can be dispatched with:

```sh
gh workflow run android.yml --ref feature/local-mac-bridge
```

The workflow tests helper/JVM, assembles debug/test/release APKs, verifies signature,
and uploads `cmux-app-stable-signed-apk` and `cmux-app-preview-apk`. It does **not**
run Android instrumentation or real-device acceptance. Workflow skip ≠ pass.

## Mac/phone access and outstanding authorization

- User authorized the old helper on the original Mac's Tailscale address, port
  **58466**, using a private pairing token. APK server used **58467**. Their live
  status must be checked; do not assume moving the repository moves a service.
- The **native cmux listener on 58465 remained OFF** after an earlier automatic
  approval review rejected enabling it. Do not enable it or bypass the restriction
  from the new laptop based solely on permission to clone/continue development.
  Prepare the concrete native test path and explain the existing review block
  before requesting the needed approval/user action. The original rejection's
  detailed text is not retained in this handoff; do not invent a reason.
- Helper needs to run inside a cmux terminal because cmux restricts its socket
  ancestry. Read `bridge/README.md`; don't make a public proxy to work around it.
- Never print, commit, or transfer `~/.config/cmux-app/bridge-token`, private
  pairing URLs, account tokens, or `/tmp/cmux-helper-terminal.log` (may contain
  credentials). Re-pair through the app as appropriate. The published checksum
  and certificate fingerprint are public integrity metadata, not private keys.
- A different laptop may not have access to the original Mac, tailnet, Pixel,
  accounts, or signing secrets. Inspect available prerequisites; continue
  independent repository work while asking only for genuinely missing access.

## Completion standard

Keep code, parity tracker, and evidence aligned. Record exact source/build tested,
test counts and failures, device/OS/cmux versions, and remaining limits. Finish
with an installable signed APK verified on the user's Pixel against their Mac,
not only an emulator-local peer. Keep committing and pushing the same project.
