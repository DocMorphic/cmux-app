# Native transport and keyboard runtime checkpoint — 2026-09-28

The sections below record Android 17 emulator, physical Pixel fixture and live
authenticated Mac checkpoints. Native sign-in, terminal input/resize, restart
reconnect and a remote file preview now have physical-device evidence. Broader
acceptance remains open. Published signed build 157 is unchanged.

## Android 17 RELRO warning — fixed and checked on Pixel

The user reported "The app isn't 16 KB compatible. RELRO alignment check failed."
on the Pixel. The phone currently uses 4096-byte kernel pages, but its package
compatibility checker flags the same native layout defect relevant to 16 KiB
devices. Inspecting the actual APK found misaligned RELRO ends in **all three**
native libraries: Iroh, JNA 5.15.0 and AndroidX graphics-path. The earlier ZIP and
LOAD alignment checks passed but did not validate RELRO; they were insufficient
to establish full 16 KiB compatibility.

The correction explicitly sets both maximum and common page size when linking
Iroh, updates JNA to 5.17.0, and rebuilds graphics-path JNI from pinned, unchanged
upstream source. Even the latest official graphics-path 1.1.0 AAR has a misaligned
RELRO end, so a version bump alone did not solve it. Its Java classes/resources
remain from the hash-verified official AAR. Gradle checks both native receipts.
The new APK-wide verifier checks every library's LOAD and RELRO boundaries, and
CI also runs ZIP alignment verification. No compatibility warning or RELRO
protection is disabled.

The original APK fails the new verifier for all three libraries (captured in
`alignment-before.txt`). Both rebuild jobs succeeded: graphics-path `36539507313`
and Iroh `36539047261`. The assembled APK passes LOAD/RELRO checks for all three
libraries and `zipalign -c -P 16 4`. After updating the Pixel, package manager
`pageSizeCompat` changed from **256 to 0** without overriding compatibility settings.

The same replacement app passed **4 physical-device tests in 1.001 seconds**:
native graphics JNI registration/conic conversion, two terminal input/output
lane tests and native artifact transfer. The graphics check invokes the official
Java binding's native conversion path even on API 34+, where ordinary path
iteration could bypass that library. No account credentials were cleared.

| Replacement artifact | SHA-256 |
| --- | --- |
| Debug APK | `803a41695a473f2c8b595cbc106cc2f59502153228065345958faf008005ff29` |
| App test APK | `beeda3a7f0740642712c82b95b8b2fa6246561ddeab653741dbde9177d9365db` |
| Iroh library | `303fb2060b36f15d41f8aaf08d51888e498ce0a940e07368d5100993db1452f3` |
| Graphics library | `3db2b7620b0e27aa9d75884fa145e75af87b310865d96a9ed7b46ec980759863` |

Evidence in `captures/runtime/pixel-native-20260929`: `alignment-after.txt`,
`relro-build.log`, `relro-native-runtime.txt`, and `relro-receipt.json`. The Pixel
still uses a **4 KiB kernel**: this verifies its package compatibility check and
runtime, not execution on a 16 KiB kernel. At that checkpoint, a separate 16 KiB runtime target and
authenticated Mac enrollment were outstanding; both have later evidence below. The app was opened again and
the user was asked to request a fresh code to verify the sign-in correction.

