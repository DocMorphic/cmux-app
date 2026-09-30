# Pixel 6a install and native pairing check

This is the direct cmux Android build on `feature/local-mac-bridge`. It is still
a development build; see [PARITY.md](PARITY.md) for the unverified features.

## Current installed checkpoint — 2026-09-30

After signed 274 verification, the Pixel appeared in ADB again. Debug app
`1d5958f` was installed in place over `5d24946`, without clearing app data.
The installed `base.apk` SHA-256 matches the tested local build:
`e62b09d6cf6c5272e2ab6326b6fbc50cb6031e12416949f94571451dc7859c4d`.
Install/hash evidence is in ignored `captures/runtime/pixel-1d5958f/`.
The phone was locked/dozing at installation, so a new unlock request is pending
before native UI acceptance. Its plugged-in stay-awake setting remains `0`.
This installs the debug package; the signed 274 package below remains separate.

The Android Iroh/V2 connection is implemented and earlier checkpoints have real
Pixel/Mac evidence; see [IROH_V2.md](IROH_V2.md) and [NETWORKING.md](NETWORKING.md).
This replaces the old statement that Iroh integration was unfinished.

## Signed integration build 274 — 2026-09-30

[Build 274](https://github.com/DocMorphic/cmux-app/actions/runs/36681430449)
passed from `1d5958fe9982606f7aefaab514bbf02ae5221343`. Download the
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36681430449/artifacts/11082157032)
and extract `app-release.apk` (repository access required). This is the newest
verified signed development APK, superseding 261.

- Package `io.github.docmorphic.cmuxapp`, version code **274**, version `0.2.0`.
- SHA-256: `a20471ffccbb09fc808b11fe630a4073b8a5f825979c13b266bb7b614795e396`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
  It matches the previous stable builds; signing settings are unchanged.
- Includes remembered-pane/default selection, new-terminal startup deadlines,
  ordered workspace refresh, empty/delayed pane discovery, Activity and real
  process-death restoration, and consumed pairing/notification entry routes.
  See [WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md) and
  [ENTRY_ROUTES.md](ENTRY_ROUTES.md) for the separate runtime batches.
- CI passed the complete app/Ghostty JVM tasks, four helper tests, viewer asset
  checks, debug/instrumentation/release builds, signature and native/ZIP checks.
  The downloaded APK independently passed `apksigner`, all five native libraries'
  LOAD/RELRO checks and `zipalign -c -P 16`.
- Installed successfully over signed 261 on the API 37 / Android 17 emulator with
  16,384-byte pages. Version 274, `arm64-v8a` and `pageSizeCompat=0` were confirmed.
  The sign-in screen was visually inspected without a compatibility warning.
  `am start -W` reported successful WARM launch, total time 1,060 ms. This is
  upgrade/launch evidence; native sign-in and Mac workflows remain untested on
  this signed package. The owned emulator was stopped afterward.
- Signed 274 was not installed on the Pixel. The debug package was subsequently
  updated in place when USB reappeared; see the current checkpoint above.
  Nested file/detail state, unacknowledged creation, full push,
  broader phone acceptance and the new upstream audit remain open. See
  [UPSTREAM_REFRESH_2026_09_30.md](UPSTREAM_REFRESH_2026_09_30.md).

Local verified APK: `build/signed-run-36681430449/app-release.apk` (ignored).
Evidence: `captures/runtime/signed274/` (ignored). Stable and debug installations
have separate app data and logins. The older tailnet download was not updated in
this checkpoint; use the artifact above for 274.

## Signed integration build 261 — 2026-09-30

[Build 261](https://github.com/DocMorphic/cmux-app/actions/runs/36664427496)
passed from `3b0f6fdb1a9a3bb262f66210cbe156714f7bb1a1`. Download the
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36664427496/artifacts/11075149281)
and extract `app-release.apk` (repository access required). This is the newest
verified signed development APK at that checkpoint; 274 above is now newer.

- Package `io.github.docmorphic.cmuxapp`, version code `261`, version `0.2.0`.
- SHA-256: `72aaf2600c348557dd4f1c6a7b0402eb459a723e1577fc4766f3ee7d0aee9781`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
  It matches 157/244/248/257; signing settings are unchanged.
