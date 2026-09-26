# Pixel 6a install and connection check

This guide is for the experimental `feature/local-mac-bridge` APK. The Mac helper passed a loopback integration check with cmux `0.64.25`, and the Pixel 6a has connected through Tailscale.

1. Connect Tailscale on the Pixel 6a. Keep the Mac connected to the same tailnet.
2. Download the latest `app-debug.apk` from the GitHub Actions artifact or transfer it from the Mac over Tailscale. If Android reports a signing conflict with an earlier debug APK, uninstall the earlier **cmux companion** first. Debug builds from separate CI jobs may have different signing keys.
3. Open the APK in Files and follow Android's installation prompt. Android may ask you to allow **Install unknown apps** for Files. Grant this only for the installation source you choose. The APK is a debug build; it is not distributed through Google Play.
4. On the Mac, open a terminal **inside cmux** and run:

   ```bash
   cd /Users/dharmaydave/me/cmux-app
   node bridge/server.mjs --bind-tailscale
   ```

   The helper prints a private `cmux-app://pair` URL. Keep it private; it contains the bearer token that authorizes terminal access. The helper listens only on the Mac's Tailscale IPv4 address, port `58466`.
5. Open **cmux companion** on the Pixel, paste the helper's private pairing URL, and tap **Connect**. Open a workspace and try a harmless command to confirm input and output.
6. Press **Ctrl+C** in the Mac's helper terminal to stop remote access after the check.

The helper route does not require enabling cmux's separate iOS pairing toggle. Terminal rendering is currently plain text and does not yet support full-screen terminal apps or notifications.

Sources: [Tailscale Taildrop](https://tailscale.com/docs/features/taildrop?tab=android), [Android install unknown apps](https://developer.android.com/distribute/marketing-tools/alternative-distribution), [Android 17 Pixel support](https://developer.android.com/about/versions/17/get).
