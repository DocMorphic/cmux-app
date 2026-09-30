# Phone-local browser parity audit

Status: audited 2026-09-30; **implementation and acceptance remain open**.
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

No local browser is implemented by this audit. The Simulator milestone APK does
not include it.