- Adds the phone-local browser, iOS address resolver, WebView controls, persistent
  cookies/storage, popup/POST handling and page recovery. `New Browser` creates
  a Mac panel when supported and otherwise opens the local fallback. Workspace
  restoration, scoped ownership and cancellation are included.
- CI passed app/Ghostty JVM suites, all four helper tests, viewer hashes, APK
  build/signature and native/ZIP checks. The downloaded APK independently passed
  `apksigner`, all five native libraries' LOAD/RELRO checks and `zipalign -c -P 16`.
- Installed over the previous stable package on Android 17's 16 KiB emulator;
  version `261`, `arm64-v8a`, and `pageSizeCompat=0` confirmed. The sign-in screen
  was visually inspected with no compatibility warning. This establishes package
  launch, not native account or live Mac acceptance. Emulator stopped afterward.
- Browser runtime evidence includes five full-screen RPC navigation tests and
  three additional system-picker/Activity-recreation tests on the same production
  debug code; see [LOCAL_BROWSER.md](LOCAL_BROWSER.md). The test-only follow-up
  did not alter the debug APK hash and does not require another signed build.
- No Pixel install: it remains absent from ADB. Physical Mac/Pixel, full push/Doze,
  accessibility and remaining navigation acceptance are still open. This is not
  the completed parity release.

Local verified download: `build/signed-run-36664427496/app-release.apk` (ignored).
Launch evidence: `captures/runtime/signed261/` (ignored).
Stable and debug packages have separate app data and logins.

## Signed integration build 257 — 2026-09-30

[Build 257](https://github.com/DocMorphic/cmux-app/actions/runs/36659644624)
passed from `9753083aa8b825f886a3c6ad83e186ddc823d0a7`. Download the
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36659644624/artifacts/11073628314)
and extract `app-release.apk` (repository access required). This supersedes 248
at that checkpoint; build 261 above is now newer.

- Package `io.github.docmorphic.cmuxapp`, version code `257`, version `0.2.0`.
- SHA-256: `1cc38dab71c2a2ac1f1189170bcd33e04d65de95dac6930e94f3d7767c684e49`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
  It matches builds 157/244/248; signing settings are unchanged.
- Adds both Simulator modes: native AVC/HEVC streaming with quality/device/
  recovery controls, and legacy PNG/JPEG streaming with ownership and input.
  Includes serialized lifecycle/mode transitions and workspace navigation.
- The full app/Ghostty JVM suites, four helper tests, pinned viewer hashes, APK
  builds, signature and native/ZIP alignment gates passed in CI. The local
  Simulator milestone passed 35 focused JVM tests and eight Android 17/16 KiB
  runtime tests; see [SIMULATOR_STREAMING.md](SIMULATOR_STREAMING.md).
- The downloaded APK independently passes `apksigner`, all five native libraries'
  LOAD/RELRO checks and `zipalign -c -P 16`. Its certificate and version were
  checked on this Mac.
- Installed and launched on the Android 17 16 KiB emulator; version 257 and
  `pageSizeCompat=0` confirmed. Sign-in screen visually inspected without a
  compatibility warning. This verifies launch, not signed native-account or
  live Simulator acceptance. Ignored evidence: `captures/runtime/signed257/`.
- The Pixel remains absent from ADB. No physical install or settings changes.
  Phone-local browser fallback, full push/Doze behavior and remaining physical
  and UI acceptance are still open; this is not the completed parity release.

Local verified download: `build/signed-run-36659644624/app-release.apk` (ignored).
Stable and debug packages have separate app data and logins.

## Signed integration build 248 — 2026-09-30

[Build 248](https://github.com/DocMorphic/cmux-app/actions/runs/36650296593)
passed from `ecdccb0710e661862365fcf7ed979653f406f44e`. Download the
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36650296593/artifacts/11069984729)
and extract `app-release.apk` (repository access required). This supersedes 244
at that checkpoint; build 257 above is now newer.

- Package `io.github.docmorphic.cmuxapp`, version code `248`, version `0.2.0`.
- SHA-256: `40c4f83a375b93aa438bfd8bd8bbfeee88cf17adabefb31376bfe104e7519193`.
- Certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
  It matches the stable certificate; no signing settings changed.
