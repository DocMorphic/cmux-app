# Android parity tracker

Reference: [cmux iOS](https://cmux.com/ios) and
[`manaflow-ai/cmux`](https://github.com/manaflow-ai/cmux) at
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291` (2026-09-27), refreshed on 2026-09-28.
The upstream code changes frequently, so update this pin before a release.
The iOS implementation lives mainly in `ios/cmuxPackage`,
`Packages/iOS`, and `Packages/Shared/CMUXMobileCore`.

The target is the real iOS companion workflow on a Pixel 6a (Android 17).
A feature is complete only after the Android behavior is implemented, covered
by a focused automated check where practical, and exercised against the Mac.
UI resemblance alone does not count.

**Local computer appearance checkpoint (2026-09-29):** Computer Details now edits
name, palette/custom RGB color, computer/utility symbols and emoji, with independent
Auto resets. Scoped atomic storage updates computer rows, the selector, workspace
avatars/search, task options and notification labels without changing connection
identity or dialing. Twenty-eight JVM cases and ten Android UI cases pass across
two runs; an initial JUnit test-declaration failure was fixed and the four new
cases rerun. Account backup/restore and physical acceptance remain open. See
[COMPUTER_APPEARANCE.md](COMPUTER_APPEARANCE.md) for evidence, Android icon/picker
differences and the exact upstream backup contract. Signed build 157 is unchanged.

**Mac Power checkpoint (2026-09-29):** Computer Details now has the iOS-style
Keep Mac Awake control, gated by exact live Mac identity and `caffeine.control.v1`.
It handles confirmed state/events, duplicate prevention and ambiguous mutations
with read-only reconciliation. Thirty-eight focused JVM checks and six emulator
UI cases pass; an initial emulator hang is documented. The verified debug APK is
installed on the Pixel, but the phone remained asleep, so live Mac power
acceptance is pending. No Mac power or phone sleep setting was changed. Row
indicators, appearance and broader detail parity remain open. See
[MAC_POWER.md](MAC_POWER.md). Signed build 157 is unchanged.

**Per-computer detail checkpoint (2026-09-29):** native computer rows now have
Details without changing the active workspace. Checks refresh discovery, resolve
the exact Mac/build, borrow and release their own connection, and can run without
opening that Mac first. Private-address editing is per computer; the all-computer
reset moved to Networking. Thirty JVM checks and seven emulator UI cases pass
(the final legacy-QR compatibility adjustment has JVM/build evidence). Appearance,
direct-only/route controls, account revoke and physical navigation
acceptance remain open; see [COMPUTER_DETAILS.md](COMPUTER_DETAILS.md).

**Live Networking / Pixel checkpoint (2026-09-29):** Settings now has a
Networking page with actual endpoint/home-relay state, broker relay metadata,
directory permission/revision and authenticated refresh. Nineteen JVM checks,
three UI cases and one native FFI case pass. The latest debug APK is installed on
the Pixel: sign-in/workspaces survived, relay refresh and home-relay attribution
were verified against the real Mac, and Connection Check returned verified Iroh,
Mac identity/account access, LAN/private-VPN route, 14 ms RTT and 75 ms RPC time.
Android's safe-report chooser opened without sending anything. Normal phone sleep
was restored and the foreground notification service remained active. Full route
transition/private-coordinate acceptance and broader parity remain open; see
[NETWORKING.md](NETWORKING.md). Signed build 157 is unchanged.

**Private-address implementation (2026-09-29):** Settings now edits per-Mac
numeric IP/UDP coordinates, enables/disables them, and confirms removal/reset.
Storage is local, excluded from backup and scoped to the full enrolled identity;
enabled hints are supplied on the next authenticated native connection. Nineteen
JVM checks and both Android UI cases pass (one final-run teardown timeout is
recorded, followed by a successful focused rerun). The
active iOS V2 adapter supports these paths but rejects legacy custom-relay and
relay-selection controls. The corrected contract and remaining direct-only/live
acceptance work are tracked in [NETWORKING.md](NETWORKING.md).

**Connection Check implementation (2026-09-29):** Settings verifies the selected
Mac identity and authenticated RPC access, reads the actual selected native path
and RTT, and offers a report without names, addresses, credentials or terminal
content. Eight focused JVM checks, three emulator UI cases and one native QUIC
path test pass. This does not complete the iOS Networking
screen: active runtime settings, private routes and remaining
connection stages are tracked in [NETWORKING.md](NETWORKING.md).

**Team creation implementation (2026-09-29):** Settings now includes Create Team
with name validation, pending-state duplicate-submit protection and the production
cmux creation route. Known-created teams remain selectable if the later refresh
or selection fails; uncertain POSTs are never automatically repeated. Sixteen
account-controller JVM checks and three emulator UI cases pass; pending/error
dialog screenshots were inspected. Live-account creation has not been performed.
See [TEAM_CREATION.md](TEAM_CREATION.md) for the upstream contract and acceptance.

**Uncertain-input checkpoint (2026-09-29):** 26 focused JVM checks and one
Android 17 UI case passed. The controlled host loses a composer acknowledgement;
the app automatically reconnects without resending, preserves the draft/warning,
and accepts a new explicit command. The native input queue also preserves its
no-replay/explicit-resume behavior after lane repair. These are fixture checks;
real-Mac uncertain-send timing remains open. See the runtime checkpoint for exact
evidence and the initial emulator startup failure.

**Latest physical checkpoint (2026-09-29):** authenticated Mac/Pixel terminal,
Files and notification checks are recorded below. A controlled 37.98-second
network outage recovered automatically 28.0 seconds after restoration with all
six output markers. Unsent draft retention was also verified in a separate run;
input submitted during uncertain delivery remains unverified. The long-title
header fix is installed and exercised on the Pixel, including Keyboard/Compose,
menu and Back. Sign-in survived the upgrade and the background service restarted.
Temporary phone network/sleep settings were restored. See
[NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md) for exact scope and
APK hashes. The goal remains active; signed build 157 is unchanged.

**Terminal resize follow-up (2026-09-29):** the last painted frame now stays visible
while a same-terminal resize waits for replay; changing terminal/client resets it.
20 focused JVM checks and both new grid/byte pixel checks passed. The existing
keyboard/input and byte-recovery cases also passed in a separate emulator run.
Inspected screenshots show Gboard and retained/settled output. See the resize
section of [NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md) for the
failed attempts, corrected fixture, final evidence and APK hashes. Physical Mac/
Pixel acceptance and complete terminal fidelity remain open.

**Latest combined runtime checkpoint (2026-09-28):** 441 JVM tests passed;
15 native-module and 16 app tests passed in separate Android 17 emulator runs.
This run found and fixed shared RPC disconnection when a screen cancelled during
a frame write. Direct/composer images, task uploads and native terminal/artifact
lanes now have emulator runtime evidence. A new combined debug APK is prepared;
physical Pixel/live Mac acceptance and full rendering parity remain open.
See [NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md) for the failure,
fix, exact test scope, APK hashes, screenshot limits and next device work.

**2026-09-28 live-host correction:** the installed cmux 0.64.25 pairing panel is
Iroh-only; the pinned active `MobilePairingModel` confirms v2 publishes no legacy
Tailscale QR/TCP route. The user approved enabling pairing; the panel reached
Iroh Ready. Native Android connectivity requires **CmuxIrxTransport** and V2
enrollment/discovery. Legacy TCP fixtures do not establish compatibility with
this host. See [IROH_V2.md](IROH_V2.md) for the revised first milestone.

**Physical viewer check (2026-09-28):** all four outstanding syntax/text/Markdown
fixture methods passed on the actual Pixel 6a, Android 17, in 10.981 s. Screenshot
pixel assertions and visual inspection confirm both Mermaid labels/arrow and Vega
bars. See [SYNTAX_CHECKPOINT.md](SYNTAX_CHECKPOINT.md) for APK hashes and evidence.
Live Mac connection and broader device acceptance remain open.

**Native transport checkpoint (2026-09-28):** the exact Iroh fork built for Android
arm64; two crypto/QUIC tests and five Irx admission/framing tests passed on the
physical Pixel. The module is isolated, with no current Mac enrollment or app
connection yet. See [IROH_V2.md](IROH_V2.md) for build pins and test scope.

**Main native packaging/account scope (2026-09-28):** the main debug APK now
includes the verified arm64 Iroh/JNA libraries and their notices; APK alignment,
signature and native hash checks pass. Account membership and Settings team
selection are implemented with 10 local network tests. A combined run with 25 V2
checks passed all 35 cases. This new APK has not been installed or connected to
the Mac. Other native ABIs and live account/device acceptance remain open. The next checkpoint below connects the account scope to the endpoint/RPC owner.

**Account discovery integration (2026-09-28):** production app wiring now owns
V2 discovery, the protected installation key, lazy native endpoint and shared
Mac RPC connections in one account/team lifetime. The Computers screen selects
fresh directory entries; saved native locators contain no credentials and are
checked against current scope before dial. UI, aggregate feeds and background
notifications use independently closable leases on one admitted Mac connection.
The full JVM run passed 365 tests (61 suites). This is local/build evidence;
Pixel enrollment, relay traversal and actual Mac traffic are still unverified.

**Recovery follow-up (2026-09-28):** bounded forced-token/API-ticket recovery
now handles explicit authentication rejection. Native dial timeouts remain
reconnectable without swallowing actual caller cancellation. Revocation remains
terminal; fresh sessions can recover expired challenges, with server rate-limit
delays honored. See [IROH_V2.md](IROH_V2.md) for test scope and remaining recovery
work. This follow-up has no new APK or live-device claim.

| Area | iOS source / contract | Android status | Acceptance check |
| --- | --- | --- | --- |
| Account | `MobileAuthComposition`, `MobileRootAuthGate`, `AuthCoordinator` | OTP sign-in/encrypted token refresh coded; verified team membership and Settings selection implemented with login/team generation guards, HTTP cancellation and explicit token refresh tests; team creation through the cmux backend and verified selection implemented; team caching, account deletion and live phone account QA remain open | Sign in with the Mac's cmux account/team; switch teams without stale authority; restore session; sign out |
| Computers | `MobilePairedMac`, `MacComputerListSection`, `V2ControlService` | Saved-Mac list/select/forget coded; V2 account-session directory pagination, pushed changes/revocation, lease expiry and relay renewal implemented with local network fixtures; selected-team discovery now feeds a Computers screen and scoped saved locators; live account discovery remains unverified | Discover, choose, forget, and reconnect multiple Macs on the actual account |
| Pairing | `MobileIrohV2InstallationStore`, `V2ControlService`, legacy `CmxPairingQRCode` | Scoped protected native identity verified on Pixel; signed V2 enrollment implemented with local network fixtures; legacy QR/deep-link codecs retained; actual account enrollment remains open | Enroll Android against current Mac/account, validate key/scope, admit and revoke; QR only for hosts exposing the legacy route |
| Transport | `CmuxIrxTransport`, `MobileCoreRPCSession`, legacy `CmxNetworkByteTransport` | Iroh library, QUIC and admission verified on Pixel; V2 control service implemented; native endpoint owner and RPC control/event adapters compile, with stream isolation and RPC lifecycle JVM checks. Account owner/computer picker wiring and shared foreground/background RPC leases are implemented; control-stream replacement, read-only resend and whole-connection closure observation are implemented; lifecycle-aware diagnostic keepalive and positive silence evidence are implemented; real relay/repair/background verification and specialized lane consumers remain open. optional event-stream isolation and relay-preserving direct-path authorization now follow iOS. dedicated render-grid input stream integrated with lease lifecycle and readiness checks. dedicated duplex terminal output/input and replay barriers now integrated. native authorized artifact downloads integrated with before-byte fallback and EOF validation. Last full JVM checkpoint: 425 tests; combined main/test debug APKs built, native loopback methods not run. Subsequent HTTP-to-WebSocket restoration is implemented with 33 focused checks; not yet in that APK | Current Mac Irx connection with control/events/terminal/artifact lanes, reconnect without duplicate input |
| Workspaces | `MobileSyncWorkspaceListResponse`, `DeviceTreeView` | Native list, sections, previews, colors, compact toolbar, computer picker, unread filter, workspace/terminal/browser creation, rename, pin/read, close, group create/rename/pin/ungroup, and move RPCs coded; anchor hierarchy, durable empty headers, saved collapse state, and bounded drag reordering coded; numeric unread badges and common group icon equivalents coded; arbitrary custom SF Symbols and phone QA pending | Live hierarchy, add/rename/close/reorder/group, status and selection |
| Terminal | `MobileTerminalRenderGridFrame`, `GhosttySurfaceView` | Styled render-grid with grapheme cell placement, semantic colors, wide cursor, delta/screen continuity and bounded local scrollback verified with JVM/emulator fixtures; actual viewport reporting/clear coded; native VT fallback, hybrid delivery, byte-gap recovery and screen-anchor negotiation verified with JVM/emulator fixtures and a captured Vim session; iOS-style View as Text with native selection/copy, cell tap, coalesced wheel RPCs and host viewport scroll responses verified with emulator fixtures; Android kinetic scrolling and cancellation verified with gesture fixtures; dedicated native duplex byte streams now feed the same mirror with unsigned cursors, overlap suppression and replay barriers; complete Ghostty fidelity, pixel scrolling/inline graphics, and phone resize QA missing | Stream render grid or VT bytes; colors, cursor, Unicode, alternate screen, scrollback, resize |
| Input | `TerminalInputTextView`, `MobileTerminalInputResponse` | Native multiline paste/submit, encrypted per-Mac/terminal drafts, pending-send guards and acknowledgement reconciliation verified with an emulator fixture; direct IME keyboard, Unicode composition, repeated deletion, ordered input and explicit recovery from rejected delivery verified with an emulator fixture; modifier/navigation/control toolbar and hardware keys coded; photo/file picker, encrypted attachments, image paste and chunked file upload verified with an emulator fixture; dedicated native input stream for render-grid terminals now implemented with framed UTF-8, readiness and no replay after uncertain writes; direct IME image paste and toolbar clipboard attachments implemented with ordered preparation and grant cleanup; IME image attachments wired into terminal/task Compose editors; complete mode handling, latency marker negotiation and phone QA remain open | Soft and hardware keyboard, modifiers, paste, image/file input, shortcuts, safe retry |
| Notifications | `NotificationFeedView`, `CmuxAppDelegate` | Native in-app feed, workspace/source/preview/time rows, read sync, provenance-based moved-terminal navigation and per-Mac view state coded; search/navigation verified with emulator fixtures; encrypted per-pairing alert identity and exact terminal routes, independent saved-Mac workers, read/forget cleanup and host-identity checks coded; combined saved-Mac feed, day/history grouping, unread filter, pull refresh, read/unread gestures and confirmed bulk read coded; offline snapshots and revision guards tested with loopback peers; event/mutation revision floors, bounded refresh retries and Activity-recreation retention coded; computer picker, scoped unread badge/bulk actions and live-destination filtering before the global cap coded; server push fallback and phone QA pending | Feed, unread counts, actions, deep links, Android background delivery, read sync |
| Browser | `CmuxMobileBrowser`, `MobileBrowserFrameEvent` | JPEG/PNG stream, bottom navigation/address/loading controls, direct IME and hardware input, live viewport updates, ordered/coalesced scrolling, frame validation and stale-dialog fencing coded; seven browser-view checks and three browser momentum gesture checks have passing emulator evidence across initial/focused runs, including watchdog/lifecycle recovery and momentum/cancellation; width-fit geometry, local pinch zoom/pan and repeated-tap click counts also verified in a clean 13-case browser emulator run; download behavior and phone QA pending | Show browser panels; navigate, scroll, tap, type, handle dialogs and downloads |
| Search | `MobilePrimaryTabScaffold`, `MobilePrimarySearchCoordinator` | Independent workspace/notification queries, bounded Unicode editing, group/computer/description and notification metadata matching, submit/clear and result navigation verified with JVM/emulator fixtures; cross-computer notification search and exact target navigation verified with emulator fixtures; iOS 26 primary-tab structure with separate Search control, cancel/submit lifecycle, unread badge and floating New Task entry coded; cross-computer workspace aggregation coded; phone QA pending | Search workspaces and notifications with matching navigation |
| Changes | `CmuxMobileChanges` | Collapsible directory tree, path-stable diff pager, numbered/wrapped hunks, grapheme-safe emphasis, line/hunk copy, persistent pinch font, refresh/retry and progressive 6,000→24,000→96,000-line loading coded; identity-checked hidden-context expansion and chunked content transfer coded; model/request checks passed; Before/After image, PDF, media and file-action previews coded; ten Changes cases passed in an Android 17 emulator run; rendered Markdown now shares the original cmux web assets; unified viewer/text controls coded; phone QA, raw syntax/streaming and document-format parity remain open | View changed files and diffs from the active workspace |
| Terminal Files | `TerminalArtifactFilesSheet`, `ChatArtifactFolderView`, `ChatArtifactViewerDestination` | Scoped RPC/paging/search store, Session/In view sheet, filters/sort, list/three-column grid, thumbnails, folder navigation, swipe previews and file actions coded; terminal menu/counted chip and direct relative/absolute path taps capability gated; 285 JVM checks and all 12 combined Android 17 Files/shared-preview runtime cases passed; row Share, folder-tap preference and rendered Markdown have passing runtime evidence; unified viewer/text controls coded; raw syntax/streaming, remaining menu fidelity, broader document formats and physical acceptance remain open | Browse terminal/session files, folders and previews without crossing authorization scopes |
| Tasks and agents | Task composer in `CmuxMobileShellUI` | Editable Claude/Codex/OpenCode/Shell and custom templates, agent icons, remembered Mac/agent/directory defaults, live model/effort choices, Mac/folder/name/group task options, scoped discovery/cache, encrypted saved drafts with stable retry IDs, completed-operation refresh/start-again recovery and new-workspace task RPC coded; task attachment import/storage/upload/retry, full-height prompt canvas and compact keyboard dock coded; offline composition and first-handshake draft adoption verified with emulator fixtures; remaining UI fidelity and phone QA pending | Create and navigate tasks; handle agent prompts and attachments |
| Settings | `MobileSettingsView` | Account, saved-computer, background notification, terminal size, connection status, Open Folders on Tap and Show Missing Files controls coded; local reset with confirmation, platform-owned erase and emulator acceptance implemented; live native route/RTT, Mac identity/account checks and address-free report sharing implemented; scoped private-address editing/reset and native dial hints implemented; active V2 status/home-relay/credential refresh and real-Pixel checks implemented; direct-only intents and broader diagnostics remain open (see NETWORKING.md for legacy-control correction) | Account, computers, notification, display, network, diagnostics, reset |
| Device behavior | iOS lifecycle, accessibility, background push | Keyboard resizing verified with an Android 17 emulator fixture; visible terminal text exposed to accessibility; broader lifecycle and phone QA missing | Rotation, keyboard, process death, offline recovery, screen reader, battery |
| Delivery | iOS release checks | Native debug/release builds and stable signing verified in CI; Pixel run pending | Stable signed APK, upgrade in place, reproducible CI, Pixel acceptance run |

## First authenticated Pixel/Mac acceptance (2026-09-29)

The top table contains cumulative implementation history. Its older “phone QA”
notes are superseded **only for these bounded checks** by source `3ca2d22`:

- Email sign-in and native account connection reached the real Mac workspace list.
- Android created a separate workspace; composer input executed a shell command.
- Actual Gboard key taps executed `pwd` and displayed the Mac result.
- Keyboard show/hide and terminal reopen succeeded after the viewport generation
  fix, with no persistent resizing banner.
- Force-stop/relaunch retained sign-in; manually reopening the workspace recovered
  terminal history. Automatic selected-screen restoration/network-outage recovery
  were not established.
- The detected-folder Files chip browsed the Mac repository and displayed an exact
  116-byte `.gitignore` preview.
- A Mac `cmux notify` appeared in the foreground notification feed with the expected
  unread count; tapping it opened the corresponding test terminal.

See [native runtime checkpoint](NATIVE_RUNTIME_CHECKPOINT.md) for artifact hashes,
source comparison, evidence and limits. This does not close full terminal fidelity,
all artifact formats/actions, long-term background/Doze behavior, server push, multiple-Mac/team behavior,
or full iOS parity. Signed release build 157 remains unchanged.

## Background notification acceptance (2026-09-29)

The Pixel received a real Mac notification as an Android system alert while cmux
was backgrounded on the launcher. Tapping a second delayed alert reopened the
correct native terminal. This verifies the opt-in foreground service and system
notification route on Android 17. The user’s notification permission and background
setting are enabled. Doze, boot and network recovery, plus server push while the
app process is stopped, remain separate open gates. Details and evidence are in
[NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md).

## Local reset parity (2026-09-29)

Settings now includes **Erase All Data on This Device**, following the pinned iOS
`MobileSettingsResetSection`: an explicit destructive confirmation, a description
of local data removal and preservation of remote account/computer data. Android's
`ActivityManager.clearApplicationUserData()` handles the process stop and complete
app-data reset; workers cannot repopulate a manually emptied credential store.
The row prevents a second request while reset is pending, reports rejection, and
requires fresh confirmation for a retry. Android also resets runtime permissions
and URI grants; the dialog explains the app closure and permission reset.

Three Android 17 UI tests passed for cancel, single confirmed request, and false/
throwing platform failures. A separate emulator-only acceptance runner seeded an
encrypted account, Android Keystore key, notification setting, internal/cache/
external files and notification permission, then pressed the real confirmation.
Android terminated the process. A new instrumentation process verified every seed
was absent, the key was removed and notification permission was revoked (**1 test
passed**). The interrupted erase process itself is not counted as a passing test.
No physical-device account data was reset.

Reproduce on an explicitly selected disposable Google emulator after installing
both APKs:

```sh
python3 scripts/check-local-reset.py --serial emulator-5554 --adb "$ANDROID_HOME/platform-tools/adb"
```

The runner and test both reject physical devices, and the platform test skips by
default without its explicit phase argument. Evidence is in ignored
`captures/local-reset/20260929T103259Z` (receipt and both process logs); debug APK
SHA-256 `580c33e5160ea1bcc111030aa27b24d803f12f5d1e8baeeac3c9183966e04231`.
[Android reset contract](https://developer.android.com/reference/android/app/ActivityManager#clearApplicationUserData()).

## Native 16 KiB runtime acceptance (2026-09-29)

Source `8525b12` passed all four native graphics/terminal/artifact tests on Google's
Android 17 arm64 16 KB system image, revision 7. The emulator kernel reports 16384
bytes per page; package manager reports `pageSizeCompat=0`, and the app launches
without the RELRO warning. APK LOAD, RELRO and ZIP alignment checks also pass.
See [NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md) for hashes,
evidence and the bounded acceptance scope. This closes the actual 16 KiB kernel
check, beyond the earlier Pixel 4 KiB kernel's package-compatibility check.

## Terminal modifier follow-up (2026-09-29)

The pinned iOS `TerminalInputModifierState`, `TerminalKeyEncoder`, and
`TerminalInputTextView` define one active accessory modifier and a strict 400 ms
double-tap window for locking it. Android now exposes Ctrl, Alt, Cmd and Shift
with one-shot/locked states, a blue active fill and outlined lock, and accessibility
state descriptions. Switching modifiers clears the previous lock. Terminal or
client changes, opening the composer/Files, and pasting clear accessory state.

Cmd text implements the iOS readline mappings (A/E/K/U/W/L/C/D), Cmd+Left/Right
move to line boundaries, and Cmd+Backspace deletes to the start of the line.
Alt+Left/Right use ESC-b/ESC-f and Ctrl+/ sends 0x1F. IME deletion batches encode
each requested delete with the active modifier, then consume a one-shot state;
editing uncommitted composition does not consume it. Hardware VT modifier
combinations remain distinct from the iOS accessory special-key behavior.

Seven focused JVM tests passed, including all four modifier states, the exact
double-tap boundary, exclusivity, Command mappings, Unicode commits and special
keys. Two Android 17 emulator cases passed together in 36.1 seconds: the new
Compose/IME modifier workflow and the existing direct-input regression. They
verify exact RPC bytes, repeated deletion, composition, locked-state persistence,
composer/terminal reset, stale input-connection rejection and recovery after a
rejected send. The locked-button screenshot was inspected with Gboard visible
and no system dialog. Evidence: `captures/runtime/modifiers/` (Git-ignored).
See [NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md) for current APK
hashes. This does not close
hardware-layout, complete terminal-mode, toolbar-customization or physical Pixel/
Mac acceptance work.

## Direct keyboard images and clipboard attachments (2026-09-28)

The pinned iOS `TerminalInputTextView` routes an image paste directly to
`terminal.paste_image`; `MobilePasteboardAttachments` stages composer attachments.
Android now accepts `image/*` through the direct terminal IME's `commitContent`,
including temporary read grants held until import/delivery finishes or is cancelled.
Hardware and context-menu paste share clipboard classification: images take precedence
over captions, content-backed documents remain attachments, and ordinary text/web
links remain text. Local URI and Intent coercion cannot become terminal input.

Direct image preparation reserves a position in the existing input queue before
reading the provider, so following keystrokes cannot overtake the paste. Direct
file paste uses the host's attachment capability and shell-quoted returned path.
Toolbar paste in composer mode stages attachments in the existing encrypted draft
repository. Captured Mac/terminal, account generation and connection are checked
around imports/uploads. At most four rich operations wait; dropped, rejected,
completed and cancelled operations release their grants. Unconfirmed delivery
pauses input, discards queued work and requires explicit resumption without replay.

Verification: 14 focused JVM tests passed (9 input queue, 5 terminal drafts);
Android instrumentation compiled. New `TerminalRichInputTest` methods cover MIME
advertisement, stale/disabled IME rejection, mixed clipboard classification,
bounds and cleanup. New `NativeFlowTest.keyboardImageReachesExactTerminalBeforeFollowingKeysWithoutChangingComposerDraft`
checks the actual screen's image RPC, decoded image pixel, target and ordering.
These four new device methods have **not run**. Local evidence is under
`captures/input/rich-paste/`. No APK was rebuilt or published for this checkpoint.

At this checkpoint, Compose terminal/task IME delivery was still missing; the
follow-up below implements it. Actual Gboard permission-grant behavior, clipboard
UI on the Pixel and authenticated live-Mac image/file acceptance remain unverified.

Android API reference: [image keyboard content and grant lifecycle](https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard).
The [Compose content receiver](https://developer.android.com/develop/ui/compose/touch-input/copy-and-paste)
requires a state-based field. The follow-up below uses the platform input interceptor
instead for IME images; no text-state migration is needed for that path.

## Composer and New Task keyboard images (2026-09-28)

`RichContentEditor` wraps each existing Compose input connection with
`InterceptPlatformTextInput`. It advertises images to the keyboard, delegates
text/selection/composition to the original editor, and rejects rich input after
connection close, session cancellation or a change of draft/connection owner.
Availability changes restart the platform session so its advertised types refresh.
The installed Compose 1.9.1 sources confirm that value-based fields use this same
platform session API (`LegacyPlatformTextInputServiceAdapter`); rebuilding the
editors around separate text state would add unnecessary synchronization risk.

Terminal composer images stage in the captured encrypted draft until Send/Insert.
New Task images share the existing photo preparation, encryption, preview and
upload flow. Task imports now reserve preparation synchronously, reject batches
that exceed the remaining slots before importing any item, classify clipboard
items individually, and check the exact editor lease after asynchronous reads.
Temporary IME grants are released when accepted imports finish, fail or cancel;
rejected images release their grant immediately. Direct terminal input shares the
same grant acquisition/release implementation.

Verification: 35 focused JVM checks passed (9 queue, 5 terminal drafts, 12 task
drafts, 9 task attachments), and Android instrumentation compiled. New tests:

- `RichComposerEditorTest`: actual Compose connection preserves selection/text
  around an image and rejects stale, disabled and closed input owners (2 methods).
- `NativeFlowTest.composerKeyboardImageStagesUntilSendAndPreservesItsText`:
  actual screen stages the image, then sends its decoded pixel and draft text.
- `NativeTaskAttachmentsTest.keyboardImageStagesWithPromptAndUploadsWhenTaskIsCreated`:
  actual New Task prompt stages/preserves its image bytes and uploads on submission.

These four methods are **compiled only**, pending Pixel execution. Local evidence:
`captures/input/composer-images/`. No new APK was packaged/published. Existing
clipboard toolbar/menu actions work separately; rich images through the Compose
selection popover and drag-and-drop are not implemented by this IME interceptor.
Real Gboard temporary grants and live Mac workflows still require device acceptance.

## Source audit: Files and removed GUI chat (2026-09-28)

Upstream [PR #10576](https://github.com/manaflow-ai/cmux/pull/10576), merged
2026-08-23, removed the iOS GUI agent-chat screen, transcript and composer. The
current reference commit retains artifact previews, the terminal Files gallery,
Markdown support and artifact RPCs under `mobile.chat.*`. Those RPC names do not
imply that the removed GUI chat is part of the current iOS companion.

Earlier dated entries below that list “agent chat” or “chat UI” as a parity gap
were mistaken. This audit supersedes those statements. The actual outstanding
scope is terminal Files (Session/In view, search, filters, sorting, folders,
previews and actions), along with the other open areas in the table. Haskell and
PureScript highlighting also belongs to artifact code viewing, not a chat screen.
No existing GUI chat implementation or verification is being claimed.

## Raw-code syntax highlighting (2026-09-28)

Raw Files/Changes viewing now uses the exact Highlightr dependency pinned in the
iOS Package.resolved: Highlightr 2.3.0 at
`05e7fcc63b33925cd0c1faaa205cdd5681e7bbef`. Its unmodified highlight.js 11.11.1
bundle contains 192 languages, including Haskell (also used for PureScript by
cmux), and both Xcode palettes. The three asset hashes are recorded in
`assets/raw-code/manifest.json`; `scripts/sync-raw-code-highlighter.py` reproduces
them. The MIT/BSD notices are bundled and visible in Open-source licenses.
All 57 extension mappings match the pinned iOS policy, and all 35 mapped language
IDs are available in the bundle.

The policy matches iOS: highlight recognized languages through 1,500,000 bytes;
automatically detect unknown languages only below 256,000 bytes. Larger files
show the expandable Highlighting off pill with the file size and threshold.
They retain the raw viewer and its controls.

An off-screen WebView runs only the bundled tokenizer and styles, with networking,
file/content access and navigation blocked. The source is passed as JSON data.
Generated HTML is converted to contiguous UTF-16 style ranges; CR/CRLF are
preserved and the decoded text must match the original exactly before anything
is applied. The displayed view remains the native selectable buffer. Attributes
supply foreground color and bold/italic monospaced traits, preserving the user's
font size and avoiding theme backgrounds that would cover search highlights.
Page cancellation closes the worker, a deadline bounds waiting, and an engine
failure leaves plain text available. A shared coroutine lock serializes workers.

All **307 JVM cases passed**, and debug/test APK assembly succeeded. The final
11-case Android run recorded **7 passes, 3 failures, and 1 started without a
result** before interruption. Four of the five new syntax cases passed; the
late-coloring case failed on a null clipboard read. Two existing text cases failed
on clipboard/gutter checks. A System UI ANR dialog was observed over the app; its
role in these failures is not yet confirmed by a rerun. See
[SYNTAX_CHECKPOINT.md](SYNTAX_CHECKPOINT.md) for exact outcomes and
[HANDOFF.md](HANDOFF.md) for continuation steps. Runtime verification remains
incomplete. This does not prove large-file performance, incremental remote
viewing, complete lifecycle/fault recovery or physical Pixel/Mac acceptance.
Signed build 157 remains the current download.

## Unified viewer menu and native text controls (2026-09-28)

Files and Changes now share one ellipsis-circle Viewer actions menu, following
`ChatArtifactViewerActionsMenu`. It contains Share/Save/Open, Copy Contents for
text, Copy Image for images, raw-text controls and the Markdown Raw/Rendered
choice. The previous separate Markdown chips are removed. Copy Contents uses the
upstream 4 MiB availability threshold; the original text is copied without gutter
numbers or a rich-text transformation. Android clipboard delivery failures are
reported in the viewer.

Raw text uses one native selectable TextView buffer with two-axis scrolling.
Selection can cross lines, preserving Unicode and original newline sequences.
The logical-line gutter is enabled by default, remains independent of copied
text, and numbers logical lines rather than each wrapped visual row. The viewer
adds case-insensitive literal search, highlighted matches, current/total count,
wrapping previous/next navigation, Go to line (clamped to loaded line bounds),
Top and End. The line index uses UTF-16 offsets, including a trailing empty line.

Word wrap and font size are saved independently for code, log and plain text,
using the pinned iOS extension set (including Haskell/PureScript). Logs default
to no wrap; code/plain text default to wrap. The size starts at 15 sp and is
clamped to 8–28, with both a slider/reset control and a two-finger pinch gesture.
Android's system font scaling is applied to the displayed size.

All **305 JVM cases passed**, with zero failures/errors/skips. The first combined
19-case Android run passed 14 cases. The remaining failures led to fixture fixes
for sheet-window lookup and asynchronous layout, and production corrections for
wrapped measurement (including the first layout), the hardware-cached gutter and
pinch accumulation. Focused reruns now provide passing evidence for all **19
distinct Android cases across runs**; this is not a clean 19-case run on the final
source. The last three-case run passed search/jumps and selection/copy/gutter;
its remaining preference/pinch case passed the focused rerun in **7.803 seconds**
with a gesture wide enough for Android's scaling threshold.

The assertions inspect actual native text, clipboard bytes, viewport movement,
saved per-kind preferences, rendered line layouts and on-screen gutter pixels.
The numbered-view screenshot was inspected. Original failures, corrected runs,
build logs and the screenshot are retained in `captures/artifacts/`. One emulator
launch hit a process-start timeout before instrumentation; its exit record is
retained, and a warm launch of the unchanged APK succeeded before testing.

This does not complete the entire artifact viewer: raw syntax highlighting,
incremental remote text display and large-file layout/performance, zoom/scroll
anchoring, broader document formats, remaining menu-icon/interaction fidelity
and physical Pixel/Mac acceptance remain open. Text is currently displayed after
the existing bounded download completes. The signed download remains build 157.

## Shared Markdown renderer and Files runtime follow-up (2026-09-28)

Files and Changes now render Markdown with the same bundled shell, marked,
highlight.js, GitHub CSS, Mermaid and Vega libraries as iOS. The 11 original
assets are copied without modification from the pinned cmux revision; their
SHA-256 hashes are recorded in `assets/markdown-viewer/manifest.json` and
`scripts/sync-markdown-viewer.py` reproduces the copy. Third-party notices and
MIT/BSD licenses are bundled and visible in Open-source licenses.

The Android host supplies the local bridge, lazy diagram loading, Rendered/Raw
selection and the exact 1,500,000-byte rendered-mode threshold. It preserves
same-page fragments and opens activated HTTP(S), mail and telephone links through
Android. Local Markdown links and relative images remain unresolved, matching
the inspected iOS host. Raw HTML is handled by the original sanitizer. CSP and
request interception prevent implicit network loads; remote images retain the
original per-image consent UI. The image transport permits HTTPS on port 443,
rejects private/reserved addresses and mixed DNS answers, uses those validated
addresses for TLS, permits at most three same-host redirects, and bounds decoded
image data to 8 MiB with an image MIME allowlist. It uses OkHttp/Okio with system
TLS verification and cancels outstanding image calls on closure.

The renderer follows the app's dark palette through a scoped Android theme,
scales text with the system font setting, and releases its WebView/bridge/jobs
when leaving the page. Renderer process loss has two bounded recovery attempts
before falling back to raw source. Remote-image live-network acceptance,
renderer-recovery injection, full zoom/accessibility equivalence, unified viewer
menu placement and advanced raw-text functionality remain open; these are not
claimed as completed or unavoidable platform limitations.

Verification:

- All **300 JVM cases passed**. After the host integration correction, the seven
  Markdown policy cases passed again. Debug and instrumentation APKs assembled.
- The first 17-case Android 17 integration run passed 14 cases, including gallery
  Share's exact bytes, session scope and read-only URI after sheet closure, the
  other Files flows, and shared image/PDF/audio previews. Two Markdown cases
  exposed the local shell being blocked by the request interceptor. The folder
  preference fixture incorrectly expected `mobile.terminal.click`; the actual
  RPC is `mobile.terminal.mouse`. The positive and negative assertions now use
  the real method.
- With the shell allowance and fixture fixed, a clean five-case rerun passed in
  **58.231 seconds**, covering all three Markdown cases plus the two NativeScreen
  chip/path/folder-preference flows. One attempted rerun stopped in a startup ANR
  before any test cases; exit-info and its log are retained.
- Screenshot review then caught light CSS against the dark Android background.
  After the theme fix, all three Markdown cases passed in **31.091 seconds**,
  including computed text-contrast, visible Mermaid geometry, a Vega chart,
  tables, highlighted code, mode switching, the size threshold and inert active
  markup/unapproved remote images. The final screenshot was inspected.

These runs provide passing evidence for 17 distinct runtime cases across the
initial and focused runs, not a clean full-suite run on the final source. Logs,
initial failures and screenshots are in `captures/artifacts/`. Signed build 157
remains the current download; this combined debug checkpoint has not been
published as a new release. Physical Pixel/Mac acceptance remains pending.

## Gallery Share and folder-tap preference (2026-09-28)

Gallery and folder rows now offer Share for non-directories, disabled when the
file is missing or already being prepared. Missing folders disable Browse folder.
The file action captures the row's terminal/session authorization, stats and
streams the original bytes, and uses the host MIME type for the exported name.
As in `ChatArtifactFileActionStore`, sharing does not impose the inline-preview
size limit. Transfers remain chunked and validate size, offsets and EOF. Partial
files are removed on failure/cancellation. A successful share uses a narrowly
scoped FileProvider URI with read permission, and the exported file outlives the
row or sheet. Preparation errors have a dismissible alert.

Settings now expose Open Folders on Tap (default on) and Show Missing Files
(default off). Folder tapping follows `TerminalFolderTapPolicy`: the default
opens immediately without a classification stat; disabling it checks the path
for at most two seconds. Directories and infrastructure failures focus the
terminal, files and terminal-scope `forbidden` responses open the viewer. The
viewer keeps its terminal authorization and displays any refusal; it never
silently switches scope. New taps, terminal/render-source changes and teardown
cancel or invalidate older work. A delayed result rechecks the path before
opening or sending a terminal click; expired noncooperative requests cannot act.

All **293 JVM cases passed**, with zero failures/errors/skips, and Android test
sources compile. Added UI fixtures intercept the actual Share intent and check
its URI/grants/bytes after closing the sheet, and exercise the persisted folder
preference through NativeScreen. Those two new fixtures have **not run yet**;
the previous 12-case runtime result predates this feature. A focused policy rerun
covers normalized refusal codes and cancellation in addition to the original
classification, deadline and stale-result cases. Logs are under `captures/artifacts/`.

No APK was built for this feature commit. Signed build 157 remains the current
download. Richer text/Markdown/document previews, broader parity and physical
Pixel/Mac acceptance remain open.

## Terminal path taps and Files layout (2026-09-28)

Ported the pinned iOS terminal artifact hit tester, including wrapped paths,
sentence punctuation, bare filenames, relative paths and Unicode cell widths.
`scripts/generate-artifact-tap-parity.py` executes the unmodified Swift sources
and records their hashes with 1,217 tap cases, including 246 Unicode cases.
The JVM checks compare all 971 ASCII cases. Android instrumentation exercises
the same full set using the renderer's real ICU cell-width implementation.

Direct taps retain terminal authorization through stat, folders and previews;
relative names are resolved by the Mac against its terminal directory. Session
RPCs continue to require absolute paths. Taps outside the rendered grid cannot
open a path from the nearest clamped terminal cell, and opening a path does not
also send a terminal click. The current default opens folders, matching iOS's
default; its optional folder-tap preference is still to be implemented.

The Files header is centered, search uses a compact filled field, and filters
use capsules with a fixed trailing sort menu. The missing-file control is in
that menu. The combined build passed all **285 JVM cases**, with zero failures,
errors or skips. A clean Android 17 emulator run passed all **12 runtime cases**
in 117.523 seconds: gallery scopes/filter/grid/missing preference, remote search
and exact-path copy, nested folders, transfer retry/close, the full 1,217-case tap
reference, relative folder authorization, five shared image/PDF/audio preview
checks, and the actual NativeScreen chip-to-gallery/relative-path-to-preview flow.
The last flow also asserts that opening a file sends no terminal click.

The initial runtime run passed 11 of 12 cases; AAPT removed the compressed test
asset's `.gz` suffix, causing the remaining fixture to fail before comparison.
The fixture now packages plain JSON; production assets are unchanged. One earlier
JVM run had a loopback connection timeout, followed by clean 284- and 285-case
runs. Initial and passing logs are retained in `captures/artifacts/`, along with
the inspected `files-grid-polished.png`. These use local RPC fixtures; they do
not replace physical Pixel/Mac acceptance.

Signed build 157 remains the current download. This feature is not yet in a
published APK; row Share, richer previews and real Pixel/Mac acceptance remain
open.

## Terminal Files chip (2026-09-28)

Added the counted Files overlay on the terminal. The upstream count policy keeps
accepted session totals across transport failures, invalidates totals when the
bound session changes, distinguishes authoritative gallery-row totals from legacy
session totals, coalesces pending scans, and bounds generation-mismatch rearms.
A zero count keeps the previous chip for the upstream 3.5-second grace period;
positive observations cancel the hide and connection teardown removes it at once.
The missing-file setting also controls count scans. Accepted count reports now
trigger Files-gallery refresh; provisional per-frame counts do not.

`scripts/generate-artifact-count-parity.py` executes the unmodified pinned Swift
count state and path detector. Android matches 6,400 state transitions and 33
conservative path cases. The full JVM run passed **282 cases** with no failures,
errors or skips, and Android test sources compile. An existing browser recovery
test now waits for the actual delivery callback before asserting completion.
Runtime logs, initial failures and final passing output are under
`captures/artifacts/`. No APK was built; the current download is still build 157.

The path reference inputs involving parent components use nonexistent roots to
avoid the generator Mac's `/tmp` symlink affecting Foundation standardization.
Android's local fallback uses lexical normalization; complete cross-platform
symlink-sensitive count equivalence is not claimed. Host gallery totals remain
authoritative. Direct path taps, row Share, richer previews and Files runtime/
physical acceptance are still pending; the chip is not a claim of completed parity.

## Terminal Files UI integration (2026-09-28)

The terminal menu now opens Files when the connected Mac advertises terminal
artifacts. The sheet implements Session/In view selection, whole-session search,
All/Images/Code/Logs/Docs/Folders filters, stable Recent/Name/Size sorting,
collapsible provenance groups, missing-file preference, pull refresh, list and
three-column grid layouts, bounded thumbnail loading, and deferred new-file
updates while reading. Terminal output changes are coalesced into gallery refresh
requests while the sheet is visible. Folder navigation retains the selected scope
and validates each immediate child path. Long press offers Copy path and Browse
folder; the upstream row-level Share action remains to be added.

File selection captures its visible swipe order and authorization. Preview pages
stream to private temporary files and reuse image/PDF/media/text viewers and
Share/Save/Open/Copy Image actions. Exported copies survive preview closure.
Terminal/session chunks use upstream stat-size/offset/EOF validation; Changes
retains its separate fingerprint enforcement. Interrupted, corrupt and cancelled
downloads remove partial files. Folder and preview loads reject obsolete results.

The full JVM suite passed **276 cases** with zero failures/errors/skips. Four new
Android Files fixtures compile (scopes/filter/grid/missing, remote search/copy,
nested folder/preview scope and navigation, corrupt-transfer retry/close). They
have **not yet run**. Logs are retained in `captures/artifacts/`. No APK was built
for these feature commits, and signed build 157 remains the current download.

Remaining Files work includes runtime layout/interaction proof, the terminal Files
chip/count/path-hit flow, row Share, thumbnail/cache and richer text/Markdown/document
fidelity, and physical Pixel/Mac acceptance. The common Code extension mapping was
checked against Apple UTType on this Mac; complete iOS type-registry equivalence
has not been verified. No platform limitation is inferred from these open items.

## Terminal Files foundation (2026-09-28)

Added terminal scan and session gallery wire models, scoped stat/fetch/thumbnail/
folder RPCs, directory capability negotiation, and gallery page identity checks.
Snapshot merging preserves provenance, duplicate-path handling, stable reading
order and deferred refresh behavior. Eager paging follows upstream's 2,000
Referenced-row cap, rejects stale generations, and stops repeated cursors while
preserving a retry position. Immediate directory children cannot escape their
parent path. Fifteen focused JVM cases passed; Android test sources compile.

The per-connection/terminal Files store now binds Session only from a supported
terminal scan. Session and search states remain independent; search trims input
and debounces for 300 ms. Failed page loads retain readable rows and retry the same
cursor. Expired cursors fetch a fresh first page, preserving search order or
showing a deferred new-files count while scrolled. Live refresh retains loaded
history and rejects late replies after close, explicit refresh or generation
recovery. Selection can capture the sheet's session authorization while direct
terminal taps retain terminal authorization.

The full JVM suite passed 264 cases before the final generation-race guard. The
focused 25-case Files rerun then passed with zero failures/errors/skips, and
Android test-source compilation succeeded. Logs are retained in `captures/artifacts/`.

At this foundation checkpoint the Files screen was still pending. The subsequent
UI integration and its remaining verification work are recorded above.
No APK was built for this feature commit; build 157 remains the current download.

## Changed-file revision previews (2026-09-28)

Binary diff pages now use the upstream Before/After policy: modified and renamed
files offer both revisions, deleted files start at base, and added/untracked files
start at current. A rename's base request uses its old path. Revision changes cancel
and remove the prior temporary download. Exact stat/chunk fingerprint validation,
offset/EOF checks and the upstream 64 MiB preview / 512 MiB media limits apply.

Images support local pinch/pan and double-tap zoom, including Android-supported
animated formats. PDFs render lazily into a scrollable page list with page navigation
and per-page zoom. Media uses Android playback controls and pauses in the background.
File actions can share, save, open externally, and copy image content through the
existing narrowly scoped FileProvider. Exported snapshots survive the preview page
so a clipboard consumer or external picker does not lose its source on navigation.
Private partial downloads are removed on errors/cancellation. Text is selectable and
numbered; upstream advanced text/Markdown viewing and broader document preview
formats still need parity work and are not claimed complete.

Verification: 240 JVM tests passed, including seven preview policy, file lifecycle,
export, limit and cancellation cases. A clean Android 17 emulator run passed all ten
Changes cases in 103.843 seconds: tree/pager/copy, error recovery, continuation/font
persistence, context reuse, mismatched revisions, image revision switching/copy,
extensionless image MIME/bytes, PDF page rendering, and audio playback/cleanup.
The initial nine-case run had one fixture synchronization failure after refresh;
its original log is retained alongside the passing run. Test hosts now include the
same Surface and safe drawing insets as the app. This is a local RPC fixture run,
not physical Pixel or live Mac acceptance. A subsequent focused two-case image run
also passed after requiring the blue fixture's rendered screen pixels before capture.
The expanded-diff and image-preview screenshots were inspected. Local evidence is
in `captures/changes/`.

## Hidden-context expansion (2026-09-28)

Added leading, inter-hunk and trailing context bands using the upstream gap rules,
including zero-count hunks, no expansion for deleted files, and no trailing band
for an incomplete diff. Bands reveal 100 lines from the chosen edge, or the full
remaining run when it has at most 120 lines. Revealed context keeps old/new line
numbers and Copy Line; Copy Hunk applies only to actual diff hunks. Stable lazy-row
keys preserve scroll anchoring as context is inserted.

Current file content uses `mobile.workspace.changes.file_stat` and `file_fetch`
with `revision: current`, 3 MiB chunks, the upstream 5 MiB / 200,000-line expansion
limits, and exact filesystem identity comparison across diff/stat/chunks. A changed
identity reloads the diff instead of inserting a different revision. Ordered offsets,
base64 size, total size, EOF and nonempty intermediate chunks are checked. Cancellation
and page generations prevent late downloads from replacing refreshed content. The
transfer also accepts base-blob identities for the upcoming revision preview UI.

Verification: 233 JVM tests passed (13 new expansion/content-transfer cases), zero
failures/errors/skips. Production and Android test sources compile. Two additional
Android cases cover context reuse and revision mismatch; all five Changes UI cases
remain unrun until the combined Changes runtime milestone. No new APK was assembled;
At that point, signed build 150 was the current download and revision preview UI was still open; both features later shipped in build 157.

## Changes viewer foundation (2026-09-28)

Compared the pinned `CmuxMobileChanges` models, file tree, unified parser, pager,
line-copy actions, font preference, continuation policy and shell RPC decoder.
Replaced the flat file list/raw-text view with a collapsible directory tree and
path-keyed diff pager. Directory chains fold into a single row; directory/file
ordering preserves exact paths, including rename metadata, unknown statuses,
binary flags and approximate counts. Refresh, retry, empty and non-repository
states are distinct. Losing the parent connection now shows Back/Reconnect rather
than a blank changes screen.

The parser preserves CRLF content and newline markers, assigns old/new line
numbers, pairs adjacent replacements for grapheme-safe intra-line emphasis, and
builds line/hunk copy text without silently cutting lines at 5,000 characters.
Diff text wraps and is selectable. Pinching changes and persists font size in the
iOS 9–22-point range. Each page retains its scroll position; a seven-page cache and
nearest-first prefetch keep state independent of pager composition. Requests are
scoped to the connection/workspace, fenced against cancelled late responses, and
invalidated when a refreshed repository snapshot changes or removes files.

Continuation now follows the iOS budgets: default 6,000 lines with no explicit
initial override, then 24,000 and 96,000. Failed continuation keeps the current
content available for retry. A larger response that does not grow the transported
window stops offering further growth instead of repeatedly sending larger requests.

Verification: 220 JVM cases passed with zero failures/errors/skips, including 19
new tree/parser/Unicode/continuation/cache/cancellation cases. Production and
Android test sources compile. Three new Android cases are prepared for tree/pager
copy behavior, error recovery, and continuation/font persistence. **They have not
run yet.** Revision previews and hidden-context expansion remain to be implemented;
the combined changes runtime milestone will include them. No APK was assembled or
released for this feature commit; build 150 was then the current download. Local
logs and selected XML reports are retained under `captures/changes/`.

## Upstream refresh (2026-09-28)

Advanced the reference from `4d3385b` to `4c5272e` and inspected the production
changes in the mobile packages. Browser geometry, gestures, payloads and host RPC
handlers are unchanged. The browser store now rejects frames from retired decoders;
Android already fences subscriptions, cancels and joins the old collector before
replacement, and verifies stale-frame rejection in the runtime recovery case.
Other changes concern Apple Ghostty runtime/teardown ownership, a one-pixel workspace
anchor threshold, and Haskell/PureScript chat highlighting. Full Ghostty fidelity,
workspace phone scrolling and agent chat remain open in the corresponding areas.

## Browser image geometry and local lens (2026-09-28)

Matched pinned `BrowserStreamTransform`, `BrowserStreamContentView` and
`BrowserStreamTapClickCounter`. The page now preserves its aspect ratio, fits the
view width and centers vertically. Drawing and pointer input share the same
transform; letterbox taps are ignored. Both scroll axes use the width-fit scale,
with a page-center fallback when the scroll anchor is outside the image.

A unified recognizer arbitrates immediate taps, remote dragging/momentum, a local
1–4× pinch lens, and local panning while zoomed. Pinching or local panning does not
forward wheel/click input to the Mac. Pinching back to 1× clears the local pan and
restores remote scrolling. Repeated taps use the iOS 450 ms / 28-point chaining
thresholds and forward rising native click counts without delaying the first tap.

All 201 JVM cases passed, including seven new geometry/letterbox/coordinate/click
contract checks. Debug/test APKs assembled. Three new Android cases exercise pixel
geometry, click chaining, zoomed input alignment and zoom limits. All 13 browser,
lens and momentum cases passed together in 80.378 seconds. The generated four-color
fixture screenshot was inspected: it preserves the 2:1 page aspect ratio and centers
vertically, with black letterboxing. Pixel assertions and post-pan click coordinates
also passed. Evidence is in `captures/browser/lens-runtime.log`,
`lens-verification.json` and `browser-width-fit.png`. This is a focused emulator
run, not a full 87-case suite or physical Pixel/Mac run. The current signed phone
download, build 157, includes this work together with recovery, momentum and Changes.

## Browser momentum follow-up (2026-09-28)

Compared the pinned iOS `BrowserStreamContentView`, scroll phase policy and scroll
batcher. Android now uses native spline deceleration after a swipe and maps each
delta from view pixels to Mac page points. Drag and momentum phases stay ordered,
with movement coalesced only within the matching phase. A new touch, keyboard or
navigation input, address editing, viewport change, stream replacement, modal,
background or disposal cancels old motion. Pending scroll requests are discarded
before newer input; an already in-flight request remains ordered ahead of cancellation.

All 194 JVM cases passed, including five new phase/backlog/cancellation cases.
The combined debug/test APK build succeeded. The initial Android run passed all
seven browser-view cases, including both recovery cases, but the three isolated
gesture fixtures recorded no input. The fixtures now render content and finish
measured-size recomposition before freezing animation time. A focused rerun passed
all four affected cases: three gesture cases and actual browser-view/RPC scrolling.
This gives ten distinct cases with passing evidence across runs, not a full-suite
run. Checks cover natural momentum completion, new-touch/key cancellation,
viewport/stream replacement, coordinates, phase order, and both swipe directions.

A source review during this run caught and corrected an existing scroll-direction
error: iOS negates UIScrollView content-offset displacement, which already has the
opposite sign to finger motion. Android now sends finger displacement/velocity
without another negation. Both axes and directions are checked. The original
failed run and the passing rerun remain in `captures/browser/momentum-runtime-initial.log`
and `momentum-runtime-rerun.log`; `momentum-verification.json` records the tested
revision. The feature commits did not trigger release builds; the combined signed
build 150 now includes them. The source audit also identified iOS
width-fit image geometry, local pinch zoom, panning while zoomed, and immediate
rising Mac click counts for repeated taps. The subsequent geometry/lens section
above records their implementation and current verification status.

## Browser recovery follow-up (2026-09-28)

Matched the pinned iOS `BrowserStreamRecoveryPolicy`, `BrowserStreamStore` input
watchdog, and browser foreground/background lifecycle. A quiet page alone never
restarts a stream. Forwarded input without a displayed frame for 2.5 seconds arms
one recovery attempt; ordinary state events do not count as frame evidence. The
policy retains the upstream four-second restart backoff and clears evidence for a
new subscription. Android also preserves event order within a millisecond.

Recovery keeps the last image while resetting the new subscription's sequence
check. Requested subscription IDs are known before listening, so old events cannot
seed a replacement stream's high-water mark. Cleanup/start operations are serialized.
Backgrounding stops the stream and cancels recovery timers; foregrounding re-arms
it. Idle input stays usable, while outstanding input is discarded with an explicit
notice and never replayed. The parent shows a reconnecting view while replacing a
failed client. RPC timeouts now remain reportable without swallowing real coroutine
cancellation when the panel closes.

Initial focused verification passed all 189 JVM cases, including eight new
policy/controller, input-queue and cancellation checks. The two new Android cases
now also pass in the combined recovery/momentum run: unanswered-input recovery with
old-subscription frame rejection, and background/foreground stream lifecycle.
Focused local evidence is in `captures/browser/recovery-checks.log` and
`recovery-unit-tests.xml`; runtime evidence is in `momentum-runtime-initial.log`.
The current phone download is build 157. Geometry/lens verification is
recorded above; download behavior and physical Pixel/Mac testing remain open.

## Browser download source follow-up (2026-09-28)

At the refreshed pin, `BrowserNavigationDelegate` converts download navigation
into a `WKDownload` and forwards it to `BrowserDownloadDelegate` in
`Sources/Panels/BrowserPanel.swift`. That delegate saves a temporary file on the
Mac, then moves it to the Mac Downloads directory or opens an `NSSavePanel` when
“ask where to save” is enabled. The inspected mobile browser interface has no
file-transfer or save-panel RPC. This establishes the host-managed source path;
it does **not** prove that downloading through Android has passed live acceptance.
Verify link-triggered downloads and the Mac save preference with a real paired Mac.
No Android-specific download limitation is asserted.

## Browser source audit and implementation (2026-09-28)

Compared the pinned iOS `BrowserStreamPane`, `BrowserStreamSurfaceState`,
`BrowserStreamKeyboardPolicy`, `BrowserStreamInputView`, shared browser payloads,
and `Sources/TerminalController+MobileBrowser.swift`. The streamed-browser action
interface and Mac handler do not contain a download-transfer method. Download
behavior remains an open investigation, not an Android platform limitation.

Implemented the bottom navigation/address/reload/keyboard bar, live history and
loading state, address-edit preservation, measured viewport updates without stream
restart, committed Unicode and native key/modifier RPCs, bounded ordered input with
explicit recovery, live coalesced drag scrolling, stale-frame rejection and image
header checks, plus dialog response identity fencing. The later momentum/recovery
follow-ups above record their implementation and runtime evidence. Four initial Android fixture cases passed for remote navigation/address editing and
viewport updates without restart, direct IME Unicode/keys/modifiers and manual hide,
frame rejection/acknowledgement and cleanup, and late dialog replies. A rendered
fixture screenshot was inspected at `captures/browser/browser-bottom-controls.png`.
The flat blue page in that image is an intentionally generated frame fixture. The last focused check passed 181 JVM cases; an earlier 180-case run had
one notification-feed peer-connection timeout, followed by clean 180- and 181-case
runs. Original failure evidence is retained locally. The browser IME Go label was compiled and checked in the combined integration run.

## Automated evidence (2026-09-28)

Offline task editing now retains the composer across connection changes, allows
saved drafts without a pairing, preserves unresolved groups until an authoritative
handshake, and keeps local folder suggestions available offline. Explicit offline
submission saves edits without creating an uncertain-operation snapshot or queuing
work. Drafts opened during the first handshake adopt the verified Mac identity.
This follows the pinned iOS `TaskComposerSheet` connection warning and editing
policy. The existing options sheet now remains open when switching Macs.

Verification for this feature: `:app:testDebugUnitTest` and
`:app:compileDebugAndroidTestKotlin` succeeded with 173 JVM tests, zero failures,
errors, or skips. Four Android cases now have passing runtime evidence for offline
entry/persistence/reconnect, unpaired draft resume, group inventory and local folder
selection after disconnect, and first-handshake adoption. The combined run also
passed the existing terminal IME and primary-navigation cases. Its initial result
was 9/11: the group assertion ran before the UI applied the changed connection
state, and a task-options tap raced the system IME animation. Waiting for the
observable state and invoking the dock’s accessible action produced a clean
2/2 focused rerun, including the production Mac-switch flow. That is 11 distinct
cases with passing evidence across runs, not a clean full-suite run. Original logs
and the focused rerun are retained in `captures/browser/`. This milestone first shipped in signed build 143; the current download is build 157. No physical Pixel is attached to adb.


240 JVM tests pass. The Android suite now contains 97 cases (including ten changes-viewer/preview checks, seven browser checks, three browser lens checks, four offline-task checks, four task attachment/layout checks, three terminal momentum gesture checks and three browser momentum gesture checks): twenty-three Compose
flows, seven task model/submission controls checks, four template checks, five task destination checks, five task draft checks, six completed-task recovery checks, one
explicit two-process draft check, two workspace drag checks, four Activity-recreation
checks, four notification/service checks and two
renderer checks. Earlier checks have passing evidence across suite and focused
runs. The All Computers workspace flow passes separately; nine navigation,
notification, lifecycle and terminal-entry cases passed for the preceding primary
navigation changes, including a focused rerun confirming Search keyboard visibility. They run on Android 17
(API 37) ARM64 emulator using the Pixel 6a display profile. The checks cover unread
filtering, terminal output appearing, keyboard-driven viewport reduction,
exactly one paste/submit request, and viewport cleanup on return to workspaces.
The composer flow also checks multiline preservation, separate terminal drafts,
encrypted persistence before sending, a disabled send action while an
acknowledgement is pending, retained text after rejection, explicit retry, and
insertion without submission. JVM checks cover delayed acknowledgements,
newer edits, Mac isolation, interrupted-send restoration, and sign-out cleanup.
The attachment flow checks the Android document-picker callback, 2048-pixel
image preparation, encrypted payloads and restored metadata, image acknowledgement,
file retention after rejected text, stable upload IDs on explicit retry, and cleanup.
The picker result is intercepted with local fixture files; cloud document
providers and a real Mac are not exercised. JVM checks also cover multi-chunk
byte fidelity, shell quoting, stopping on connection changes, attachment removal,
and resource budgets. The direct-input flow calls Android InputConnection APIs
through the actual native keyboard view and RPC client, checking uncommitted
composition, UTF-16/code-point deletion, control/navigation/function keys,
rejected delivery without queued replay, explicit resume, preserved composer
drafts, and rejection of a stale keyboard connection after switching terminals.
The on-screen keyboard and resized terminal were visually inspected. This does
not prove live vim/htop behavior, physical keyboard layouts, or every Android IME.
The rendering checks compare actual pixels from mixed Unicode spans against
separate cell placements, exercise device ICU cluster boundaries and widths,
and verify invisible glyphs, indexed backgrounds, and a wide underline cursor.
JVM cases cover alternate-screen/full-frame fences, stale revisions, carried
burst scrollback, resize history invalidation, semantic theme colors and
accessible column gaps. The rendering bitmap was visually reviewed.
The raw-stream flow checks split UTF-8 and ANSI bytes, duplicate suppression,
alternate-screen restoration, forced-gap recovery, scrollback/Latest without a
viewport resize, and suppression of parser-generated input. JVM checks add
transport selection, hybrid transitions, malformed/overflowing sequences,
bounded pending output, grid-to-VT seeding and actual captured Vim output.
The touch/copy flows check an immutable local copy sheet, exact Copy All text,
Android selection handles and selected-text copying, menu/long-press entry,
suppression of remote scroll on a screen-anchored primary screen, alternate-screen
wheel direction, cell coordinates and workspace/surface/client scoping, and a
viewport-anchored host's returned scroll frame. JVM cases cover shared hit-test
geometry, slow-response coalescing, prefetch cadence, cancellation, uncertain
scroll delivery, copy history boundaries and trimming blank screen tails.
Search/navigation checks cover independent tab queries, submit/cancel semantics,
bounded scalars without broken surrogate pairs, accent/width folding for
notifications, group/computer/description/notification metadata matches, and
returning from a search result with the committed filter intact. Moved-terminal
notifications resolve to the live owner only with the host provenance flag;
unresolved or ambiguous workspace destinations do not mark a notification read. Decoder cases
cover nullable metadata, duplicate IDs and redundant row content. The emulator
exercises moved-surface RPC scoping and an unavailable destination, and captures
notification search and its error state. Additional alert checks use two local
fixture Macs for exact saved-pairing and terminal navigation, cancellation of a
superseded initial feed handshake, repeat opening and
forgotten-Mac rejection. Delivery tests exercise real Android notification and
PendingIntent identities, Keystore-backed destination restoration, per-Mac read
cleanup, sibling cmux installation identity, foreground-service lifecycle and
stale-feed suppression. The production feed worker's invalidation and disconnect
path runs against a framed TCP peer. The combined foreground feed independently follows every saved Mac while the UI
is started, retains the latest snapshot during a disconnect, and closes its feed
sessions when the UI stops. It merges at most 2,000 items, sorts deterministically,
and uses a 300-item row window with load more. Projection tests cover local day
boundaries, daylight saving, contiguous same-pane groups within two hours,
filter-before-grouping and expansion/anchor retention. Coordinator tests cover
read/unread revision fences, offline bulk-action failures and replacement Mac
identity. Emulator flows cover expandable history, the unread filter, long-press
and swipe actions, bulk confirmation/cancel, identical IDs from two Macs, computer
search, exact terminal navigation and bulk actions despite a narrower search.
Computer filtering and live-destination visibility are applied before the global
cap, preserving older valid rows. Source snapshots remain intact, including
hidden notifications. Bulk read targets the captured computer scope and includes
its retained rows hidden by search; an unknown/forgotten captured scope cannot
widen to all computers. Selection persists in the encrypted account store, is
restored on Activity recreation, and falls back to All Computers after removal.
All Computers now aggregates saved-Mac workspace/group snapshots. Workspace and
group keys and local group expansion include the owning pairing origin; search
includes the owning Mac and uses the same scope as the computer picker. Each
workspace action routes to that exact background session, re-resolves the latest
window/group scope, and refreshes the owning list after success or rejection.
Unavailable or forgotten owners never fall back to the foreground Mac. Workspace
snapshots advance independently of notification revision fences. Opening a row,
its changes or an exact browser/created terminal promotes the owning foreground
connection and revalidates the route and fresh destination before navigation;
workspace navigation never marks a notification read.

Workspace unread counts preserve the host's optional 64-bit count. Expanded
headers show the anchor's count; collapsed headers aggregate all members, and an
unknown contributor keeps the aggregate unknown. Unread states with unknown or
zero counts display the iOS minimum badge of 1. Read rows retain their gutter so
icons do not jump. TalkBack receives the state/count on the row; decorative
badges and glyphs are excluded from duplicate announcements. Overflowing remote
aggregates remain unknown rather than wrapping into a negative count.

The focused Android 17 unread flow passes with actual Compose controls and the
framed loopback RPC client: expanded/collapsed counts, legacy unknown counts,
read/unread refresh and search flattening. It passed together with three existing
hierarchy/drag regressions (four tests total, 80.235 seconds). Expanded/collapsed
screenshots were visually inspected. The same badge flow also passed at 150% system text size (62.47 seconds),
with expanded and collapsed screenshots reviewed. Other accessibility sizes and
physical Pixel behavior remain unverified.

Group headers use a folder or a common custom-symbol equivalent, a separate
collapse chevron, and a pin next to the name. Lucide sources, hashes, ISC/MIT
notices and the conversion script are committed. These are Android vector
shapes, not Apple's SF Symbol glyphs: unknown symbol names currently fall back
to a folder. Complete custom-symbol fidelity remains open. The existing cmux
brand logo remains the upstream asset.

Workspace hierarchy now preserves spatial host order, represents anchors only
through group headers, indents children, aggregates unread visibility on collapse,
and retains empty headers in their pin tier. Collapse overrides are persisted per
Mac/group. Long-press reordering and edge scrolling use explicit inside/outside
group slots, with accessible Move up/Move down actions. Only an unfiltered,
connected, single-window/single-computer list can reorder. Up to three predictions
may be pending; writes are serialized and scoped to the verified owner/window.
Rejection, owner removal, external order changes, and changed pin tiers invalidate
dependent predictions. Live titles, previews, created rows and deleted rows remain
current while order is predicted. A fresh move can proceed after a rejected chain.

The pinned unmodified Swift algorithms generate 46 reference snapshots, 2,434
rendered drops, and 20,360 direct proposals. JVM tests compare hierarchy, canonical
RPC intents, and predicted ordering against those reference results, including
empty/promoted groups, collapsed groups, noncontiguous membership, pins and
invalid targets. Two empty-group defects were found and corrected by this
comparison. Loopback RPC tests exercise delayed acknowledgements, intermediate
snapshots, the three-move bound, rejection, external pins, forgetting and stale
window/owner guards. On the Android 17 Pixel-profile emulator, the full hierarchy flow opens the
anchor, preserves collapse across navigation, drags a row through the actual RPC
client, verifies the new order and disables reordering in search. A held gesture
moves beyond recycled source rows using edge scrolling; an accessibility check
exercises Move down and its disabled state. The first runtime pass exposed a
conflict with LazyColumn touch scrolling; disabling touch scrolling while a row is
held fixes cancellation while retaining programmatic edge scrolling. All three
new checks pass in focused runs. Filtering/terminal keyboard entry, scoped search
and multi-computer workspace mutation/navigation also pass for this batch.
`workspace-hierarchy-reordered.png` was visually reviewed. Initial interrupted
or failed diagnostic runs are not passes. Physical Pixel testing remains pending.
Connection cleanup captures the specific composed client; route effects capture
the same session as their keys and commit keyboard/navigation changes on the
Android main thread after revalidating the route.
Legacy alert identities without device/installation binding are retired on
upgrade; saved accounts, pairings and terminal drafts are retained.
The pinned iOS implementation retains feed snapshots in memory and clears them
with account state; it does not persist the feed to disk. Android follows that
model: a ViewModel retains snapshots and expansion across Activity recreation,
while saved tab/query/filter and loaded-row-window state is restored and a new process fetches fresh
content. Opaque Android alert routes remain encrypted on disk independently.
System-shade taps through actual process death, boot/sleep reliability, server
push fallback and physical-device navigation remain unverified or incomplete.
Screenshots were inspected with and without the keyboard. This caught and
fixed terminal background overdraw, skipped render updates, and a false
cancellation error during resize. It uses a local RPC fixture; live Mac auth,
pairing, real terminal applications, and the physical Pixel remain unverified.
See [ANDROID_TESTING.md](ANDROID_TESTING.md) for reproduction.

## Navigation presentation

The primary scaffold follows the pinned iOS 26 structure: Workspaces and
Notifications in a rounded tab group, a separate round Search control, a
floating New Task action on Workspaces, and Settings through the cmux logo.
The selected tab and unread badge have explicit Android accessibility semantics.
Search temporarily replaces the tab group with an editor and cancel button;
submit/result navigation commits its scope, while cancel/app Back clears it.
Committed filters survive tab/terminal navigation and Activity recreation.
The Android implementation uses Compose-drawn translucent surfaces and native
Android focus/IME handling. Apple's system glass material is not reproduced by
this implementation; final material, typography and physical-device visual QA
remain open.

## Work order

1. Make pairing usable and durable: encrypted credential storage, QR scanner,
   state restoration, and connection recovery. The helper remains an interim
   transport while the native cmux account and pairing path is validated.
2. Port the official pairing/ticket/frame/RPC contracts and replace the helper
   with the Mac's mobile endpoint. Verify against a pinned cmux build.
3. Finish terminal rendering and the full input contract, including remaining
   Ghostty fidelity, kinetic scrolling, live mouse reporting and inline graphics. Use vim, htop, Claude Code, and Codex as acceptance cases.
4. Complete workspaces, notifications, browser, search, changes, tasks,
   settings, accessibility, and background behavior.
5. Run the parity checklist on the Pixel and ship a stable signed APK.

The current Android build is not a parity release. Keep this table honest as
implementation and physical-device verification progress.

## Task model and effort contract

Task creation probes `mobile.task.models.list` for the selected provider and
fetches the cmux `/api/agent-models` catalog concurrently. A cold catalog can
populate the picker while Mac discovery is pending; nonempty discovered Mac
results (including default-only metadata) take priority. Cache identity includes
the exact pairing origin and provider, and account clearing or forgetting a Mac
fences stale refreshes. Transient failures preserve previously usable data.
Discovery retries while the composer is open with the iOS 500 ms–15 s capped
backoff; cancellation, provider unavailability, unsupported/disabled RPCs, authorization
failures, account mismatch and invalid requests stop it. Structured RPC error
codes are preserved without closing an otherwise usable connection.

The model menu captures its presented choices so a delayed Mac catalog cannot
replace an option while the user taps it. A chosen model survives delisting.
Efforts come only from the selected model or the Mac's Default metadata; a
Default model choice adds no explicit model argument. Claude `--effort`, Codex
`-c model_reasoning_effort=`, and OpenCode `--variant` values are quoted with the
upstream provider algorithm. Prompts remain in `CMUX_TASK_PROMPT`.

The command port is compared with 695 results from the pinned unmodified Swift
provider implementation, including repeated/existing flags, quoted arguments,
end-of-options, command boundaries, redirects, Unicode and embedded apostrophes.
JVM checks cover discovery completion order, default-only host results, stale
refreshes, forgetting/sign-out, owner cancellation, timeouts, and selected model/
effort reconciliation. This does not verify the installed agent CLIs on the Mac.
Task attachment flows, full composer layout parity and agent chat remain open.
Editable templates, saved drafts and completed-operation recovery are described below.

The Android 17 Pixel-profile emulator passes all five task controls flows and the
existing primary navigation/search/composer-entry flow together (six tests,
175.423 seconds). The five task flows use the production Compose/RPC path with a
synthetic Mac and injected catalog. They verify explicit model/effort submission,
Default, plain Shell, older-host fallback, and prompt/operation-ID retention
after a timeout followed by explicit retry. The final screenshots were inspected.
This remains separate from actual
Mac agent launch, task recovery and physical Pixel acceptance.


## Task submission identity and response handling

Task submission now compares the effective `workspace.create` parameters and
exact Mac pairing origin. Prompt or directory whitespace that produces the same
request keeps the retry ID. An effective command/environment/destination change
gets a distinct ID; reverting an unsent edit returns to the submitted baseline.
This follows `MobileTaskSubmissionIdentity` and `MobileTaskSubmissionSnapshot`
at the pinned upstream revision. Caller JSON mutations cannot alter the baseline.

The task composer requires a nonempty `created_workspace_id` identifying a unique
workspace in the returned list. Incomplete or malformed responses keep the prompt
and retry identity visible. A valid partial creation response updates existing
rows and appends new workspaces without dropping unrelated rows. Group metadata
is retained until a full list refresh, matching the iOS merge path. Navigation selects
the returned workspace and terminal, clearing any prior browser selection.
Requests are fenced to their original client and Mac: a late response after
connection replacement cannot navigate or apply a workspace list to a new session.

The initial submission batch covered a mounted composer session. Durable drafts
and capability gating are described below. Completed-operation reconciliation is
covered in the recovery section; physical-Mac agent launch remains required.


## Saved task drafts

The task composer owns a durable draft identity independent of its view or RPC
connection. Its Drafts sheet lists other drafts newest first, supports resuming,
deleting and starting a new draft, and saves the active draft before switching.
Leaving edited content offers Save Draft, Delete Draft and Keep Editing. Empty
selection-only drafts are omitted; a submitted untitled Shell keeps its retry
record. The collection retains the newest 20 nonempty drafts, matching iOS.

Prompt and directory text, template identity/name/raw command, explicit model metadata,
Default-model effort metadata and the last composed request are encrypted with
the Android Keystore account store. Autosaves are ordered and conflated, background
and disposal request a flush, and task creation waits for a durable request/ID
write before any RPC. Restoring equivalent content reuses the persisted ID;
changing effective parameters resolves a different operation. Successful creation
removes only its own draft. A failed local save prevents transmission.

Account login incarnations and per-editor leases fence stale writers. Sign-out
removes task drafts, and a delayed old token refresh cannot overwrite credentials
or clear a newer account's collection. Delayed model reconciliation is also fenced
to its originating provider. A resumed draft selects its saved computer and waits
for that exact pairing connection before mounting the composer. Task creation
requires the host's `workspace.task_create.v1` capability.

Remaining task work includes attachments, offline composer editing, draft rebinding after a pairing code changes, full
composer styling and agent chat. Saved drafts remain bound to the saved pairing;
they are not automatically redirected to a different or renewed pairing. Physical
Pixel/Mac verification remains required.


## Completed-operation task recovery

A normalized `already_completed` creation error preserves an immutable copy of
the accepted request and immediately retires its normal retry ID. The anchor and
fresh identity are stored with the encrypted draft. Equivalent requests cannot
use Create Task. Refresh Workspaces refreshes the full workspace/group listing,
then explicitly reconciles using the old operation ID and exact original request.
A successful response follows the existing validated workspace/terminal navigation
and removes only the recovered draft. No automatic retry is sent.

If reconciliation again returns `already_completed`, Refresh Again remains
available and Start Again opens the same duplicate-task confirmation as iOS.
Only confirmation submits the new operation ID. A list-refresh error, disk-save
error or unrelated RPC error cannot authorize that action. Replacing the client
or editor prevents a late result from installing recovery or navigating.

Late discovery preserves the submitted model/effort snapshot during recovery.
Effective edits detach the recovery controls; reverting to equivalent content
restores them. A genuinely different submitted request clears the old anchor.
Cold restoration preserves the anchor and retired identity and requires refresh
again, matching iOS's restored recovery phase. Older encrypted drafts remain
readable. Persistence and RPC checks use the same account/editor/origin guards
as normal task submission. Physical Pixel/Mac verification is still required.


## Editable task templates and successful defaults

The Agent menu lists editable shipped Claude, Codex, OpenCode and Shell templates
and custom templates, with Edit Agents opening the list and add/edit form. Names,
icons, raw commands and default directories are editable. Built-in identities
remain protected from deletion even after renaming or changing their commands;
custom entries can be removed. Provider/model discovery follows the actual command.
A blank command opens a plain shell. Nonblank scripts retain their exact source,
and the task prompt is passed through `CMUX_TASK_PROMPT`.

The three upstream agent images are copied unchanged from the pinned iOS source;
paths and SHA-256 hashes are recorded in `TASK_TEMPLATE_ASSETS.json`. Ten symbol
choices use the app's attributed Lucide equivalents. Custom emoji retain one ICU
grapheme. These symbol shapes are Android equivalents rather than Apple glyphs.
The editor shares the main window's Android keyboard and system-bar insets.

Template edits commit to the encrypted account store before updating the UI.
Errors retain the form; account changes fence stale writes. Unsaved editor fields
survive Activity recreation. Successful task creation remembers the template,
exact paired Mac, and up to 20 recent directories per Mac. A new task selects
the last successful Mac only while that pairing is still saved. Directory defaults
prefer the template, then the selected/recent workspace (focused terminal first),
then the last successful directory, then `~`. Explicitly typed paths survive agent
changes. Saved drafts retain their own Mac, template and directory. Deleted-template
restoration falls back to an available template and clears the old submission anchor.

Task attachments, offline composer editing, full composer
visual parity, agent chat and physical Pixel/Mac acceptance remain separate work.


## Task destinations and workspace options

Task Options follows the pinned iOS name field and Machine/Directory/Workspace
group card order. Explicit names override prompt-derived titles; generated titles
retain 60 grapheme clusters rather than splitting emoji. Group selection waits
for authoritative inventory, retains missing selections until explicitly resolved,
and checks current membership again after the durable save and before sending.
The wire field is `group_id`, gated by `workspace.create_in_group.v1`.

Changing the Mac durably reassigns the draft before reconnecting. It preserves
the prompt, name and typed directory, clears the previous Mac's group, and retains
retry/recovery anchors with their original pairing origin. Identical group IDs
on two Macs do not carry selection across computers.

The folder picker uses `mobile.directory.list` in 50-entry pages and debounced
`mobile.directory.search`. It supports Home/Computer, parent navigation, recent
locations, unreadable-folder states, explicit retries and local suggestions when
remote search is unsupported. Responses require bounded, ordered, nonduplicate
pages; changed page inventories keep the earlier page and report an error. Stale
queries cannot replace newer results. Search coverage limitations remain visible.
Suggestion ranking matches 115 outputs generated from the pinned unmodified Swift
implementation, including source priority, recency, fuzzy matching and byte order.

These controls use Android full-screen navigation and attributed vector symbols.
Full composer visual parity, task attachments, offline editing, agent chat, pairing
renewal and physical Pixel/Mac acceptance remain open.

The five destination checks and production-screen Mac-switch flow pass together
on the Android 17 emulator (six cases, 158.872 seconds). Options and folder screens
were visually inspected with system bars and the search keyboard visible. The
full 63-case instrumentation suite was not rerun for this batch.


## Terminal momentum

Android native spline decay, whole-row fractional carry and the upstream 450 ms
alternate/legacy momentum limit are implemented. New touches, explicit input,
Latest, View as Text, geometry/surface/mode replacement and disposal cancel motion.
Confirmed local-primary history stops at its bounds. Typing drops queued scrolls;
late scroll-response grids cannot replace the display after explicit input.

The initial implementation passed 163 JVM tests and compiled both debug APKs;
the integrated batch now passes 172 JVM tests. Four added
JVM checks cover carry/reversal, animated delivery and its deadline, cancellation/
history bounds and dropping queued motion. Three Android gesture checks now pass in the integration batch described below.
Build 138 includes this change. Pixel/Mac verification and pixel-precise rendering
remain open.


## Task attachments

The pinned iOS task-attachment contract is now implemented: 10 attachments,
8 MiB prepared images, 32 MiB files (including empty files), and 64 MiB total.
Photos, document selection, clipboard file URIs, previews and removal feed the
account-scoped encrypted task draft. Staged payloads survive repository reload;
deleting the draft or signing out removes its files. File viewing uses a narrow,
non-exported FileProvider and a temporary cache export created only on Open.

Uploads use 3 MiB chunks, stable upload IDs and the task submission's operation ID.
The created workspace receives CMUX_TASK_ATTACHMENTS and the upstream prompt
suffix. Attachment identity stays in the local retry snapshot and is stripped
from the workspace.create wire request. Accepted-operation reconciliation skips
uploads; an explicit new task gets a new operation ID. Shell tasks retain but do
not upload their draft attachments. Capability and session guards gate delivery.

34 focused JVM checks pass (9 attachment contracts, 9 submission identity checks,
12 task draft checks and 4 terminal attachment regressions). Four Android checks
now pass for picker/import/retry, encrypted restoration/sign-out, preview/external-
viewer cleanup/removal, and keyboard-dock layout. Build 138 includes this feature. Rich IME attachment paste and real Mac/Pixel testing remain
open; clipboard content-URI import does not claim rich IME coverage.


## Task composer canvas and keyboard dock

The pinned `TaskComposerLayout.swift` now supplies the main Android composition:
a full-height borderless prompt; a centered name/directory title; back and draft
icons; and a compact dock with task options, attachment selection, horizontally
scrolling agent/model/effort pills and a circular submit action. Edge fades show
that further choices can be scrolled into view. Model loading/error states remain
visible; effort choices are hidden when the host supplies none. Task Options owns
folder browsing and selection, replacing the old directory form on the canvas.

The dock remains above the Android IME while the prompt area shrinks. Visual
surfaces remain 38 dp, with Android 48 dp activation targets and a matching scroll
viewport. Icons and accessibility descriptions identify each action; long choices
scroll without displacing the fixed options or submit controls. Drafts, recovery,
agent templates, model/effort submission and destination checks use the new UI.


### Combined integration evidence

All 172 JVM tests pass. The initial seven new runtime cases passed together
(`OK (7 tests)`, 63.230 seconds). The 38-case regression batch passed 36 cases;
two obsolete UI assertions expected a fixed New Task title and an empty disabled
effort picker. Both now assert the upstream behavior instead. The final focused
12-case batch passed together (`OK (12 tests)`, 132.686 seconds), including those
corrections, all seven model/effort cases, four attachment/layout cases and the
production navigation/search flow. Together these runs provide passing evidence
for 45 distinct Android cases; the full 70-case suite was not rerun.

Two diagnostic runs stalled inside Compose 1.9.1's repeated performScrollTo helper
on the compact dock and were explicitly stopped; they are excluded from passing
run totals. The test helper now performs one scroll action, advances the controlled
dispatcher, verifies visibility, and clicks the actual button. The final clean run
uses that helper. The dock's scroll viewport also matches its 48 dp touch targets.

Inspected emulator captures: `captures/task-composer/task-composer-keyboard.png`
and the production-flow `task-composer-canvas.png`. Physical Pixel/Mac acceptance,
rich IME attachments, offline task editing, agent chat, Iroh, full Ghostty/inline
image fidelity and other open rows above remain unverified or incomplete.


Attachment recovery also passes in two distinct instrumentation processes
(12465 → 12653; seed 18.928 s, verify 6.029 s). The checks preserve encrypted file
bytes/identity, custom template, directory/name/group, model/effort and completed
operation identity. Explicit reconciliation opens the recovered task using its
original operation ID and sends zero attachment uploads in the verification
process. This is fixture-Mac evidence, not physical Pixel/Mac acceptance.


## Signed integration build 138 (2026-09-28)

[GitHub run 36353416757](https://github.com/DocMorphic/cmux-app/actions/runs/36353416757)
succeeded at `86678870ba2a0837d5bc559b6a9da5e7b23c510a`. The signed APK includes
task attachments, the composer canvas/dock and terminal momentum. Package/version,
unchanged signing certificate, feature classes, absence of instrumentation fixtures,
license assets and agent-image pixel equality were verified. The release FileProvider
is non-exported and exposes only its task-preview cache directory with per-URI grants.

The APK served to the phone matches SHA-256
`93b43169fba7a6d8a4549a7127fa0d16461523068cee4e3bb8a6bb3ce55357df`
(9052151 bytes). The download was fetched back over HTTP and matched the
verified artifact. Detailed local receipt: `captures/releases/8667887-verification.json`.
The full app parity goal and physical Pixel/Mac acceptance remain open.


## Signed integration build 143 (2026-09-28)

[GitHub run 36356036737](https://github.com/DocMorphic/cmux-app/actions/runs/36356036737)
succeeded at `636b9ffc93937077f93a13b08cd3818a30f597f3`. It includes offline task
editing and the browser controls/input/viewport milestone. The package/version,
stable signing certificate, new browser/offline feature code, absence of test
fixtures, existing license assets, task FileProvider scope and agent-image pixels
were verified in the signed artifact.

Served SHA-256: `90e7c08b6247a8b513f8f85c92338ac065968d1023f95ef8fe3d04426c0b739f` (9070804 bytes).
The file was replaced atomically on the existing tailnet download server, then
fetched back over HTTP and matched against the verified artifact. The local receipt
is `captures/releases/636b9ff-verification.json`; runtime evidence and the inspected
fixture screenshot are in `captures/browser/`.

This is an integration preview. The full iOS parity goal and physical Pixel/Mac
acceptance are still open.

## Signed integration build 150 (2026-09-28)

[GitHub run 36358173423](https://github.com/DocMorphic/cmux-app/actions/runs/36358173423)
succeeded at `6aba8c2977f3b47516d03cc77671cfaa8f0bdd22`. This combined milestone
includes browser stream recovery/lifecycle, native momentum/cancellation, corrected
wheel direction, aspect-preserving width fit, local pinch/pan and native repeated
click counts. The feature code passed 201 JVM cases and a clean 13-case Android 17
browser integration run before packaging. The upstream reference was refreshed to
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

Verified the release package/version, unchanged signing certificate, recovery/
momentum/lens feature classes, instrumentation-fixture exclusion, license assets,
agent-image pixel equality and FileProvider scope. The existing tailnet APK server
was updated atomically and its HTTP response matched the verified artifact.

SHA-256: `a07857531b1a0d1eb7921a63746c36b4fe4c8780c0017c1100070b4c33586b23` (9103572 bytes).

Local receipt: `captures/releases/6aba8c2-verification.json`. Runtime logs, geometry
unit checks and the inspected generated-image screenshot are in `captures/browser/`.
This remains an integration preview; full iOS parity and physical Pixel/Mac
acceptance are not complete.


## Signed integration build 157 (2026-09-28) — current download

[GitHub run 36360218971](https://github.com/DocMorphic/cmux-app/actions/runs/36360218971)
succeeded at `d53abe9b05fc5912dc820738a725360096e7aa80`. This combined Changes
milestone includes the tree/pager/numbered diffs, continuation/copy/font controls,
revision-checked hidden-context expansion, Before/After image/PDF/media previews,
and file actions. The source passed 240 JVM cases, a clean ten-case Android 17
Changes run, and a focused two-case rendered-image/MIME rerun. The upstream remote
HEAD was rechecked and still matches `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

Verified package/version, the unchanged signer, Changes and earlier feature classes,
instrumentation-fixture exclusion, licenses, agent asset pixels and FileProvider
scope. The tailnet server was updated atomically; its full HTTP response matched
the signed artifact. Local receipt: `captures/releases/d53abe9-verification.json`.

SHA-256: `a847a2f9465cac4ac62883b6401217fac4624bd1f3b85223fbbfb127b1cd1e33` (9218260 bytes).

The current download is build 157. Evidence and inspected fixture screenshots are
in `captures/changes/`. This is an integration preview: advanced artifact/text
viewing, terminal Files gallery, remaining terminal/transport/settings/notification parity,
and physical Pixel/Mac acceptance remain open.
