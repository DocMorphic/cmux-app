# Daily-use Android delivery

## Current goal — 2026-10-28

The owner replaced the full iOS parity goal with a practical Android release,
targeting roughly two hours of focused delivery work. The release must support:

- Existing sign-in and saved Mac pairing, without resetting app data.
- Connecting to the Mac and opening its workspaces and terminal panes.
- Reliable terminal input, visible output and scrollback.
- Switching workspaces/panes and recovering after backgrounding or disconnection.
- A signed, phone-accessible APK download and simple setup instructions.

Use the existing Mac companion and account services. No new server, Firebase
project, Tailscale deployment, Google Play account or billing backend is required
for this delivery. Optional notification-helper enrollment is separate from
terminal access. Full iOS visual parity, exhaustive reference captures, billing,
Cloud and experimental background notification/reply acceptance are outside this
milestone. The historical parity tracker is not this release's completion gate.

## Download

[Download signed build 750](https://github.com/DocMorphic/cmux-app/releases/download/android-preview-750/app-release.apk) (278,853,630 bytes).
[Release notes and verification manifest](https://github.com/DocMorphic/cmux-app/releases/tag/android-preview-750).
Built from `40ae5ff7cb7d5608d346b163da0babebf264cb19` with the existing release key.
The daily-use delivery milestone is complete; full iOS parity is outside its scope.

## Upgrade on your Pixel

1. Download `app-release.apk` from the release linked in the repository README.
2. Open the download. If Android asks, allow your browser or Files app to install
   this APK, then choose **Update**. Do not uninstall cmux or clear its data.
3. Open cmux. Your existing account and saved Mac should remain. Select your Mac
   under **Computers**, open a workspace, then choose its terminal pane.

The release uses the same application ID and signing certificate as your installed
build 723. If Android offers a fresh installation instead of an update, or reports
a signing conflict, stop and check that you downloaded the release APK rather
than a debug APK. Do not remove the existing app to work around it.

## One-time Mac setup, if needed

1. Run cmux on the Mac. On this Mac, keep using the already configured
   **cmux DEV android-push** companion; its account and pairing are restored.
2. In cmux Settings, enable **Enable iOS pairing**. This setting also enables
   this Android companion.
3. Sign in to the same cmux account and select the same team on both devices.
4. On Android, open **Computers**, refresh if necessary, and select the Mac.
   Native Iroh discovery/transport handles the connection. No separate server
   installation is needed. A working internet connection is still required.

You can leave **Pair Android Notifications** closed. Your existing notification
pairing is reused; generating another pairing code is unnecessary for an upgrade
or for ordinary terminal use.

## Everyday use

Keep cmux running and the Mac awake while using its terminals remotely. Open a
workspace and use its pane picker to switch terminals. Use **Keyboard** for direct
terminal input; the terminal toolbar provides special keys. Return to the
workspace list to switch workspaces.

After an ordinary app switch or network interruption, reopen cmux and let it
reconnect. If it stays disconnected, wake the Mac, check both internet connections,
then select the saved Mac again under Computers. Confirm that both devices still
use the same account/team before changing pairing. Do not reset pairing as the
first recovery step. A sleeping or powered-off Mac cannot serve live terminals.

## Delivery evidence

Build 750 passed 3,552 unit tests with four existing skips. All 26 selected core
Android cases have passing results across the recorded runs after correcting
stale synthetic sign-in/browser fixtures and making the offline fixture close its
wire. Production sources did not change during those test corrections. The exact
signed APK passed six ART class checks and two cold starts on the owned ARM64
emulator, with an empty crash buffer. Signing, native 16 KiB alignment and viewer
asset checks passed. Emulator settings were restored and the emulator stopped.
The release notes and manifest identify the source, APK SHA-256 and version.
Emulator tests exercise native terminal lanes, saved-Mac selection, workspace
routing and activity/background recovery. They do not replace
an actual phone check. The previously installed build 723 passed real Mac/Pixel
native connection and terminal input/output; the new APK must be identified
separately rather than claiming that older check tested its exact bytes.
