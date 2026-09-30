# Phone-local browser parity audit

Status: resolver, state foundation and WebView pane implemented 2026-09-30;
**workspace navigation integration and physical acceptance remain open**.
Reference revision: `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

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

No local browser is exposed through the main app yet. Signed Simulator build 257
predates both checkpoints. Next: `New Browser` remote creation/local fallback,
stale-request cancellation, account/team/Mac/workspace routing and close-to-terminal,
followed by physical acceptance. The goal remains incomplete.
