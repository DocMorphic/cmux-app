# Phone-local browser parity audit

## Embedded web video fullscreen — 2026-10-07

`LocalBrowserWebHost` now implements both WebChromeClient custom-view callbacks.
Previously it supplied neither, so Android WebView did not advertise fullscreen
support. The Activity-owned fullscreen window hosts the renderer's video controls,
hides system bars, keeps the screen awake while open, and restores the page when
Back dismisses it. Duplicate requests are rejected; navigation, backgrounding,
UI detach, release and renderer death retire the window. Activity recreation exits
fullscreen before detaching the retained WebView, preserving its DOM and draft.

Source comparison: official iOS revision
`186cec79781256867ad4516f0802118738bd2393`,
`Packages/iOS/CmuxMobileBrowser/Sources/CmuxMobileBrowser/MobileBrowserView.swift`,
lines 55–88, configures WKWebView with inline media playback enabled. This is a
scoped embedded-media audit, not evidence of every iOS HTML fullscreen behavior.
Android's [WebChromeClient contract](https://developer.android.com/reference/android/webkit/WebChromeClient#onShowCustomView(android.view.View,%20android.webkit.WebChromeClient.CustomViewCallback))
requires custom-view hosting and the corresponding hide callback for fullscreen.
Global source pins are unchanged.

**Two Android checks passed together in 21.602 seconds** on the existing Android
17 / 16384-byte-page AVD. The first covers duplicate rejection, callback counts,
renderer-initiated exit, detachment and idempotent cleanup. The second uses an
actual HTML video with the existing generated two-track MP4, a real user tap and
WebView fullscreen rendering. It checks visible gold/blue video pixels, advancing
playback, Back, exact retained WebView identity, draft preservation and unchanged
page request counts across Activity recreation. Both screenshots were inspected.
The latter uses the debug lifecycle Activity with the production browser host and
owner; separate-process routed browser/proxy and physical Pixel/Mac acceptance
remain open, as do wider media lifecycle, accessibility and iOS visual comparison.

Debug/test APK assembly passed. Local evidence is in
`captures/runtime/browser-fullscreen/` (ignored): runner output, screenshots and
before/after ANR/crash buffers. Those buffers were empty for this boot. Gradle was
stopped before the emulator boot; the sole AVD was then stopped and reaped.
No extra AVD or signed release was created; published signed APK remains **616**.

Debug APK SHA-256:
`2aa667879422d4cbd2c64d6f9ed125c8a9f4689e759accf0384e1b3f3c6b5bc6`.
Test APK SHA-256:
`a5170ffee9b60e48e264b5832147acb93bb7462f65e1a3c65cbfcd4bf980060d`.

## Browser recreation with a binary Changes preview — 2026-10-07

The production routed browser and Changes sheet now have a passing Android
integration check for a font-scale change while a binary PDF is open. The case
uses the real separate-process `RoutedBrowserActivity`, bound host service,
sidebar, main-process `RoutedChangesActivity` and PDF viewer, with generated
network responses and document bytes. No credentials or real Mac are used.

`globalSidebarChangesBinaryPreviewAndDraftSurviveBrowserParentRecreation`:

- Creates an unsent JavaScript draft in the routed browser, opens Mac B's Changes
  from the shared sidebar, and selects page two of a two-page PDF.
- Changes Android font scale to force recreation. It observes a different sheet
  Activity with the same retained model and artifact file, checks the page-two
  pixels are green, and verifies that no Changes request was repeated or sent to
  Mac A. Android's `wm_relaunch_activity` event explicitly identifies the real
  browser Activity, so this is more than a resize of the earlier debug WebView host.
- Dismisses the sheet and verifies the browser's draft title and original page
  request count, then checks preview-file cleanup and browser-host lease release.
- Restores font scale and the display override. A debug-only manifest entry
  prevents the bare Compose harness from being recreated by font scale; neither
  production Activity handles that change itself. The existing harness Activity
  is now included in the release verifier's dynamic exclusion inventory (12 entries).

