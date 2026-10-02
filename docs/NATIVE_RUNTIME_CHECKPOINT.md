# Native transport and keyboard runtime checkpoints

## Physical MainActivity composer and reopen — 2026-10-02

The unlocked Pixel passed `LiveNativeUiCheck`: **1 test, 16.529 s**, zero skips.
Using the existing account and saved Mac, it created a uniquely titled workspace,
opened it through MainActivity and exercised the production lazy-startup fix.
Gboard was visible; terminal height fell from 1743 to 907 pixels. Framework text
injection into the real composer followed by Send produced the exact assembled
marker in terminal semantics. Leaving and reopening the workspace retained that
marker exactly once. Login was preserved and fixture removal was verified.

Both private screenshots were visually inspected. They show actual shell output
and the Gboard/composer layout. The keyboard image has smaller terminal text than
the reopened image; the test did not wait for host-grid convergence. Therefore
this is not proof of settled resize or direct physical Gboard key taps.

A stronger host-viewport probe subsequently timed out before keyboard entry and
reported unverified fixture cleanup (41.46 s total). No command was sent. Native
connection preflight remained healthy (1 test, 4.982 s). A separate authenticated
inspection identified the generated fixture, and one exact-title close verified
its removal (1 test, 6.557 s). Existing workspaces were not selected for closure.

The test now keeps Compose effects moving while polling dimensions off-thread,
logs only numeric geometry, and retains an app-private creation receipt for
interrupted-run recovery. The revised APK built, but the Pixel disconnected
before installation, so the stronger resize check remains pending.

The successful UI run used debug APK
`e5e263d23bb42bc877c66c1a4fa66675d9cf136a9fa114b308055e288b6e42a8`
and test APK
`8bb6a49ae31e267ebadfb04395aa381054e3e111f9923d754310d48e50e16d9a`.
Production code, signed build 376 and persistent phone sleep settings are
unchanged. No emulator was started. Original successes, failures, recovery and
screenshots remain ignored in `captures/runtime/pixel-ui-20261002/`.

## Physical live GRID output and native input lane — 2026-10-02

Extended the opt-in physical terminal check with `cmux_live_terminal_stream=true`.
The final Pixel/Mac run passed **1 test in 11.542 s**, without skips. It retained
the existing login and saved Mac, verified identity/account access, and used one
new disposable workspace. The baseline RPC command/replay and distinct-connection
reconnect checks still passed.

For the additional live check, the host selected **GRID** output. The test used
production `subscribe`, `TerminalStreamMirror`, and `TerminalInputLaneOwner`, with
a temporary 80×24 viewport on that disposable terminal. It established one replay
baseline, sent a second generated command through the independent native input
lane and received its exact output through **five applied live grid events**.
No replay after that send could satisfy this assertion. The assembled output marker
was absent from the echoed command, and control RPC remained usable while the
input lane was open. A later separate reconnect verified preserved baseline output
on a genuinely different underlying connection.

The host did **not** advertise `terminal.input.exactly_once.v1`:
`identifiedInputAdvertised=false`, `inputAcknowledgementVerified=false`.
The test therefore sent the supported legacy native frame. This is proof of live
native input/output, not physical verification of identified input, its ACKs or
deduplication. Those need a compatible host. Fixture coverage for identified input
remains recorded separately in `TERMINAL_INPUT_DELIVERY.md`.

Temporary stream/viewport resources were released, the test workspace was closed
and its absence verified, and the app was reopened. No existing terminal received
input. The phone's stay-awake setting remained `0`; no emulator was started.
Production code/APK is unchanged from the lazy-startup fix: installed debug SHA-256
`e5e263d23bb42bc877c66c1a4fa66675d9cf136a9fa114b308055e288b6e42a8`.
The test APK built and passed ZIP alignment, SHA-256
`f31011c4462e9713d2acbbcb09745328166d11551108d781fcfe26044a36edff`.
Signed build 376 remains unchanged. This check does not establish raw-byte output
lanes, Compose pixels, Gboard, network switching, full sender recovery or live push.
Evidence is ignored under `captures/runtime/pixel-stream-20261002/`.

