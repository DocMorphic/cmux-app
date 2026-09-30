# Pixel 6a install and native pairing check

This is the direct cmux Android build on `feature/local-mac-bridge`. It is still
a development build; see [PARITY.md](PARITY.md) for the unverified features.

## Current installed checkpoint — 2026-09-29

Debug app `5d24946` is installed on the Pixel in place, preserving app data.
The installed `base.apk` SHA-256 matches the tested build:
`c1e35644fbe4dc4f79de97dd9989b5507c0f07c48688fef1ea296d28a5f4bb05`.
Install/hash evidence is in ignored `captures/runtime/pixel-5d24946/`.
The phone was in use afterward, so new UI acceptance is pending. Its plugged-in
stay-awake setting remains `0`; it was not changed for this installation.

The Android Iroh/V2 connection is implemented and earlier checkpoints have real
Pixel/Mac evidence; see [IROH_V2.md](IROH_V2.md) and [NETWORKING.md](NETWORKING.md).
This replaces the old statement that Iroh integration was unfinished.

## Signed integration build 244 — 2026-09-30

[Build 244](https://github.com/DocMorphic/cmux-app/actions/runs/36645287501)
completed successfully from `3c806083a37a53b24a3662609f6c5be562029702`.
Download its
[cmux-app-stable-signed-apk artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36645287501/artifacts/11068798967)
and extract `app-release.apk`. Repository access is required. This replaces build
157 as the most recent verified signed artifact.

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
