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

**Delivery update — 2026-10-03:** signed development build **474**, source
`c45920e8ba20c036bd3ca7c11491a6e03c42d763`, passed CI, independent packaging checks
and a signed-out 468 → 474 upgrade on the existing API 37 / 16 KB emulator.
See [PIXEL_INSTALL.md](PIXEL_INSTALL.md) for the artifact and hashes, and the latest
[PARITY.md](PARITY.md) entries for feature evidence. The goal remains active on
this Mac; physical Pixel acceptance, authenticated upgrade, configured push and
broader source/visual parity remain open. The repository/delivery table below
records the original September 28 handoff rather than the current APK.

## User's objective and working preferences

Latest local feature: [MAC_COMPATIBILITY.md](MAC_COMPATIBILITY.md) adds minimum
Mac-version admission, a cached remote policy and update guidance. It has 85
passing focused JVM tests and two passing Android UI tests. Signed build 474
does not include it. Next implement the separate audience policy and durable
offline warnings, then resume physical acceptance when the Pixel is available.
The goal remains active; do not repeat the already-pending Pixel reconnect request.

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

Requirements: JDK **17**, SDK platform **36**, build-tools **36.0.0**, platform-tools,
Node **22** for helper tests, Python 3 for source generators. Wrapper: Gradle 8.13;
AGP 8.13.2; Kotlin/Compose plugin 2.2.21. Android min 26, target/compile 36. Android
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