Reference: [Android native alignment guidance](https://developer.android.com/guide/practices/page-sizes).

## Actual 16 KiB kernel acceptance — 2026-09-29

Installed Google’s Android 17 16 KB system image revision 7 (`android-37.0`,
`google_apis_ps16k`, arm64-v8a) and created a separate disposable AVD,
`cmux_api37_16k`. `adb shell getconf PAGE_SIZE` returned **16384**. Fingerprint:
`google/sdk_gphone16k_arm64/emu64a16k:17/CE2A.260420.050/16231978:userdebug/dev-keys`.

The debug APK from source `8525b12` and its test APK installed successfully. All
**4 native tests passed in 1.675 seconds** on this kernel:

- Graphics-path JNI registration and native conic conversion through the official Java binding.
- Independent native terminal input while control RPC remains usable.
- Native duplex terminal replay, chunks and input alongside control RPC.
- Capability-authorized native file transfer with bounded size/EOF checks.

The app then cold-launched to its sign-in screen without a compatibility dialog.
Package manager reported `pageSizeCompat=0`. All three packaged native libraries
passed LOAD/RELRO verification, and the APK passed `zipalign -c -P 16 4`.
This closes the previously missing 16 KiB native-runtime check for the packaged
arm64 libraries. It does not imply full app feature parity or additional ABI support.

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `580c33e5160ea1bcc111030aa27b24d803f12f5d1e8baeeac3c9183966e04231` |
| App test APK | `aff358d04257e2690e8b7cfda7c393cc50a3be61d6e6c59f629c2750352e572a` |

Evidence is retained in ignored `captures/runtime/android17-16k`: `receipt.json`,
`native-runtime.txt`, `alignment.txt`, and visually inspected `launch.png`.
The Pixel still has the earlier live-tested viewport APK; this emulator check
neither updates the phone nor publishes a signed release. Build 157 remains the
last published signed APK.

## First live Mac terminal and viewport correction — 2026-09-29

After the user signed in, the physical Pixel showed the Mac's existing workspaces.
Using the Android app's New workspace action created a separate third workspace.
The composer sent `echo cmux_android_live_20260929`; its command, output marker and
next Mac shell prompt were visible on the Pixel. Existing user terminals were not
used for test input. This is the first live account/workspace/terminal round trip,
not just a local protocol fixture. Screenshots are retained locally under
`captures/runtime/pixel-native-20260929` (`workspaces-current.png` and
`live-input-first.png`); they are not committed.

The same run exposed a persistent "Terminal viewport is still resizing" banner.
Upstream `TerminalController.applyMobileViewportReport` rejects a generationless
replay after a numbered dedicated viewport report. Android was doing exactly that.
Replay now carries the same client, surface, viewport generation and original
phone dimensions as its dedicated report. It must not replace the phone's natural
dimensions with a smaller effective host cap. Genuine `viewport_transition`
responses now wait for a full grid or a three-second watchdog, with two retries,
matching the pinned iOS readiness/retry policy. Other errors and cancellation are
not retried; this recovery helper is never used for terminal input.

Six focused generation/recovery tests and ten existing terminal-mirror tests
passed. The updated debug APK built, passed all native alignment checks and was
installed without clearing account state. On source `3ca2d22`, reopening the
terminal showed the prior output without the resize error. Switching to direct
keyboard input resized the terminal successfully. Actual Gboard key taps entered
`pwd`, then the Gboard Enter key executed it; the correct Mac repository path and
next prompt appeared. Hiding the keyboard also produced no resize error.

A full force-stop/relaunch retained sign-in and reconnected to the Mac workspace
list. Manually reopening the test workspace restored its terminal history,
including both commands and their results. This checks reconnect and server
terminal continuity, not automatic restoration of the selected terminal screen
or recovery from a network outage.

The detected-folder Files chip opened the real Mac repository folder. Browsing
that folder and opening `.gitignore` displayed all 116 bytes matching the local
file, with syntax coloring and line numbers. This verifies one live folder/text
preview path, not every artifact format or file action. Evidence: `viewport-terminal.png`,
`viewport-keyboard.png`, `direct-ime-pwd.png`, `viewport-keyboard-hidden.png`,
`restart-list.png`, `restart-terminal.png`, `live-files.png`, `live-folder.png`,
and `live-file-preview.png` in the same ignored capture directory. Full rendering,
background recovery and overall iOS parity remain open.

The test terminal then ran `cmux notify --title cmuxAndroidLive --body
PixelNativeAcceptance` successfully. The Android Notifications tab received the
correct workspace, title and body with one unread item. Tapping that notification
opened the same test terminal with its matching command history, and returning
to the feed confirmed the unread badge cleared. This establishes
live foreground feed delivery and destination routing; Android system alerts,
background delivery and server-push fallback are separate remaining gates. Evidence:
`notification-sent.png`, `live-notifications.png`, `notification-destination.png`,
`notification-read.png`.

The installed debug APK for these live checks has SHA-256
`d78ca499224058ec8bb0fabb8b76e9869ccaebaf5379429cea9cbce57fc5c235`.
`live-acceptance-receipt.json` records source `3ca2d22` and scope. The app test APK
was not rebuilt for this viewport change; the earlier four native-lane/graphics
instrumentation checks belong to the preceding RELRO checkpoint.


## Live Android background notification — 2026-09-29

On the same installed `3ca2d22` debug APK, enabled Background notifications through
Settings and accepted Android's notification permission dialog. Android reported
`NativeNotificationService` as an active foreground service with the persistent
connection notification. No battery optimization exemption was requested.

The dedicated test terminal ran a delayed `cmux notify` command. The phone returned
to its launcher before the 12-second delay elapsed. Android's notification service
then showed an alert on `cmux_alerts` with the exact `cmuxAndroidBackground` title
and `PixelBackgroundAcceptance` body while the launcher remained the resumed
activity. The first alert was no longer present when the notification shade was
inspected later; its dismissal cause was not established.

A second six-second delayed notification, `cmuxBackgroundTap` / `PixelTapAcceptance`,
arrived while the app was backgrounded. Its visible system notification was tapped
and reopened the correct test terminal, with the matching command and successful
Mac notification response in the terminal history. The listener remained running.
Background notifications were left enabled for the user.

Ignored evidence: `background-settings-before.png`, `background-settings-enabled.png`,
`background-start.json`, `background-alert-record.json`, `background-system-tap.png`.
Only this app's notification records were retained; other apps' notification data
was not saved. These checks prove delivery while the Activity is backgrounded with
a live foreground service. They do not establish long-term battery/Doze behavior,
boot recovery, network-outage recovery or delivery while the process is stopped.
The official source at the pinned reference uses APNs for server push; Android
server-push delivery needs separate infrastructure support.

## Physical Pixel native lanes — 2026-09-29

Installed the debug app and app test APK from source `61a6bf7` on the Pixel 6a
running Android 17 (`google/bluejay/bluejay:17/CP3A.260905.009/16091614:user/release-keys`).
The existing installation was updated without clearing account data.

One instrumentation run passed **3 tests in 1.056 seconds**:

- Native duplex terminal replay/chunks/input alongside the control stream.
- Independent native terminal input while control RPC remains usable.
- Capability-based raw file transfer with exact size/EOF validation and usable control.

These tests use real native Iroh/QUIC endpoints on the phone's loopback interface.
They do not exercise the Mac, external discovery, account enrollment or visual
terminal behavior, and do not read or clear saved account credentials.

APK SHA-256 values:

- App: `ff831f70d863b8c3fdda70873ba053a0df1948e4aae36996acab9220e26372f1`
- App tests: `69c5a41d8a9f9da1d2b4de59adf3cfde5cdc512da070906165dea16aa5376b05`

Local evidence: `captures/runtime/pixel-native-20260929/native-lanes.txt` and
`receipt.json` (ignored). The phone was unlocked again and reached native sign-in.
Mac UI capture failed with ScreenCaptureKit error -3811; the cmux CLI also rejects
this external process under its existing socket access policy. Neither result
establishes the native listener's current readiness, so the user was asked to
check Mobile settings. No access policy was changed.

### Live email sign-in failure found

The user received an email code, but verification failed. Comparing our request
with upstream `AuthCoordinator.verifyCode` and `CMUXAuthMagicLinkCode` at
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291` revealed that Android discarded the
send-code response's nonce and sent only the visible code. The required payload
is the lowercased six-character code followed by the unchanged opaque nonce.

`NativeEmailSignIn` now retains the challenge in memory, composes that exact
payload, permits invalid-code retries, rejects missing/incomplete responses,
and fences late responses after resend, cancellation or sign-out. Successful
verification consumes the challenge. Account tokens still use the existing
encrypted store. A fresh email request is required after installing this fix.

The sign-in form now scrolls above the IME, displays errors inside the form,
restricts entry to the six-character email code and shows request progress.
Stack errors returned via an HTTP 200 envelope now read the actual response body.
Seven focused JVM regression tests passed, the debug APK built successfully and
was installed on the Pixel without clearing data. Its SHA-256 is
`179ae3166da18f84cbb6476134dab169e359eeeeb74afdf8f654c074eb059ea1`.
Evidence is in `signin-build.log`, `signin-jvm.xml` and `signin-receipt.json` in
the same ignored directory. Authenticated live acceptance of the replacement APK
is pending.

## Failure found and fixed

The first combined UI run failed three terminal cases. Additional connection
failure diagnostics captured `LeftCompositionCancellationException` and no
fixture-server failures. A viewport/editor coroutine cancelled during an RPC
write was retiring the shared connection, so keyboard resizing or navigation
could leave typing and composer sends disconnected.

A deterministic JVM regression failed before the fix. `MobileRpcClient` now
checks cancellation before starting a frame, then completes that frame in an
independent IO cancellation context within its original deadline. Waiting writers
remain cancellable. A deadline watchdog closes the transport to interrupt legacy
Java socket writes, which coroutine cancellation cannot interrupt by itself.
Actual write failures still retire the connection; no uncertain input is replayed.

Three regressions cover cancellation during a started frame, cancellation while
waiting for the writer, and a truly blocking Java write released by its deadline.
The UI fixture now includes connection failure stacks, peer failures, method names
and screenshots on failure; it does not print request tokens or payloads.

## Verified runs

| Run | Result | Scope |
| --- | --- | --- |
| Full JVM suite | 441 passed, 70 suites | Includes the three cancellation/deadline regressions |
| Native module on emulator | 15 passed, 16.167 s | Identity/signing, key storage, QUIC, admission, event streams, malformed optional stream isolation, control replacement, keepalive and revocation |
| Rebuilt app on emulator | 16 passed, 156.236 s | Native terminal input/output, native artifact transfer, clipboard classification, stale IME rejection, Compose selection preservation, direct image ordering, resume/terminal switching, composer image send, task image upload, encrypted storage, picker, preview/removal and keyboard layout |

These are separate 15-test and 16-test instrumentation runs. The failed initial UI
run and diagnostic reruns remain retained; the final app run passed all 16 in one
process. No tests were disabled or assertions weakened to obtain the pass.

Inspected screenshots at this initial checkpoint confirm the task prompt/dock fits
above the keyboard and terminal keyboard controls remain visible. Its terminal capture occurs during
viewport resizing and shows a blank output frame; it does **not** establish terminal
rendering fidelity or absence of resize flicker. The resize follow-up below addresses
that blank frame; full rendering fidelity remains open.

## Prepared APKs at the initial checkpoint

| Artifact | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `a1c5ce51ff787333d6b634e07f23d9d66d15a4a30dd27f6471484b8d6aee386a` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `88bd07d025a7a77eaba8ca433fae4ebe156fa6127b04005eb39b45bf8d330b94` |
| `iroh/build/outputs/apk/androidTest/debug/iroh-debug-androidTest.apk` | `17a1ff7b5d8c7effcd8938d679f65fff306439601b720f541c7456ee52cfb02c` |

The main APK passes signature verification and 16 KiB page alignment. All 14
packaged viewer assets match their manifests, and the arm64 Iroh library matches
the pinned native build receipt. This local debug APK includes control push
recovery and both direct/composer keyboard-image changes. It has not been installed
on the physical Pixel or published as a new signed release.

Evidence on this Mac: `captures/runtime/6568068-emulator/fixed-receipt.json`,
`fixed-app-runtime.txt`, `iroh-instrumentation.txt`, `fixed-jvm/`, build logs and
screenshots. The directory is intentionally excluded from Git. The receipt records
the source base, exact RPC source hash, APK hashes and emulator fingerprint.

## Next acceptance work

1. The current app and three native lane checks have now passed on the Pixel as
   recorded above. Physical keyboard acceptance remains open. UI fixtures
   in `NativeFlowTest` and `NativeTaskAttachmentsTest` clear debug credentials;
   do not run those over an authenticated session without arranging restoration.
2. Verify actual account enrollment/discovery against the already enabled cmux
   mobile listener; then open workspaces, type, paste images/files, resize, navigate
   and reconnect with the actual Mac.
3. Check real Gboard temporary URI grants and physical terminal rendering. Continue
   the remaining parity requirements in `PARITY.md`; this milestone does not close
   the full-app goal.

## Resize follow-up — 2026-09-29

Opening the keyboard created a fresh output mirror and immediately published its
empty display before the host answered the resize replay. The Android screen now
retains the last painted frame during a same-terminal, same-connection resize.
A different terminal or client resets the display. Every new viewport still owns
a fresh parser and protocol cursor; only the previous visible frame is retained.
An uninitialized render grid cannot replace it, while a valid empty host frame can.

The new regression holds the actual fixture RPC replay response after opening the
keyboard. On the previous APK it fails because the terminal text disappears. The
fixed checks cover grid and byte output, count painted foreground pixels while
waiting and after replay, and require zero old foreground pixels when switching
to another terminal whose replay is held.

All 20 focused JVM checks passed (10 mirror and 10 render-grid tests). The main
and test APKs built. Both final retention cases passed in one emulator run in
32.881 seconds. The existing keyboard-resize/input and raw-byte recovery cases
also passed in the preceding run. These are four distinct passing cases across
separate runs, not a clean combined four-case run.

The initial emulator startup hit Android service/Gboard ANRs before testing. A
later System UI startup dialog obstructed the first combined run and its keyboard
case timed out. The pre-fix text-retention assertion failed with that dialog still
present, so that run is supporting evidence only. After dismissing the dialog, the
keyboard case passed; the new byte case exposed a fixture race where its global
replay latch could hold an outgoing terminal's response. The fixture now holds
only the selected surface's replay. No production assertions were weakened.
All failed runs and diagnostic logs are retained.

The four final retention screenshots were visually inspected without the system
dialog. They show Gboard, the retained frame scaled to fit while the response is
held, and the replacement frame at its new dimensions after the response arrives.
The separate keyboard/input screenshot also shows terminal output above Gboard.

| Updated artifact | SHA-256 |
| --- | --- |
| Main debug APK | `3527c33e64fa852c78d79af0e58d965839761af2594e532c62b5e16149243970` |
| App instrumentation APK | `5747e92d8a6e0c255984a9e9f972f0377f8fce9744e6df7bf2e858984e569186` |

Evidence is in `captures/runtime/resize-retention/`, excluded from Git: the
before-fix failure, fixed run, APK/source hashes, JVM XML and screenshots. This
follow-up does not establish physical keyboard behavior or full Ghostty fidelity.

## Modifier follow-up — 2026-09-29

The app now has the iOS one-shot/sticky Ctrl/Alt/Cmd/Shift behavior and readline
shortcuts described in `PARITY.md`. Seven focused JVM checks passed. The new
modifier workflow and the existing direct keyboard composition/rejection/switch
regression passed together on the Android 17 emulator: **2 tests, 36.1 seconds**.
The locked-control screenshot shows the blue active fill, lock outline, Cmd
button and Gboard; it was visually inspected without an ANR overlay.

Earlier build attempts were interrupted and emulator preflight found a System UI
startup dialog. No runtime pass is attributed to those attempts. The final main/
test build succeeded; the final emulator was awake on its normal launcher before
the two-case run. This remains fixture evidence, not authenticated Mac/Pixel QA.

| Artifact at this checkpoint | SHA-256 |
| --- | --- |
| Main debug APK | `ff831f70d863b8c3fdda70873ba053a0df1948e4aae36996acab9220e26372f1` |
| App instrumentation APK | `69c5a41d8a9f9da1d2b4de59adf3cfde5cdc512da070906165dea16aa5376b05` |

Local evidence: `captures/runtime/modifiers/receipt.json`, `runtime.txt`,
`final-assembly.log`, `jvm/`, and `terminal-sticky-control.png`. Published signed
release 157 is unchanged. Physical acceptance and full-app parity remain open.
