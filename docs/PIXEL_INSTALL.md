# Pixel 6a install and connection check

This guide is for the experimental `feature/local-mac-bridge` APK. The Mac helper passed a loopback integration check with cmux `0.64.25`; the Pixel 6a connection has not yet been verified.

1. Connect Tailscale on the Pixel 6a. Keep the Mac connected to the same tailnet.
2. Receive `app-debug.apk` from the Mac through Taildrop. On Android, the transfer should appear as a notification and in the Files app's Downloads section. If Taildrop is unavailable, enable **Send Files** in the tailnet's Tailscale settings or transfer the APK another way.
3. Open the APK in Files and follow Android's installation prompt. Android may ask you to allow **Install unknown apps** for Files. Grant this only for the installation source you choose. The APK is a debug build; it is not distributed through Google Play.
4. On the Mac, open a terminal **inside cmux** and run:

   ```bash
   cd /Users/dharmaydave/me/cmux-app
   node bridge/server.mjs --bind-tailscale
   ```

   The helper prints a private `cmux-app://pair` URL. Keep it private; it contains the bearer token that authorizes terminal access. The helper listens only on the Mac's Tailscale IPv4 address, port `58466`.
5. Open **cmux companion** on the Pixel, tap **Connect through Mac helper**, paste that URL, and tap **Connect**. Select a workspace and terminal. Try a harmless command to confirm input and output.
6. Press **Ctrl+C** in the Mac's helper terminal to stop remote access after the check.

The helper route does not require enabling cmux's separate iOS pairing toggle. Terminal rendering is currently plain text and does not yet support full-screen terminal apps or notifications.

Sources: [Tailscale Taildrop](https://tailscale.com/docs/features/taildrop?tab=android), [Android install unknown apps](https://developer.android.com/distribute/marketing-tools/alternative-distribution), [Android 17 Pixel support](https://developer.android.com/about/versions/17/get).