**Final result: one Android case passed in 19.344 seconds** on the existing
`cmux_api37_16k` AVD, Android 17 with 16384-byte pages. The signed-APK verifier's
three Python unit checks also passed; no signed APK was built or verified here.
Final screenshots were inspected: page two is visibly rendered at enlarged text
size, and the browser returns with its draft and sidebar. The final boot's event
buffer has no ANR/crash events and its crash buffer is empty. Settings returned to
font scale 1.0 and physical 1080×2400 with no override; ADB was non-root. Gradle and
the emulator were stopped, and the emulator process was reaped.

Failed/interrupted attempts remain in `captures/runtime/browser-binary-recreation/`:
the original bitmap wait did not advance the Compose test clock; a later attempt
hit a bind-application ANR before any test started, with the saved main-thread
trace in ART's instrumentation initialization; another runner disappeared without
a final result. After resumption, all recovery assertions passed but cleanup
looked for a back button absent from the tablet layout. Using Android Back gave
the final complete pass. These attempts do not count as successful runs.

Final debug APK SHA-256:
`990aaa2c62e16b0b075f09ad4f981455ba017078027651a995e8026c7e961a8c`.
Final test APK SHA-256:
`d3fa59b445007065488d5684ad99e697e5e9309a66b2f9d4c7e4cbe1328ffe9c`.

This verifies the PDF/browser recreation path. Media/PiP and pending Save from
this route, renderer/process death, actual host freshness, and physical Pixel/Mac
acceptance remain open. Browser Changes previews run in the main process; this
does not exercise the separate `BrowserMediaPlaybackActivity` fallback. Global
upstream pins are unchanged. Published signed APK remains **616** and full parity
remains unverified.

## Retained browser view and Changes rotation — 2026-10-05

The routed browser controller now owns one `LocalBrowserWebOwner`. Activity
recreation can reattach its existing WebView instead of destroying the DOM and
loading its URL again. A mutable context wrapper is rebound to the current
Activity while attached and to the application while detached. Activity-owned
file-picker callbacks are cancelled/cleared on detach and replaced on attach;
a stale composition release cannot detach a newer lease. Replacing the surface
or clearing the controller retires the view and its work. This is in-memory
retention; it does not persist a live DOM through process death.

**Four Android checks passed in 79.876 s** on the sole 16 KB emulator:

1. A debug lifecycle host uses the production owner, pane and WebView. Two full
   Activity recreations retain the exact WebView, unsent input value, JavaScript
   counter and back history without another page request. Back/Forward still
   work. Closing the host destroys/detaches the view and replaces its Activity
   context with the application context. `RoutedBrowserController` is wired to
   that same owner; the explicit `ActivityScenario.recreate()` calls in this
   check target the debug host, not the separate-process Activity.
2. Production routed Changes sheet: open Mac B's diff while the browser has an
   unsent JavaScript draft, recreate the sheet and rotate both ways. The same
   sheet model and exact Mac B requests survive without refetch. Dismissal
   returns to the unchanged page; removing Mac A's capability closes its next
   sheet, retires leases and preserves the browser draft without reload.
3. Existing production routed-browser rotation/history/host-lease regression.
4. Real system picker cancel, reopen and multipart upload through the routed
   browser process, verifying the uploaded bytes.

Three screenshots inspected; no crash entries or new ANR events during the run.
Window/sleep settings unchanged after the wide-screen test restored its override;
emulator stopped/reaped. No Pixel/Mac workflow was exercised. Evidence is in
`captures/runtime/browser-view-retention/`, including build/instrumentation logs,
source/APK hashes, event baselines, settings and screenshots. The new debug-only
`RetainedBrowserTestActivity` is now the ninth debug Activity; the release
verifier inventories those names dynamically and must check all nine on the next
signed build. Signed **596** remains the verified download and does not include
this change. No global upstream pin was advanced by this Android lifecycle fix.

Still open: forced recreation of the separate-process parent while a binary
Changes preview is open, renderer/process death, live-host acceptance, pending
picker restoration, and media/text/Save viewer restoration. The legacy direct
(non-routed) fallback still uses its existing fresh-WebView remount policy.

The following initial audit is historical; newer checkpoints supersede its
pending implementation statements.

