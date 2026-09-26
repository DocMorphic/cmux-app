# Pixel 6a install and native pairing check

This is the direct cmux Android build on `feature/local-mac-bridge`. It is still
a development build; see [PARITY.md](PARITY.md) for the unverified features.

1. Connect the Pixel 6a and Mac to the same Tailscale tailnet.
2. Install the latest `cmux-app-stable-signed-apk` release APK from GitHub Actions
   or the Mac's temporary Tailscale download link supplied for a device test.
   The older debug APK uses a different signing key, so Android may require
   uninstalling **cmux companion** once. Later stable-signed APKs upgrade in
   place. This removes the old app's local pairing state; keep the Mac helper
   pairing URL available if you still use it.
3. In cmux on the Mac, open **Settings → Mobile**, enable **iOS pairing**, then
   choose **Show Tailscale QR**. Despite the label, this is cmux's native mobile
   endpoint and the Android app reads the same QR contract.
4. Open **cmux companion** on the Pixel. Sign in with the same cmux account as
   the Mac, scan the Mac's QR, and allow the camera prompt if shown. The app
   verifies the account ID and connects over the Pixel's Tailscale VPN.
5. Open a workspace and try a harmless terminal command. Check browser panels,
   scrollback, and the notification feed. To test background alerts, enable
   **Settings → Background notifications** in the Android app and allow Android
   notifications. The app then shows an ongoing cmux connection notification.

The old Mac helper path remains available from the Android sign-in and Settings
screens for comparison. Its private `cmux-app://pair` URL contains terminal
access credentials and must be kept private. The native QR contains a route
and account identity but does not contain an access token.