- Includes terminal zoom, native file/Markdown panels and native Todo controls,
  in addition to build 244's Ghostty renderer and configurable toolbar.
- CI's full app/Ghostty JVM suites, helper tests, pinned viewer hashes, APK
  builds, release signature and native/ZIP alignment gates passed.
- The downloaded APK independently passes `apksigner` signature verification,
  native LOAD/RELRO checks and `zipalign -c -P 16` on this Mac.
- Installed and launched on Android 17's 16 KiB emulator. It reports version 248
  and `pageSizeCompat=0`; the sign-in screen was visually inspected without a
  compatibility warning. This proves launch, not signed native-login/terminal
  acceptance. Local evidence: `captures/runtime/signed248/launch.png`.
- No Pixel installation: ADB still shows no physical phone. The debug app and
  stay-awake setting on the Pixel remain unchanged.
- Does not include the later Simulator work. Build 257 above includes both
  Simulator viewers; full parity remains incomplete.

Local download: `build/signed-run-36650296593/app-release.apk` (ignored).
Stable and debug packages have separate app data and logins.

## Signed integration build 244 — 2026-09-30

[Build 244](https://github.com/DocMorphic/cmux-app/actions/runs/36645287501)
completed successfully from `3c806083a37a53b24a3662609f6c5be562029702`.
Download its
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36645287501/artifacts/11068798967)
and extract `app-release.apk`. Repository access is required. This replaces build
157 at that checkpoint; build 248 above is now newer.

- Package `io.github.docmorphic.cmuxapp`, version code `244`, version `0.2.0`.
- APK SHA-256:
  `0710c86a9f003310051ce84c6aea8f33f4e8f50106fe1cfd54203ad92e429d18`.
- Signing certificate SHA-256:
  `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`,
  matching the existing stable release certificate. No signing changes.
- The full app/Ghostty JVM suites, helper tests, viewer hashes, APK builds,
  release signature, native LOAD/RELRO and ZIP alignment gates passed in CI.
- Downloaded APK independently verified with `apksigner`, `aapt`, the native
  alignment verifier, and `zipalign -c -P 16` on the Mac.
- Installed and launched on the Android 17 16 KiB emulator; package reports
  `pageSizeCompat=0`. The sign-in screen renders without a page-size warning.
  This is launch evidence, not signed-build account/terminal acceptance.
- Includes native Ghostty/GVI2 graphics and the configurable terminal toolbar.
  The later per-view zoom and Mac panel work are **not** in this artifact.
- No Pixel install: the physical phone remains absent from ADB and the Mac USB
  inventory. The installed Pixel debug checkpoint above remains unchanged.

Local verified download: `build/signed-build-244/app-release.apk` (ignored).
The stable and debug apps have separate data; installing this package does not
upgrade `cmux (debug)` or transfer its login.

## Native account workflow

1. Install the current debug APK as an in-place update to **cmux (debug)**.
   Its package is `io.github.docmorphic.cmuxapp.debug`. Keep the existing account
   state; do not uninstall it or run account-clearing fixture tests on the Pixel.
2. On the Mac, sign in to cmux, then open **Settings → Mobile** and enable
   **iOS pairing**. This listener also serves the Android native companion.
3. Open **cmux (debug)** on the Pixel and sign in with the same cmux account/team
   if prompted. Select the Mac in Computers. The normal account route uses Iroh;
   a Tailscale QR is not required for this workflow. This Mac version's active
   panel may show no QR despite retaining a Show Tailscale QR label.
4. Verify the workspace list and terminal rendering. Use a dedicated test
   workspace for input acceptance, then check resize, scrollback, reconnect,
   browser panels and notifications. Preserve personal terminals and account data.
5. For background alerts, enable **Settings → Background notifications** and
   grant Android notification permission if prompted. An ongoing cmux connection
   notification is expected. Full Doze/boot behavior is still an acceptance item.

Tailscale-only routes are a separate, explicitly authorized per-computer option;
see [TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md) for prerequisites and the
remaining physical VPN checks. A debug APK and a release APK use separate signing
and package identities; do not replace signing keys to work around an install error.

The old Mac helper path remains available from the Android sign-in and Settings
screens for comparison. Its private `cmux-app://pair` URL contains terminal
access credentials and must be kept private. The native QR contains a route
and account identity but does not contain an access token.