Status: resolver, state foundation, WebView pane and workspace navigation
implemented 2026-09-30; **physical acceptance remains open**.
Reference revision: `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

The newer `204a11d` candidate adds per-computer Mac routing and ephemeral website
storage. Its native tunnel and SOCKS/router pass JVM and Android socket checks;
session owner binding and policy refresh also pass 40 focused JVM checks. They
are not yet connected to this WebView. A separate-process adapter passes two
Android checks for routing and storage isolation, but production presentation and
session retirement remain open. See [BROWSER_TUNNEL.md](BROWSER_TUNNEL.md) for the
reviewed contract, Android isolation requirements and remaining implementation.

The authoritative module is
`Packages/iOS/CmuxMobileBrowser/Sources/CmuxMobileBrowser/`, specifically
`MobileBrowserPane.swift`, `MobileBrowserView.swift`, `BrowserSurfaceState.swift`,
`BrowserSurfaceStore.swift` and `BrowserURLResolver.swift`.
`WorkspaceDetailView+Surfaces.swift` mounts it for `.browser`; `.browserStream`
mounts the separate Mac-rendered stream. Android's existing `NativeBrowserView`
implements the latter and cannot establish parity for the local pane.

## Required behavior from the pinned implementation

- `WorkspaceDetailView.openBrowserFromToolbar` first attempts to create a real
  Mac browser when `supportsBrowserStreamCreate` is available. A missing
  capability or rejected/failed creation opens the phone-local fallback. A
  per-request UUID prevents a late creation result from changing a newer route.
  Opening the fallback stops browser/simulator streams and clears selected Mac
  surfaces. Selecting a terminal, Mac surface or stream closes the local pane.
  A one-shot last-opened-local-browser restoration intent can reopen it.
- One optional phone-local surface per workspace, with stable surface identity.
  The store lives outside Mac workspace snapshots, so inventory refreshes cannot
  overwrite local browser state. Opening an existing surface reveals it; closing
  removes it and returns to the terminal. The default new URL is
  `https://duckduckgo.com/`.
- Top chrome: Back, Forward, editable address/search, Reload or Stop depending on
  loading state, and Close Browser; two-point progress line above the WebView.
  Back/Forward are disabled when unavailable. Address typing is preserved across
  redirects and URL callbacks. Successful address submission dismisses focus.
- State tracks current URL/title, address editing, loading/progress, navigation
  flags and navigation errors. Pending navigation/load commands are consumed once.
- Persistent phone cookies/localStorage and inline media playback. This source
  does not synchronize web sessions with the Mac. Remount reloads the last URL;
  the pinned store explicitly does not promise history preservation across remounts.
- A new-window/`target="_blank"` request opens in the current pane. Native
  workspace back navigation must remain available; browser history uses its chrome.
- Resolver behavior is more detailed than prefixing `https://`: explicit web
  URLs, domain/path heuristics, IPv4/IPv6 and local HTTP defaults, search query
  encoding, scheme-less userinfo rejection and safe wrapped-URL handling. Typed
  non-HTTP(S) schemes are not loaded. Port the source tests and run a Swift/Android
  corpus comparison before claiming equivalent behavior.

## Workspace lifetime checkpoint (2026-09-30)

Local pages and restore intents now retire when their owning Mac publishes a
connected, complete workspace snapshot confirming that the workspace is gone.
Missing, offline, reconnecting and unhydrated snapshots preserve retained pages.
Workspace metadata changes update the local header without replacing its page.
The same check fences a browser-create response before either remote navigation
or local fallback, even when Compose has not processed the refreshed inventory.
Colliding workspace IDs on another Mac remain independent.

The feed coordinator rejects missing/non-array workspace lists, invalid or blank
workspace IDs and duplicate IDs before publishing an authoritative snapshot.
A rejected snapshot preserves the previous inventory; a valid empty list confirms
absence. Optional pane metadata retains the existing parser behavior.

Verification: **37 JVM tests passed** (13 navigation, seven store/state and 17 feed
coordinator cases), and debug/instrumentation APKs built. **Six full-screen Android
17 / 16 KiB browser-routing tests passed in 15.714 seconds**, including a delayed
create response whose owner workspace disappears during the request. No fallback
or stream is opened; another workspace remains usable. Emulator stopped afterward.
Ignored evidence: `captures/runtime/workspace-lifetime/`; build log:
`/tmp/cmux-workspace-lifetime-build.log`. Physical Pixel/Mac acceptance remains open.