## Lazy terminal startup and live reconnect — 2026-10-02

A new opt-in physical test exposed a real startup deadlock: native
`workspace.create` succeeded, but polling `is_ready` alone never started its
terminal. The first run failed after **27.252 s**, before sending any input, and
verified cleanup of its disposable workspace. This failure is retained rather
than counted as a passing creation/input check.

At audited upstream `204a11d`, `v2MobileWorkspaceCreate` forces
`eager_load_terminal = false`. `mobileResolveWorkspaceAndSurface` materializes a
selected terminal when a terminal request resolves it. Android had withheld all
terminal requests until inventory readiness was true, creating a circular wait.

`MobileRpcClient.prepareTerminal` now requests a scoped replay without a client
viewport or input. `NativeScreen` invokes it once per selected unready terminal
and active lifecycle interval. Its response is discarded; the authoritative
inventory poll, existing startup deadline and disabled-input UI retain ownership
of readiness/recovery. A transient preparation error does not restart creation or
send keystrokes. Switching the pane/connection or leaving the foreground cancels
the effect. No preparation runs merely from enumerating other workspaces.

With that preparation step, the final physical Pixel/Mac check passed **1 test in
6.731 s**, without skips. It used the existing account and saved Iroh Mac, verified
host identity, created a dedicated workspace, sent one `printf` command, decoded
its exact output through the production `TerminalStreamMirror`, closed the native
connection, reconnected to the same terminal and decoded the same output without
resending input. Distinct underlying event streams prove this was a new connection,
not another lease on the original shared wire. An earlier 8.818 s run also passed,
but lacked that explicit connection-identity assertion; these are two runs of one
case, not two different cases. The selected output mode was **GRID**. It then closed the created
workspace and verified its absence. Existing terminals received no input and no
credentials/pairings were cleared. The printed marker was assembled from separate
shell arguments, so command echo alone could not satisfy the output assertion.

All **7 startup UI regressions passed in 86.741 s**, without skips, on the reused
Android 17 / 16 KiB emulator. The corrected case requires exactly one initial
preparation for the exact owning workspace while keyboard input stays disabled,
then requires a normal viewport-bearing replay after inventory readiness. The
other cases retain sibling selection, timeout/late readiness, explicit retry,
disappearance, delayed-create navigation and partial workspace-list behavior.
Earlier assertions that forbade every terminal request before readiness encoded
the faulty assumption; they now permit only the viewport-free preparation and
still reject input/resize calls. The delayed response for a workspace that was
left still requires zero terminal calls. The startup screenshot was visually
checked with the selected pending tab and disabled Keyboard control. The emulator
was stopped after the run; no additional virtual device was created.

The updated main debug APK is installed on the Pixel; its installed hash matches
`e5e263d23bb42bc877c66c1a4fa66675d9cf136a9fa114b308055e288b6e42a8`.
All six native LOAD/RELRO checks and both main/test APK ZIP alignment checks pass.
The phone's stay-awake setting remains `0`; this test did not change it.
Signed build 376 is unchanged. The live test exercises the production RPC and
decoder, not Compose pixels, Gboard, native output-lane streaming or network
switching. The full app goal and browser/push-provider acceptance remain open.

