# WireGuard Android tunnel

Pinned Maven artifact: `com.wireguard.android:tunnel:1.0.20260102`.
Official source: https://git.zx2c4.com/wireguard-android/ (tag `1.0.20260102`).

The app uses the unmodified Java Config/GoBackend APIs and `libwg-go.so`.
Unused root/kernel helper binaries `libwg.so` and `libwg-quick.so` are excluded
by APK packaging. The app currently ships arm64 only.

`inventory.json` records the inspected AAR checksum and the Go modules read from
its arm64 `go version -m` build information. Go 1.24.3, the Android wrapper and
all four linked module license/patent files are reproduced in the app's
`assets/licenses/WireGuard.txt`; Apache-2.0 is also in the license browser.
This inventory describes the published artifact, not a local native rebuild.
The app's existing ELF/RELRO and ZIP checks passed on the integration APK.

Android integration uses a cmux subclass of the upstream VPN service to add an
explicit-start reservation, a foreground notification and shared-account lifetime.
The library's base service is removed from the merged manifest. No always-on or
boot-start support is enabled. See `docs/CLOUD_COMPANION.md` for runtime evidence
and remaining work.