- Debug APK SHA-256: `a377eee659e0c02362f5f71f5524ede2c34977fdb83b817d0a60748709afce3a`.
- Test APK SHA-256: `357f2eee6702305b55b04a9a9029cc330a1cb12a287ee31fe113e19be54e594d`.

Signed build 261 predates this fix. This checkpoint does not implement the full
persisted last-tab policy audited in [WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md).

## Android implementation entry points

1. Port the `New Browser` creation/fallback and stale-request rules above. The
   Mac's `kind=browser` descriptors alone cannot represent this local route.
2. Add a separate local state store keyed by account/Mac/workspace identity; the
   Android app aggregates multiple Macs, so raw workspace IDs alone can collide.
   Keep the store independent of `parseWorkspaces` and live inventory updates.
3. Implement a dedicated Android WebView pane and resolver. Review WebView
   settings explicitly: local browsing is not an artifact viewer and must not
   inherit privileged JavaScript interfaces or file-access settings from one.
   Preserve upstream behavior without disabling TLS checks.
4. Route it through `NativeScreen` and the workspace surface picker. Extract
   composables/helpers rather than growing the already large `NativeScreen`
   method. Account changes must retire the old local route/state.
5. Verify against a local HTTP fixture: actual page pixels and interactions,
   redirects while typing, back/forward/reload/stop, popups, cookies/storage,
   remount URL restoration, close-to-terminal, inventory refresh and cross-Mac
   identity isolation. Then perform real Pixel/Mac workflow acceptance.

## Resolver and state foundation (2026-09-30)