Ignored local evidence: `captures/runtime/pixel-terminal-20261002/`, including
the original readiness failure, corrected physical run, fixed-label report,
build logs and installed APK/hash/alignment receipts. See
[the opt-in procedure](ANDROID_TESTING.md#opt-in-physical-terminal-acceptance).

## Physical upgrade and system-bar contrast — 2026-10-02

Updated the Pixel's existing debug installation without clearing app data. The
first-install timestamp remained unchanged, the existing account refreshed, and
the Workspaces screen displayed the saved Mac's workspaces. The installed debug
APK matches local SHA-256
`831db310ff7bba2a5732fd6bf075ceaafc571c97e439e9ae4a2e496dd4a5d058`.
This is a local debug update; signed build 376 remains unchanged.

The explicitly read-only `LiveNativeBrowserCheck` passed **1 test in 5.181 s**
without skips, using the existing single admitted saved Mac. It verified host
identity, current account access and a workspace read over **LAN or Private VPN**.
The native transport supports browser lanes, but this host did **not** advertise
`browser.tunnel.v1`; no browser listing was requested. An initial attempt with a
hardcoded `stable` instance-tag selector failed to select a saved Mac before
connecting. Removing that optional selector used the existing saved identity;
this was not an authentication fix or new pairing. No terminal input, workspace
mutation or account-store reset was performed.

The physical screen exposed dark status-bar icons against the app's dark
background. MainActivity and RoutedBrowserActivity now explicitly request light
system-bar icons. The debug build succeeded in 29 s; all six native libraries
passed LOAD/RELRO checks, ZIP alignment passed and the phone reports
`pageSizeCompat=0`. After installation, the MainActivity screenshot visibly shows
readable light status icons and navigation indicator. The routed-browser change
compiled but was not physically exercised. The read-only connection check
preceded this system-bar-only change; it was not repeated on the final APK.

The temporary USB stay-awake setting was restored to its original value `0` and
read back. No emulator was started. Private evidence remains ignored under
`captures/runtime/pixel-376/`: test reports, package/hash receipts, build/alignment
logs and before/after screenshots. Screenshots and UI dumps contain real workspace
content and must not be committed. This closes this upgrade/contrast check, not
the remaining browser, live push or full-app acceptance requirements.

## Earlier checkpoints — starting 2026-09-28

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

## Physical network outage recovery — 2026-09-29

On the installed viewport APK (`3ca2d22`, SHA-256
`d78ca499224058ec8bb0fabb8b76e9869ccaebaf5379429cea9cbce57fc5c235`), used only the
separate test workspace to run six numbered output lines ten seconds apart.
Wi-Fi and mobile data were initially enabled; airplane mode was off. Both network
connections were disabled temporarily and then restored in a `finally` block.

The first outage lasted 35 seconds. An unsent `echo cmux_after_network` draft
survived. The terminal displayed a connection error after network restoration,
and the phone subsequently slept. After unlock, terminal output recovered and the
draft was sent once, producing one echoed command and one output marker. A manual
Reconnect tap was attempted while the phone was asleep, so this first run does
not establish a clean automatic-recovery timing result.

For the second run, USB stay-awake was temporarily enabled. The measured outage
lasted 37.98 seconds (including device-command overhead). Without tapping Retry,
the terminal recovered **28.0 seconds after restoration**, displaying all six
`cmux_awake_1` through `cmux_awake_6` lines exactly once and no connection error.
That includes output generated while the phone was offline. Both Wi-Fi and mobile
data were restored to enabled. This verifies one real outage/recovery scenario;
it does not prove exactly-once handling of input submitted during an uncertain
send, which this test did not attempt, or all network and lifecycle conditions.

Ignored evidence in `captures/runtime/pixel-native-20260929`: `reconnect-outage.json`,
`reconnect-offline.png`, `reconnect-unlocked.png`, `reconnect-post-send.png`,
`reconnect-awake.json`, `reconnect-awake-offline.png`, `reconnect-awake-recovered.png`.
The same run exposed a long terminal title hiding the Keyboard control; the UI
correction constrains the title to the space remaining between navigation buttons.

## Long terminal title and app upgrade — 2026-09-29

The live outage check exposed a terminal header layout bug: a long running command
could consume the row and hide Keyboard. The title now receives only the width
remaining between Back and Keyboard/Compose, with single-line ellipsis.

Installed the updated debug APK over the authenticated Pixel app. With a long
`env CMUX_HEADER_ACCEPTANCE=… sleep 90` command running in the dedicated test
workspace, verified Keyboard → Compose → Keyboard, the title menu (View as Text
and Files), and Back to the workspace list. Screenshots were visually inspected;
the long title and both navigation controls remain visible with Gboard open.
The account stayed signed in. Android restarted `NativeNotificationService` on
`MY_PACKAGE_REPLACED`; dumpsys confirms it is a foreground service. This checks
listener restart, not a new notification delivery after this upgrade.

The installed APK bytes match the local main APK. Native alignment checks pass.
USB stay-awake was restored to its original value `0`; Wi-Fi and mobile data are
both enabled and airplane mode remains off. No device data was erased.

| Artifact | SHA-256 |
| --- | --- |
| Main debug APK | `de386e9ce21fe9f277be81ef688f0e76ab38b607d89e0fdd6da1b51f81262adc` |
| Instrumentation APK | `8f76d175fcb873c51587006e5a1e58fc43f1244747f3a10ea9ff95c48773f36e` |

The new automated header regression initially could not start on the 16 KiB
emulator: both launcher and test-app startup ANRs were recorded. Instrumentation
reported “Process crashed,” not a passing test. The passing native-library tests
on that kernel documented earlier are separate evidence.
The 4 KiB emulator retry first asserted before the asynchronous workspace route
finished opening. The fixture now waits for terminal output before checking the
header; assertions are unchanged. The final focused run passed **1 test in
8.324 seconds**, covering visible/clickable navigation, keyboard mode switching,
title menu and return to workspaces. Main APK unchanged; only the test APK was
rebuilt for this synchronization correction. Initial failure logs are retained.

Ignored evidence: `captures/runtime/pixel-native-20260929/header-receipt.json`,
`header-keyboard-long.png`, `header-menu.png`, `header-back-confirmed.png`,
`header-alignment.txt`, `header-build.log`, `header-runtime.txt`,
`header-runtime-retry.txt`, and `header-runtime-final.txt`.
The last published signed APK remains build 157; this update is installed debug
acceptance, not full parity or a new signed release.

## Uncertain terminal delivery acceptance — 2026-09-29

Added a lost-reply fixture that executes/records `terminal.paste`, then closes that
socket before returning its acknowledgement. Its listener remains available for
the real screen's automatic reconnect. The UI check requires the original draft
and delivery-unconfirmed warning after reconnect/replay, a persisted warning,
exactly one original command, and successful delivery of a different explicit
command. This exercises application behavior with a controlled host; it does not
establish the same failure timing against the physical Mac.

A separate native-lane integration check injects a partial native write, repairs
the lane, and verifies the input queue discards the uncertain/queued keys without
RPC fallback. The queue stays paused until explicit resume, after which only new
input reaches the replacement lane. **26 focused JVM tests passed**, zero
failures/errors/skips: 8 native-input-lane, 9 input-queue and 9 control-repair cases.

The first UI attempt ended in a test-app startup ANR before any test executed.
Launcher/System UI stalls and high emulator CPU contention were also recorded;
it is not a test pass. Retried after stopping that emulator and booting the same
AVD with `-gpu host`; preflight showed the normal sign-in screen without an ANR
overlay. The full lost-reply UI case passed **1 test in 7.286 seconds**. Its
reconnected screenshot was visually inspected: terminal output, retained command,
Gboard and the unconfirmed-delivery warning are visible.

Main APK is unchanged from the preceding Pixel header checkpoint (`de386e9…`).
The instrumentation APK SHA-256 is
`e28484bd7d4ba0a3a370e84fb82301f90c312df4c21f67f3941a024690187bfb`.
Ignored evidence: `captures/runtime/pixel-native-20260929/lost-reply-receipt.json`,
`lost-reply-runtime.txt` (startup failure), `lost-reply-runtime-host.txt` (pass),
`lost-reply-jvm/`, and `composer-lost-reply-reconnected.png`. Use the host renderer
as the next emulator preflight choice on this Mac; one successful retry does not
establish that every startup ANR was caused by software graphics.

The physical phone was not used or changed. This closes the controlled UI/native
queue scenarios, while real-Mac uncertain-send timing and broader lifecycle
acceptance remain open. No new signed APK was published.

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