`LocalBrowserAddress` implements the audited address policy. The reproducible
`scripts/generate-local-browser-fixtures.py` runs the pinned, unmodified Swift
resolver and records its SHA-256. Its **103 reference cases** cover the upstream
test scenarios plus Unicode/IDNA, IPv6, authority boundaries, control/wrapped
characters, ports and percent encoding. Android matches every exported string.
International hosts use Android's built-in nontransitional UTS 46 IDNA processing
instead of mapping distinct domains such as `faß.de` and `fass.de` together;
see the [Android IDNA API](https://developer.android.com/reference/android/icu/text/IDNA).
The desktop tests use ICU4J 77.1 as the counterpart; it is not packaged in the app.

`LocalBrowserSurface` holds address editing, committed URL/title, navigation and
loading state, once-consumed load/command requests and a per-attachment callback
token. Remount restores the URL and resets old history flags; detached/closed
views cannot overwrite a new attachment. `LocalBrowserStore` keeps one surface
per account/team/Mac/workspace, preserves it across unrelated inventory refreshes,
supports one-shot restore intent and retires state on account/team changes.
Neither class performs Mac RPC or holds a WebView.

Verification:

- **10 JVM tests passed**: three resolver tests (including the complete Swift
  corpus) and seven state/store tests covering consumption, redirects during
  editing, remount, stale callbacks, navigation state and identity isolation.
- **One Android 17 / 16 KiB instrumentation test passed in 0.048 seconds**, running
  all 103 cases through the production resolver with Android's actual URI/ICU
  implementations. This is resolver runtime evidence, not page-loading/UI evidence.
- The production debug APK contains neither the golden corpus nor ICU4J test
  classes. Emulator stopped after the check; ignored evidence is under
  `captures/runtime/local-browser-foundation/`.
- Debug APK SHA-256: `21fed74887c73f0c25ea5de803135fd74dd93c6738b3cf269bc50a4331717031`.
- Test APK SHA-256: `865279272ab136199c4c05a905742f8a8f3b0a2225a3b209acec95eeadd4de1f`.

## WebView and chrome checkpoint (2026-09-30)

`LocalBrowserWebHost` owns and disposes one WebView per mounted pane. It applies
pending commands once and fences callbacks by WebView identity and surface
attachment. It enables JavaScript, DOM storage, persistent cookies and pinch zoom,
with no JavaScript bridge or file/content URL access. TLS errors are cancelled;
page failures offer Retry. Renderer termination destroys the affected view and
Reload constructs a fresh one with the last URL. Lifecycle changes pause/resume
only the owned view, without globally pausing other WebView timers.

`LocalBrowserPane` adds address/search, Back/Forward, Reload/Stop, Close and loading
progress. Redirects preserve active address edits; Go dismisses input focus and
the keyboard. File selection uses the Android picker, single/multiple content
URIs, cancellation and an outstanding-result reservation across surface changes
and Activity recreation. Selection results from retired views are discarded.

Android's [WebSettings multiple-window contract](https://developer.android.com/reference/android/webkit/WebSettings)
allows new-window links and user-initiated `window.open()` to replace this page
when multiple-window support is disabled. Runtime tests verify both, and verify
that a POST form with `target="_blank"` retains its method and request body.
File selection uses the [WebChromeClient API](https://developer.android.com/reference/android/webkit/WebChromeClient);
renderer cleanup follows [WebViewClient](https://developer.android.com/reference/android/webkit/WebViewClient).

Verification:

- Debug and instrumentation APKs build successfully.
- **Seven Android 17 / 16 KiB tests passed in 16.438 seconds**: rendered navigation,
  Back/Forward/reload/close callbacks; popup links/script/POST; cookies/localStorage
  and URL restoration with a fresh view/history; redirect while editing and Go;
  Stop/network failure/Retry/settings; stale/cancelled file-result ownership;
  actual renderer termination and fresh-view recovery.
- Painted red/green/blue page regions were checked in screenshots. The first-page
  and recovered-page screenshots were also inspected. Fixture Activity system
  bars differ from the main app; this is not full-app theme or physical acceptance.
- The original network-error fixture used a pre-request disconnect policy that
  the dispatcher did not trigger. It was corrected to disconnect after receiving
  the request, and the complete seven-test class passed with real WebView errors.
- File-result state is exercised, but an end-to-end system picker upload remains
  an acceptance item. No Pixel account/data was touched; ADB still listed no
  physical device after the user reported a USB connection. Owned emulator stopped.
- Debug APK SHA-256: `c143be95581511911f86ac52e01fcc690eba3dc58726e221092416833ec8aab2`.
- Test APK SHA-256: `41113f9f5d24365768e4507ee4553b232a3fbe8e41d9f7d62f8068e5c2400aa5`.
- Ignored evidence: `captures/runtime/local-browser-pane/`; build/runtime logs in
  `/tmp/cmux-local-browser-pane-build.log` and `/tmp/cmux-local-browser-view-runtime.log`.

This pane checkpoint alone did not expose the browser. The following navigation
checkpoint enables it; signed Simulator build 257 predates both.

## Workspace navigation checkpoint (2026-09-30)

`LocalBrowserNavigation` is retained by `NativeFeedSession`, separately from Mac
inventory. Workspace actions and terminal/Mac-surface pickers expose `New Browser`.
Remote creation requires a connected owner advertising both `browser.stream.v1`
and `browser.stream.create.v1`. The request uses that row's Mac and workspace,
with no foreground-Mac substitution. A valid response must carry a nonempty string
panel ID and the requested workspace ID. Unsupported/offline/rejected/malformed,
timed-out or unknown outcomes open the local fallback. The mutation is never
automatically replayed; repeated taps during the same request do not duplicate it.

Pending creation has progress/cancel UI, a generation guard and captured navigation
and authorization checks. Navigation, cancellation, account changes, computer
removal or disposal prevent late results from changing the newer screen. View
disposal cancels pending callbacks while retaining the separate local page state.
The main screen's terminal header/tabs and remote-browser branch were extracted
after hitting Kotlin's per-method bytecode limit; final debug/test builds passed.

The local page works without a live Mac connection. Identity uses the verified
account/team when available, the saved Mac's account scope while refresh is offline,
or the login incarnation for legacy unscoped pairings. Sign-out, a new login or a
changed verified scope retires old state. Mac origin and workspace ID are always
part of the key. No Mac credentials or RPC client enter the WebView.

`LocalBrowserWorkspaceView` supplies the workspace header and pane picker. Back
keeps a one-shot restore intent; reopening that workspace remounts its page with
the saved URL. Close and explicit terminal/Mac-pane selection remove the local
surface, returning to the selected terminal when it still exists. Notification
selection also retires a local tab in its target workspace. Workspaces with no
Mac panes remain tappable when they have a local tab.

Verification:

- **17 JVM tests passed**: ten navigation/identity/descriptor checks and seven
  surface/store regressions. Coverage includes both capability gates, fallback
  without sending, one-send failure/timeout handling, remote success, cancelled
  uncooperative responses, duplicate clicks, stale guards, one-shot restore,
  return-terminal retention and account/team/Mac isolation/retirement.
- **Five Android 17 / 16 KiB tests passed in 12.918 seconds** through the actual
  `NativeScreen` and a local framed-RPC peer: terminal-menu fallback and
  Back/restore/Close; empty-workspace reopening; malformed remote response
  fallback and picker selection; successful creation in the requested workspace;
  cancellation followed by another terminal. These account fixtures explicitly
  refuse a physical device. They were run only on the owned emulator.
- Local HTTP page pixels and the full workspace/browser chrome were captured;
  the screenshot was inspected. The capture occurs during keyboard dismissal,
  so it does not establish settled IME or physical rotation acceptance.
- The initial remote-success fixture used `id` instead of the protocol's
  `surface_id` in the workspace inventory. Correcting the fixture allowed the
  remote panel route to pass. The separate WebView tests from the previous
  checkpoint remain seven passing tests; they were not rerun for routing-only edits.
- Debug SHA-256: `a79f241df6bc7b986abaeedf358d18470f30516fb909c68a9a1d6756051b1153`.
- Test SHA-256: `cf9a4b9297015f86daaf9b44337114955bfb9cee4db2bc576d7eaef33376eb52`.
- Ignored evidence: `captures/runtime/local-browser-routing/`; final build log
  `/tmp/cmux-local-browser-routing-final-build.log`, runtime log
  `/tmp/cmux-local-browser-routing-runtime.log`. Owned emulator stopped afterward.

## System picker and Activity recreation acceptance (2026-09-30)

**Three Android 17 / 16 KiB tests passed in 15.325 seconds**, using the production
screen in the existing debug-only Activity host:

- A generated text file was selected through Android's real document picker.
  Submitting the HTML form sent a multipart POST to the local fixture server;
  the filename and exact UTF-8 file bytes were verified in the received request.
- Recreating the Activity retained the local workspace tab and URL, mounted a
  different WebView, and reloaded the page from the local server.
- Recreating behind an open system picker discarded the old selection rather
  than attaching it to the new WebView. A second selection and byte-verified
  upload then succeeded, proving the outstanding-result reservation clears.

Each test also waited for the actual Android IME inset to become hidden after
address submission. The fixtures refuse physical devices, use only generated
files and credentials, and delete their own content-provider files afterward.
Picker, restored-page and upload screenshots are under ignored
`captures/runtime/local-browser-lifecycle/`; the uploaded-page screenshot was
inspected. It captures a return transition and a debug fixture Activity, not a
settled full-app theme/accessibility acceptance result. The emulator was stopped.

UI interaction uses the documented [UI Automator APIs](https://developer.android.com/training/testing/other-components/ui-automator-legacy).
The generated file is inserted through [MediaStore.Downloads](https://developer.android.com/reference/android/provider/MediaStore.Downloads).
UI Automator is an instrumentation-only dependency and is not packaged in the app.

- Production debug APK remains byte-for-byte unchanged:
  `a79f241df6bc7b986abaeedf358d18470f30516fb909c68a9a1d6756051b1153`.
- Test APK: `c6c458a61de19496f9829e01320c70189424d35bda3de74f68ddc28caf0a74f4`.
- Logs: `/tmp/cmux-local-browser-lifecycle-build.log` and
  `/tmp/cmux-local-browser-lifecycle-runtime.log`.
- Signed build **261** at `3b0f6fd` passed full integration CI, signature/native/ZIP
  verification and 16 KiB Android 17 launch. It includes the production browser
  milestone; see [PIXEL_INSTALL.md](PIXEL_INSTALL.md) for download and hashes.

Remaining acceptance: real Pixel/Mac creation and offline fallback, physical
picker/rotation, landscape/TalkBack/large text and cross-Mac use. Review workspace
deletion during an outstanding create and longer-lived last-opened-tab restoration
in the broader navigation audit. The full Android parity goal remains incomplete.
