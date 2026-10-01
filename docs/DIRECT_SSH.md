# Direct SSH parity and implementation plan

## Status and evidence boundary

**SSH computers can now be saved and connected from the Android UI.** Computers
and Settings expose the host list/editor, and Settings exposes SSH key management.
Plain SSH shells now open from the saved-host list with the Ghostty terminal.
The Workspaces entry now combines existing cmux-tui owners, tmux sessions and
plain shells in one screen, with persistent terminal navigation and actions. Production host storage,
private-key storage and the low-level transport are implemented as described in
the checkpoints below. Existing native Mac pairing, terminal
rendering, Files and browser tunnel tests do not prove SSH/SFTP support. This audit
establishes the required behavior at upstream candidate
`204a11dfcc76280205e50406ab94270a1c152155`; it does not advance the broad implemented
reference or establish which App Store/TestFlight binary contains these features.
Older checkpoints below retain their original evidence boundaries; the SSH
Files presentation checkpoint is the latest implementation status.

## SSH Files presentation (2026-10-02)

Audited `SSHDirectoryView.swift` at the same `204a11df` candidate. The Android
sheet now has a centered folder title, an Add menu containing both upload routes
and New Folder, file/folder/link icons, modification dates, directory chevrons,
selectable monospace paths, long-press actions and pull to refresh. Filename/title
ellipses preserve both ends; transfers use a bottom progress bar. Explicit Refresh
and row action buttons remain accessible alongside the gestures.

Listings put folders first and compare numeric filename runs by magnitude,
without integer overflow, alongside locale-aware text collation. This covers
common natural filename ordering; Java collation is not a claim of identical
ordering to Apple's localized-standard comparator for every Unicode name.
Photo-picker uploads use `Photo-yyyyMMdd-HHmmss[-N].extension` in local time,
with the media MIME type selecting the extension. Documents retain provider
basenames. The actual photo-picker test now requires the generated PNG name as
well as verifying exact bytes and the rendered preview.

Nine JVM cases passed (five existing path cases and four presentation cases), and
both debug APKs built. The tests cover folders/numeric order, large numbers,
leading zeros, German/Swedish collation and timestamp/time-zone/batch suffixes.
Native ELF and both APK ZIP 16 KiB alignment checks passed.

All **eight** real SFTP/browser/system-picker cases passed in **108.982 seconds**
on API 37 / 16 KiB, with no skipped tests. The final photo capture shows the green
fixture image and its generated `Photo-20261002-011454.png` name. Evidence is in
ignored `captures/runtime/ssh-audit/files-presentation-*`: build and alignment
logs, Android device receipt, instrumentation/logcat and inspected screenshots.
Debug APK SHA-256:
`ec8e2f163a7dfeb842c92ecaad2ed12d3e9c4a551016c5de08fc11f4f74da75c`;
test APK: `e8c0f8a7bb113e8423520db2f90a81827d3f71fab0c3f9a05ce59938a6a07c01`.
After building, only whitespace indentation and documentation were adjusted.

The browser screenshot confirms the new file icon, modification date, grouped
row, Add button and middle-truncated title. This fixture activity has its own
system-bar styling; it is not physical-device or full iOS visual acceptance.
Swipe-to-delete, multiple-photo/video and cloud-provider checks, process death,
export and physical acceptance remain open. No signed release or parity pin changed.

## Real Android document and photo pickers (2026-10-02)

The real document-picker test exposed a stale callback capture: the local
`::upload` function reference retained the initial null `directory`. Local Kotlin
function references compare equal despite different captured values, so the
launcher's updated callback retained that first capture. The selection returned
without an error but uploaded nothing. Both picker launchers now use lambdas that
update when the loaded folder changes. A rerun confirmed multiple document uploads
and duplicate-name handling reached the selected remote folder with exact bytes.

The tests create their own MediaStore documents and image, cancel and reopen the
actual system pickers, then verify uploads over real SFTP. They delete only the
media they created. Android 17's installed modular photo picker is
`com.google.android.photopicker` and uses a **Done** confirmation label; older
picker packages/Add labels remain supported by the test. These differences affect
test selectors, not the app's platform picker contract.

**Verification:** all **eight** SFTP/browser/system-picker cases passed in
**102.598 seconds** on API 37 / 16 KiB, with no skipped cases. The two new cases
prove cancelling/reopening both system pickers, multi-document selection, preserving
an existing destination on duplicate upload, exact transferred document/photo bytes
and a displayed image preview. The other six cases retain file actions, source
failure, cancellation/cleanup and account-retirement coverage. The selected-photo
and image-preview captures were inspected; the generated green image is visible.
APK ELF and ZIP 16 KiB alignment checks passed. No JVM logic changed in this fix.

Initial attempts are retained: the original eight-case run exposed the upload
callback bug and an obsolete photo-picker package selector; the diagnostic run
showed an empty folder without an error and the new picker package. After the app
fix the document case passed, while the photo test still expected Add. The installed
`PhotopickerGoogle@CE2A.260420.050` resources and rendered final selection bar confirm
Done. The test now accepts the platform's Done/Add label in the picker package.
The [AOSP selection bar](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/61808742c17acb107221fa5f0ce998532dbc0bed/photopicker/src/com/android/photopicker/features/selectionbar/SelectionBar.kt)
was also inspected while diagnosing this control. No picker result is mocked.

Ignored evidence is in `captures/runtime/ssh-audit/`: `pickers-android/`,
`pickers-diagnostic/`, `pickers-callback/`, `pickers-final-build.txt`,
`pickers-final-alignment.txt` and final `pickers-verified/`. Debug APK SHA-256:
`959e17ae494e5e233bf2900e447b524e8511331bb7f03ac810695e395568a14f`;
test APK: `e5b39ae4ea9366dc4a923cb5765edf465a959f3e1e972d84f780525d079a44a0`.
The emulator and fixture were stopped. No signed release or broad upstream pin
changed. Physical Pixel, multiple-photo/video selection, iOS-style generated photo
names, media/export, publication races/connection loss and final visual acceptance
remain open. Cloud-backed provider behavior and picker/process-death restoration
are not established by these local-media tests.

## SSH path insertion and transfer interruption (2026-10-02)

Files now inserts a shell-quoted absolute path, preserving spaces, quotes, glob
characters and shell metacharacters as one argument. This follows
`SSHFileBrowserSheet.actions` (`remotePathShellWord`) at audited candidate
`204a11d`. Copy Path still copies the raw path. Insertion remains a paste with no
Enter and leaves the unsent composer draft intact. Paths containing terminal
control characters remain unavailable for insertion.

Uploads with a known source size now require the streamed byte count to match
before publishing the destination name. A changed or truncated source therefore
cannot silently produce a successful partial upload. Unknown-size providers remain
supported. Read failures close the source and remove staging through the live
channel. Cancellation closes the operation's SFTP channel, leaving the shared SSH
transport usable. Remote cleanup remains best effort: cancellation or transport
loss can leave only the uniquely named `.cmux-upload-*` staging file. The operation
is never replayed; the test verifies the requested final name is not published when an upload is
cancelled while its data writes are held by the fixture. Cancellation racing with
publication remains an uncertain outcome requiring a fresh listing.

**Verification:** five focused JVM path/quoting cases passed. Six real SFTP
browser/transfer cases passed in **57.646 seconds**, and three shell UI cases in
**74.121 seconds**, on the same API 37 / 16 KiB APKs. The shell UI inserted a path
containing spaces, Unicode, glob characters and single quotes, retained the unsent
draft, then explicitly sent Enter and verified the exact quoted bytes in the
fixture's echo. The fixture never executes terminal input as host commands.
Transfer checks hold actual server reads/writes, cancel the client operation and
verify local cleanup, source closure, no final upload name and a usable transport.
Read failure and both smaller/larger known source-size mismatches leave no staged
or final files; unknown-size upload succeeds. Native preview text and shell output
captures were inspected. APK ELF and ZIP alignment checks passed.

The first Files run passed five cases but failed the preview's Espresso focus
check. A screenshot and window dump confirmed a Pixel Launcher ANR dialog covering
the activity. The emulator launcher was stopped, then the **same APKs** passed all
six cases. The failed log/capture remain in `transfer-files/` and
`transfer-live.png`; no app workaround or relaxed assertion was used.

Ignored evidence under `captures/runtime/ssh-audit/`: `transfer-build.txt`,
`transfer-path-tests.xml`, `transfer-alignment.txt`, `transfer-files-verified/`
and `transfer-shell/`. Debug APK SHA-256:
`5d762fc9ac15eaad7977f1ed671b60eec3ec66d3d0407aa834d7efb85b731ece`;
test APK: `98503c606ed0c7cb87acafde43c826ae397f8779909add4d8cab4d56db3d1314`.
The emulator and fixture were stopped after verification. No signed release or
upstream pin changed. System document/photo pickers, media/export, publication
races/connection loss, remaining SSH browser features and final physical/UI
acceptance remain open.

## Plain SSH working directory (2026-10-02)

Plain shells now observe OSC 7 reports in their own output, matching the iOS
`MobileSSHWorkingDirectoryReport` at audited candidate `204a11d`. Files opens at
the last reported absolute directory; shells without reports still start at remote
home. The observer sends no shell commands and passes the original output bytes
unchanged to Ghostty. Each shell owns its report state; ended, retired or replaced
shells return no directory, and a reconnect starts unknown.

Reports support BEL and ST endings across arbitrary chunks, percent-encoded
`file://` paths and literal `kitty-shell-cwd://` paths. Plus signs, query/fragment
characters and Unicode remain path characters. Invalid, cancelled, non-directory
and oversized OSCs cannot overwrite the last valid report. Retained OSC storage
is bounded to 4096 bytes. A missing remote folder falls back to home through the
existing SFTP resolver.

**Verification:** seven parser JVM cases and four existing file-path cases passed
with no failures/skips. Debug and instrumentation APKs built once and passed ELF
and ZIP 16 KiB alignment checks. All three real SSH shell UI tests passed in
**76.967 seconds** on API 37 / 16 KiB. The production Files action displayed
`/Shell files λ +%?#` and its fixture marker file after a split OSC report; the
saved screenshot was inspected. Closing Files preserves the draft and PTY.
Ended/reconnected shells forget the old directory, while ANSI, terminal queries,
alternate-screen restoration, input and resize checks still pass.

Ignored evidence: `captures/runtime/ssh-audit/cwd-build.txt`, `cwd-alignment.txt`,
`cwd-*Test.xml` and `cwd-android/`. Debug APK SHA-256:
`2ac7d8a5c8a42e80bfb1d926025b35c2d501d5e8f8b1a8fc6bb6a90e4a2a855c`;
test APK: `b684fc0c5a70c4fc550e2573a43b076d702d7fe445936ea8e745a7ec41a69010`.
No physical Pixel was connected. System pickers, transfer faults, remaining SSH
browser features and final visual/physical acceptance remain open. This does not
publish a signed APK or advance the broad parity pin.

## SSH Files browser (2026-10-02)

The SSH terminal Files action now opens an SFTP browser on that SSH computer.
It resolves remote home and the selected cmux-tui/tmux terminal's current directory,
with a parent trail rooted at home or `/`. The follow-up above adds plain-shell
reported directories, with home as the fallback. The browser
supports folder-first listings, refresh, symlink directory navigation, new folders,
rename, confirmed nonrecursive delete, copy/insert path, document/photo selection
for uploads, progress and file preview. Downloads reuse the shared image, PDF,
text/Markdown, media and export viewers. Small unknown UTF-8 files use text;
unsupported files remain downloadable for external open/share/save.

This uses SFTP directly, never native Mac file RPC or remote shell commands.
Each operation owns a cancellable channel on the existing account/host transport.
Closing the account prevents new operations and closes that transport. An explicit
Disconnect remains respected. Mutations do not retry after a lost response.
Remote names use POSIX rules and JSch's glob-taking methods receive escaped literal
paths; `mkdir` and `realpath` receive raw paths. Folder deletion never recurses,
and symlink deletion removes the link. Downloads stream to a private scratch file,
check announced size and modification time, enforce available disk space and remove
incomplete local copies. These metadata checks do not detect every same-size edit
within the server's timestamp resolution. Closing the sheet removes its downloads;
export actions use the shared viewer's separately owned copies.

Uploads choose a free suffix from a fresh listing and stream through a uniquely
named temporary file. On servers advertising `hardlink@openssh.com`, publishing
uses that extension to avoid replacing a concurrently created destination. Other
servers rename after checking that the destination is absent; SFTP v3/JSch does
not provide a portable atomic compare-and-swap for that fallback. Rename similarly
checks the destination first. Concurrent remote writers remain an acceptance
boundary. Partial remote-file removal is best effort: connection loss can leave
`.cmux-upload-<UUID>` for later inspection. Operations have a ten-minute deadline;
that is not a resumable-transfer implementation.

Source scope correction: the reviewed iOS SSH provider/UI exposes create/end
workspace, new screen/window and tab/split creation. No SSH workspace/pane/tab
rename or move action was found in `MobileSSH*`, `MobileShellComposite+SSH*` or
`SSHWorkspaceListPanel` at the reviewed candidate. Those protocol commands are not
an additional iOS SSH UI parity requirement. Native Mac workspace actions retain
their existing requirements. The real SSH gaps are Files/browser, lifecycle/cache,
server capability gaps and final visual/device acceptance.

Sources at `204a11d`: `SSHFiles/SSHFileBrowserModel.swift`, `SSHDirectoryView.swift`,
`SSHFilePreviewView.swift`, `WorkspaceDetailView+SSH.swift`,
`CmuxTUIControl+ProcessInfo.swift`. JSch path and publication semantics were checked
against its [2.28.0 ChannelSftp source](https://github.com/mwiede/jsch/blob/jsch-2.28.0/src/main/java/com/jcraft/jsch/ChannelSftp.java).

**Verification:** four focused JVM path/input checks passed without skips. The
final debug/test APKs passed three real SFTP/browser checks in **65.803 seconds**
and three shell UI checks in **62.849 seconds**, on API 37 / 16 KiB. The shell check
opens Files through the production toolbar, closes it and verifies the same PTY
and unsent composer draft remain. Files stays in a sheet over the terminal. Other
checks cover exact transfer bytes, suffix collisions, Unicode/glob/backslash names,
rename collision refusal, nonempty-folder refusal, symlink deletion preserving the
target, folder creation/rename/delete/cancel dialogs, native displayed Unicode
preview text and refusal to reopen a retired account. APK ELF and ZIP alignment
checks passed. Long folder headings were constrained after screenshot inspection;
final browser/preview captures were inspected. Path insertion rejects control
characters and requests bracketed paste without adding Enter; the insertion
follow-up above adds the required shell quoting.

The first attempt failed on an ambiguous test selector (Rename title/button) and
fixture symlink setup (JSch's OpenSSH-style argument order versus AsyncSSH's
standard decoder). The selector now chooses the clickable button; a fixed,
path-free fixture command creates only the private test link. These failures and
intermediate passing receipts are retained, rather than counted as final passes.

Ignored evidence is in `captures/runtime/ssh-audit/`: `files-path-tests.xml`,
`files-verified-build.txt`, `files-verified-alignment.txt`,
`files-verified-android/` and `files-verified-shell/`. Final debug APK SHA-256:
`f5b8343761525f0f90a213ad5bacd4e49ed07029fe69c249485b7f4b518c5de7`; test APK:
`ca41721c32bae34ea5ec6e6d56579840031b5ace7955c9816cc7608c9de04662`.
The emulator and private SFTP fixture were stopped afterward.
Physical Pixel, system document/photo pickers, large media, cancellation/fault
edges and iOS visual acceptance remain open. Plain-shell current-directory
reporting is covered by the follow-up above. No signed release or upstream pin changed.

## Owner recovery, layout creation and idle policy (2026-10-01)

Opening a durable cmux-tui selection now resolves its provider independently of
listing. A missing/ended `cmux-android` owner can be started with the existing
binary, then the saved terminal is resolved by registry/workspace/tab/terminal
identity. This path never installs a binary or creates a replacement terminal.
Other session names require an existing matching socket; the phone does not
start a missing desktop owner. Startup, provider publication and discovery are
serialized per host. Discovery itself remains read-only.

The workspace screen now uses `new-screen` for **New Screen**, matching iOS's
nested-terminal action. Each pane exposes **New Tab**, **Split Right** and
**Split Down**. Each successful action selects the returned terminal, rather than
requiring a second tap. Provider actions re-list the current owner, resolve the
stable workspace and verify pane identity/membership before sending a numeric
layout command. They re-list afterward and capture the created terminal's durable
identity. A moved/replaced pane is refused before submission. These legacy layout
commands do not support the durable workspace mutation envelope; no fake CAS fields
are sent, and an uncertain creation is not automatically replayed.

The host's saved idle-close setting is now supplied on creation and attachment,
using `terminal-idle-close-v1` capability gating. Unsupported servers receive no
policy command. An explicit policy rejection does not replay creation or block
an otherwise live terminal. A disconnected control still retires normally. This
matches iOS's best-effort setting behavior and **does not add idle closure to
0.13.4**, which lacks that capability; the server-version gap remains open.

**JVM verification:** 57 focused checks passed without skips, including the real
cmux-tui/tmux process tests and installer process test. New checks cover current
idle settings (including Never), server policy rejection without duplicate
creation, correct returned-terminal selection, pane identity rejection and omission
of unsupported layout mutation guards. Debug/test APKs built and app ELF / both
APK ZIP 16 KiB checks passed.

**Android verification:** all six mixed-workspace checks passed on API 37 / 16 KiB
in 171.110 seconds using real SSH, private cmux-tui 0.13.4 and tmux processes.
The new layout check creates a screen, tab and both split directions, types into
each automatically selected terminal, verifies the resulting topology and keeps
the original terminal identity. The owner-recovery check stops the phone owner,
proves a separate discovery consumer does not restart it, then uses Reconnect to
recover the same registry and terminal through a new owner generation. History
and new input are checked through the visible terminal before reading the current
provider registry. Exactly one additional phone-owner ensure occurs. A stopped
desktop owner remains stopped and shows the explicit reconnect error.

An initial combined run timed out after three completed tests; its precise stall
cause was not established. The identical APKs passed all six checks on rerun.
This is retained as a test-run reliability follow-up, not counted as a pass. The
runner now preserves partial instrumentation output on timeout and force-stops
the emulator test before removing its reverse tunnel. An earlier recovery test
assertion inspected a retired setup host; it now inspects the current host only
after UI-driven history and input recovery succeed.

Receipts are under ignored `captures/runtime/cmux-tui/`: `layout-jvm/`,
`layout-build.txt`, `layout-alignment.txt`, `layout-android/` (timeout) and
`layout-android-retry/` (six passing results and inspected captures). Debug APK
SHA-256 is `e75c4e8562275c973e70cd98c7e9434d59bde04aa9b7182e973b177f0a147c3c`;
test APK is `a1adeb945b26a3414268f031d2037ddcdf5e9512a7f64602d76c8d9dc7d9838a`.
The emulator was stopped after verification. No physical-device test or signed
release was performed. Captures verify readable controls and recovered terminal
content; they do not establish final iOS visual parity or large-font acceptance.

Remaining work includes browser and
SFTP/media, compatible-server geometry/idle closure, complete iOS visual/interaction
parity, process-death/fault acceptance and physical Pixel verification. This
checkpoint does not change the signed release or upstream references.

## Installer and phone-owned session creation (2026-10-01)

The workspace screen now offers **New cmux Workspace** independently of running
owners. This explicit action probes the platform, locates a binary, installs one
only if absent and starts the product-scoped `cmux-android` owner. Existing desktop
owners still use their sockets without `server ensure`. Their workspace identities
remain unchanged when the phone creates or ends its own workspace. Discovery and
listing do not download, install or start an owner. Host creation and discovery
are serialized; account/transport retirement cancels their ownership. Navigation
away does not abort an already submitted creation or replay it later.

`SshCmuxInstall.kt` follows iOS's phone-download/SSH-upload approach, with an
application pin for **0.13.4** and SHA512 digests for Darwin/Linux arm64/x64 packages.
The official Darwin/arm64 `latest` metadata still reported 0.13.4 in this check;
this is not a newer compatible server release. URLs and digests are fixed per app
build. Downloads reject redirects, non-200 replies and archives over 64 MiB, check
cached files before reuse, verify SHA512 before any remote install operation and
remove partial downloads on failure/cancellation. The four package manifests were
read from the official npm registry; receipts are in `install-audit/` below.

Installation uses Android SFTP into a unique 0700 staging directory under the
remote `$HOME/.local/bin`, extracts only `package/bin/cmux-tui`, requires a regular
non-symlink executable and checks its `remote-probe` distribution version. An atomic
hard link within that filesystem publishes the executable only if the destination
is still absent. Existing executables and symlinks are never overwritten. Shell
paths are quoted and cleanup accepts only the generated staging identity. Upload,
verification and activation failures attempt bounded cleanup. A disconnected or
unreachable host can prevent that cleanup; this is not a guarantee that every
network-loss case leaves no staging files. No remote Node or internet is required.

**Verification:** 54 focused JVM checks passed without skips. The six installer
checks cover platform/URL selection, size/checksum rejection before remote writes,
partial-download cancellation, bounded cleanup paths and real published-archive
installation in a private HOME containing spaces/quotes. That process check also
verifies cache reuse, refusal to replace the installed file, unchanged binary hash
and cleanup after interrupted upload. Owned-startup checks reject a substituted
session identity. Debug/test APKs and the app ELF / APK ZIP 16 KiB gates passed.

On the retained API 37 / 16,384-byte-page emulator, the live installation test
passed in **18.359 seconds**: production HTTPS download from npm, pinned checksum,
Android SFTP, real Mac extraction/version check/activation, the installed executable
starting `cmux-android`, terminal input, visibility of the existing desktop workspace,
a second creation without another installation, and zero retained staging folders.
The test's cache and generated key were removed. It uses private generated SSH
credentials and does not touch the user's Mac installation. This run proves
Darwin/arm64 installation; Linux/x64, Linux/arm64, Darwin/x64 and the physical Pixel
still need their own acceptance evidence.

The four mixed-workspace Android checks then passed in **99.099 seconds** on the
same app APK, with the test-only synchronization fix. The new fourth check creates,
opens, reopens and ends a phone-owned workspace while retaining the desktop
workspace's registry/key. The original failure attempted to read View as Text
before the asynchronously reopened screen existed; the corrected test waits for
that screen and handles an initially empty replay. The other checks retain mixed
navigation, input, drop recovery and saved Disconnect restoration. The resulting
owner/workspace capture was inspected. All private fixture roots were removed
after confirmed terminal/owner cleanup, and the single emulator was stopped.

- App SHA256: `f93e8d2510329b5bcd7a01c211b23972c8a26ab53367472bb57c5c1366423c45`.
- Installation test APK SHA256: `b021939786034fa4fc67c8a04f99bdcc66826774a647c21274aa234a2e7d5137`.
- The subsequent workspace-test synchronization correction changes only the test
  APK, to `c6ec33241415cecd885eb00884702fddbf0bf3f1ccafd161af756dce5b4df680`.
- Ignored receipts: `captures/runtime/cmux-tui/install-audit/`, including `jvm/`,
  `owned-build.txt`, `transport-build.txt`, `final-test-build.txt`, `alignment.txt`,
  `install-android/` and `final-owned-android/`.

[Reproduction](../ssh-spike/README.md#android-cmux-tui-installation-check) records
both installer modes. The install screenshot captures creation progress; it is not
final iOS styling acceptance. The server's geometry/idle-close gaps are unchanged.
Restoring a selected phone-owned session after its owner stops still needs an
ensure-on-open path (listing must remain read-only); automatic upgrades and a
compatible server release remain open. Other outstanding work includes idle-policy
integration, remaining cmux topology actions, SSH Files/browser/media, full OS
process death, final visual parity and physical acceptance. Signed 284, the Pixel
installation and the upstream references are unchanged.

## Mixed SSH workspace navigation (2026-10-01)

The Computers host's Workspaces action now enters a shared route listing
cmux-tui workspaces, tmux sessions and plain shells in that order, matching the
reviewed iOS provider ordering. cmux-tui rows preserve screen/pane/tab hierarchy;
existing-owner creation, new terminal and confirmed End Workspace invoke the
production provider. tmux retains its pane, new-window, split and end actions.
Shells can be created, reopened and closed from the same screen. Browser and
unknown content stay visible as unavailable rather than being opened as a PTY.
The list is a working integration; final iOS visual/interaction parity is still
outstanding.

One connection admission supplies both provider families. An automatic reconnect
does not clear the user's persisted Disconnect choice. Saved selection stores
only typed navigation identities, scoped to the saved host and the account login
incarnation. cmux-tui restoration resolves durable resources; tmux resolves its
exact workspace/window/pane; a missing plain shell is reported rather than
silently respawned. A canceled cmux view acquisition retires its exact eventual
renderer after the detach fence, without releasing a newer acquisition under the
same key. Ending confirmation is discarded when its provider/connection changes.

**Verification:** 47 focused JVM checks passed without skips (including the real
cmux-tui/tmux process checks and two saved-target checks). Debug and test APKs built;
app ELF LOAD/RELRO and both APK ZIP 16 KiB alignment checks passed. On the sole
retained API 37 / 16,384-byte-page AVD, **three mixed workspace checks passed in
68.722 seconds** through real Android SSH, cmux-tui 0.13.4, tmux 3.7c, the production
Compose route and Ghostty JNI:

- Unicode replay/input, View as Text, reopening with history, new cmux terminal
  and workspace, cancel/confirm ending, then tmux and plain-shell navigation/input;
- transport drop followed by UI-driven automatic reattachment, history and fresh
  input; explicit Disconnect stays idle until Reconnect;
- saved selection restored using a fresh host store/vault/session runtime, with
  history intact and persisted Disconnect still respected across restoration.

These checks inspect the actual View as Text sheet, so they cannot accidentally
repair navigation by separately reacquiring a renderer. The mixed fixture's plain
shell is a cat pipe accepting window-change events, not an OS PTY resize test.
The first run exposed missing resize-event handling in that fixture; the fixture
was repaired and all three checks reran on the same APKs. Terminal and workspace
captures were inspected (cat's PTY echo includes visible control-sequence notation;
that is fixture output). The harness status bar is not a final-app styling check.
Both private server/state directories were removed after confirmed cleanup and
the emulator was stopped. [Reproduction](../ssh-spike/README.md#mixed-android-ssh-workspace-checks).

- App SHA256: `c9596ca644db2ce0d99223105160b9ed950e5a62ff36a302e2e1cc325c642235`.
- Test SHA256: `c8e086f65db6a979ee5649bc691a6a83846c38b2b587b37a4411e77d11c418b2`.
- Ignored evidence: `captures/runtime/cmux-tui/mixed-build.txt`,
  `mixed-alignment.txt`, `mixed-final-android/`, `mixed-fixture-final-log.txt`.

This establishes Android SSH integration with existing fixture owners. Installer
and phone-owned session creation, a compatible server release for geometry and
idle-close, remaining cmux topology actions, warm-cache policy, full OS process
death, SSH browser/SFTP/media, physical Pixel acceptance and final UI parity remain
open. Signed 284 and the physical Pixel installation are unchanged. The upstream
watch/release schedules documented in [UPDATES.md](UPDATES.md) still await the
application and workflow merge to main; this checkpoint does not activate them.

## cmux-tui provider and renderer (2026-10-01)

`NativeSshSession` now owns a `SshCmuxHosts` registry, with host providers bound to
the current account and SSH transport. Existing owners are discovered independently;
stale/unreachable sockets produce errors without hiding reachable sessions. No
server is installed or started by discovery. Each `SshCmuxProvider` subscribes,
coalesces topology refreshes and resubscribes/re-lists after overflow. Invalid
snapshots retain the last good tree and expose an error. Account/transport closure
retires controls and renderers.

Provider-owned actions create a durable workspace/terminal, add a terminal and end
a workspace. Creations carry unique mutation/terminal identities and generation
guards; canceled UI waiters cannot replay or abort an already submitted mutation.
Ending checks the confirmed content identities, resolves the exact resource
session without a first-session fallback, calls `terminal.close` for its distinct
terminals, rechecks for newly added content and only then removes the workspace.
This is a sequence of server operations, not an atomic transaction: failure may
leave a partially ended workspace, and uncertain operations are not auto-retried.
Multi-tab/pane split/move/rename actions still need integration and acceptance.

`SshCmuxTerminal` implements the shared terminal-screen contract with a silent
Ghostty mirror, fresh VT replacement for replay/resizing, server colors/cursor,
bounded ordered input and chunked large replay ingestion. Geometry updates are
conflated. The shared screen's STARTED lifecycle explicitly claims/releases view
geometry; a retained opening first releases its initial claim until a visible
screen adopts it. Reopening creates a distinct renderer after the prior detach
fence, so a late release/resize from an old screen cannot retire the replacement.
Renderer ownership is bounded to 16 retained terminals per owner. A cache policy
for the final mixed workspace UI is still outstanding.

The renderer exposed a binding restriction: cmux-tui permits a 1×1 canonical
grid, while Android's Ghostty wrapper/JNI/snapshot decoder required at least two
rows/columns. The pinned core itself rejects only zero dimensions. Commit
`bd67fce` aligns the binding with that contract. Native-only CI run
[36920005789](https://github.com/DocMorphic/cmux-app/actions/runs/36920005789)
passed; its 29 artifact files and current binding source hash were checked before
using the checkpoint locally. [Ghostty evidence](GHOSTTY_VT_ANDROID.md) records the
native runtime checks. No native verification gate was bypassed.

**Verification:** 45 focused JVM checks passed without skips. The real 0.13.4
process test now uses provider creation/end actions, confirms changed-content
refusal before any terminal is ended, then ends both original fixture terminals
and removes the workspace. Unit checks cover overflow, canceled UI waiters,
account-owner retirement, malformed refresh, ambiguous resource sessions and
color-escape filtering. Debug/test APKs built; app ELF alignment and both APK ZIP
alignment checks passed.

On the retained API 37 / 16,384-byte-page emulator, **three renderer component
checks passed in 38.298 seconds**. They use deterministic wire events through the
production control, JNI renderer and shared Compose screen: Unicode replay,
bracketed composer input, silent query handling, foreground/background/cursor
metadata, hidden-view release, 1×1 replacement, detached-input refusal and stale
view release after reacquisition. The
terminal capture was inspected. Separately, **ten Ghostty native runtime checks
passed**, including single-cell create/resize/expand. The emulator was stopped.

- App SHA256: `3ac64acfaf3a072dc85e38d364ff0010a7a39edb4e9b9f57474e7214ca7bb346`.
- Test SHA256: `baf6d65052edbed3d16e6476e3a5b3514495a5264de954fe31aa9701f7e17978`.
- Ignored evidence: `captures/runtime/cmux-tui/provider-jvm/`,
  `provider-final-build.txt`, `provider-final-alignment.txt`, `provider-final-android/`
  and `native-36920005789/`.

The mixed workspace navigation still needs to call these providers; this does
not claim that cmux-tui is exposed through the Computers UI yet. The Android
component fixture is not end-to-end cmux-tui over Android SSH. That workflow,
installer/owned-session creation, remaining workspace actions, browser/SFTP,
physical Pixel acceptance and the published-server geometry/idle-close gaps
remain open. Signed 284 and the physical Pixel installation are unchanged.

## cmux-tui discovery and durable inventory (2026-10-01)

`SshCmuxRemote` now adapts the production SSH transport to the existing-owner
discovery contract. It searches the phone install location, PATH and common
Homebrew/local paths, then lists named and hashed sockets in upstream runtime
precedence. It does not install a binary or start a server. Absolute paths and
session names are validated, arguments are shell-quoted, output uses strict UTF-8,
and listings are bounded. The first socket per session digest wins. A hashed
socket must match the SHA256 of the session returned by `identify`; a substituted
owner, timeout or canceled connection closes the opened relay. Binary absence is
distinguished from an execution error.

The typed inventory preserves ordered workspaces/screens/panes/tabs, empty
workspaces, terminal identities, browser metadata and split/stack layouts.
Unknown additive kinds/layouts remain representable without being treated as
terminals. Malformed identities, duplicate numeric/view IDs, invalid layout
references, excessive item counts or nesting reject the snapshot. Browser sizes
such as 1920×1080 remain metadata rather than being mistaken for an invalid
terminal grid.
Wire JSON nesting is also bounded before recursive JSON decoding, so the typed
layout limit cannot be bypassed by overflowing the decoder first. Quoted brackets
and escaped quotes in titles do not count as nesting.

`SshCmuxSelection` scopes restoration to a session and registry, stable workspace
key/resource and terminal identity. If a terminal has multiple tab views, a saved
tab resource selects its exact view; an ambiguous match is refused. Numeric-only
legacy selections work only within the same known owner generation. A replacement
registry, dead/replaced terminal or different session cannot inherit the selection
just because its title or numeric IDs match. The caller must additionally scope
these selections to its account and saved SSH host.

**Verification:** 40 focused JVM checks passed without skips: ten relay checks,
seven inventory/selection checks, five discovery/lifetime checks, one real
cmux-tui process and 17 tmux regression checks. The real 0.13.4 process check now
runs the production binary/socket discovery commands and `relay --socket`, parses
the actual workspace hierarchy, captures a durable selection, stops/restarts the
private owner, observes a changed generation, then resolves and reattaches the
same terminal with history intact. Its private terminal and owner state are
cleaned up after the run. Receipts are in ignored
`captures/runtime/cmux-tui/inventory-final-build.txt` and `inventory-jvm/`.

This adds the SSH adapter and inventory/restore policy; account-owned provider
publication, workspace UI, live topology refresh, renderer ownership, installer,
compatible server selection and physical Android acceptance remain outstanding.
No emulator or APK build was needed, and signed 284/Pixel installations remain
unchanged. The 0.13.4 geometry-restoration and idle-close gaps below remain open.

## cmux-tui relay foundation (2026-10-01)

`SshCmuxControl` now shares the account-owned raw SSH exec pipe with tmux.
It implements bounded newline/UTF-8 JSON framing, string request correlation,
ordered writes, request deadlines and retirement on malformed/overflowing input.
Canceled replies cannot answer another request; input and uncertain mutations
are not retried. Session identity, protocol 11 minimum and required capabilities
are checked before attachment. Numeric surface IDs and dimensions reject string,
fractional and overflowing values rather than aliasing another terminal.

Attachment registers before the request because the initial VT snapshot arrives
before its acknowledgment. The client handles output, replacement resize replay,
colors, exclusive geometry, view leases, release/reclaim and detach fences. A
server without lease/detach support is fenced by closing the relay. Idle-close
requests are capability-gated and use the upstream seconds/never contract.
Resource V2 requests preserve a caller's idempotency key and structured errors.
This adapts the candidate's `Packages/iOS/CmuxMobileSSH/.../CmuxTUI` contract;
it does not yet provide discovery, installation, typed inventory or workspace UI.

**Verification:** 28 focused JVM checks passed without skips: ten cmux-tui unit
checks, one real cmux-tui process and 17 tmux regression checks, including real
tmux 3.7c. The cmux-tui test creates an isolated HOME/runtime/config, subscribes,
creates a workspace and `/bin/cat` terminal, receives Unicode output, resizes,
releases/reclaims its lease, detaches and rejects late input. A new relay then
reattaches the same durable terminal resource with its prior history. Explicit
`terminal.close` ends the process, and the empty workspace is removed. Cleanup
closes fixture terminal hosts before stopping/resetting the owner: stopping only
the owner would leave durable terminal processes alive. Failed cleanup retains
its unique directory instead of deleting state out from under live processes.

The real binary was the official npm `cmux-tui-darwin-arm64` **0.13.4**, pinned by
the reviewed iOS provider. Its tarball SHA512 was checked before extraction:
`O0N+CffNanx9DvdAJc6J7FgqtEitY+E7FNEkvJfXs0QeAcvfAb5jhROqkdw4IsSGcJlwF8NNa/8ak4p3nSyGkw==`.
It reports control protocol 12 and build
`ec4cbd4fdd16526d5653d7a1f76496ef785f2a2a`. It was executed only from ignored
fixture storage, never installed in the user's PATH. The opt-in test recipe is
in [the SSH fixture guide](../ssh-spike/README.md#real-cmux-tui-relay-check).

**Observed server-version gap:** a separate two-client probe attached a desktop
view at 100×40, then a phone view at 80×24. The phone's release returned `applied`,
but the canonical tree still reported 80×24. Thus an acknowledgment does not prove
desktop geometry restoration on 0.13.4. This binary also omits
`terminal-idle-close-v1`. The client capability contract is tested, but selecting
and validating a server version with those behaviors remains required.

Ignored receipts: `captures/runtime/cmux-tui/final-unit-build.txt`,
`verified-jvm/`, `metadata.json` and `probe-receipt.json`. No emulator was started,
no APK was assembled/published and no Pixel app was replaced at this checkpoint.
This process test verifies the production relay codec against the server; Android
SSH transport integration, terminal rendering, mixed-provider UI, warm caches,
installer/version strategy and physical acceptance remain open.

## Plain-shell reconnect and grouped-session cleanup (2026-10-01)

An ended plain shell now offers Reconnect on its terminal screen. A failed dial
leaves the old renderer and registry slot intact and shows the error on that
screen. After connecting, the registry replaces that slot with a fresh shell;
an ended PTY is not resumable. Old input is not replayed. The action rechecks
account ownership and that the original shell was not removed during connection.
It shares the coordinator's explicit retry/trust behavior.

Collection of abandoned Android tmux groups now evaluates `session_attached`
inside tmux immediately before `kill-session`. Only exact generated Android group
names are admitted, and an attached group remains alive. The `if-shell` target
has a trailing colon to select the session context; an initial test caught that
omitting it gives an empty attachment-count format. This recheck is an Android
improvement: the reviewed iOS source lists unattached groups then removes them by
name. Prior notes claiming iOS already had the recheck have been corrected.

The real-process test also exposed a control-stream lifecycle issue: deleting
another grouped session can broadcast `%window-close` for windows our group still
owns. The client now coalesces an authoritative pane listing before retiring those
panes, rather than trusting the notification alone. Layout changes during that
listing request a follow-up. Tests cover both a surviving linked window and an
actual removal. The original failure and its private-fixture wire trace are kept
in ignored evidence; temporary wire logging was removed from the test source.

**Verification:** 26 focused JVM checks passed without skips (17 tmux, including
the real tmux 3.7c process, and nine connection-coordinator checks). Debug and test
APKs built. On the single API 37 / 16,384-byte-page emulator, **three shell Android
checks passed in 51.471 seconds** and **four real SSH/tmux checks passed in 50.850
seconds**, using identical APKs. Shell retry was exercised with a missing key:
the failed attempt kept the old screen, restoring the key enabled a fresh PTY,
the old marker was absent, remote active-shell count was one, and the old object
rejected input. The fresh-shell capture was inspected. tmux coverage retained
creation/split/end, account retirement, drop/manual recovery and saved-state
restoration against a fresh runtime.

- App SHA256: `66cf483105826dacbf06d7fce4fff61f613225d9643c4cf93808d3f4657aed37`.
- Test SHA256: `eb95b4461a79486b9213dea407d1dfc8ac1eb1f5c065deb0a3b5d4fbe6198511`.
- Ignored evidence under `captures/runtime/tmux-workspaces/`:
  `cleanup-jvm/`, `cleanup-shell-android/`, `cleanup-tmux-android/`,
  `cleanup-shell-verified-build.txt` and `cleanup-wire-failure.xml`.

The emulator and both loopback fixtures were stopped. Signed build 284 and the
Pixel installation are unchanged. The component/fixture screenshots do not prove
full Activity styling, arbitrary-TUI or physical-device acceptance. Full OS
process-death/foreground fault acceptance, stale restored-target UI, bounded warm
caches, mixed shell/tmux/cmux-tui navigation, SSH Files/browser/media and physical
acceptance remain open.

## Saved tmux selection and execution-time pane targets (2026-10-01)

The selected tmux pane now uses Android saved state containing the exact remote
server/session creation identity, window ID and pane ID. After restoration the
route relists and attaches only that matching target. A restored route uses
automatic connection policy; a fresh navigation or explicit retry may clear a
persisted Disconnect. Pending attachment has a Back action and visible failure/
retry feedback. Restoration does not replay terminal input.

Every control-mode capture, mode query and input command now names its grouped
session, original window and pane together. A global pane ID alone would follow
`join-pane` into another window/session even when the phone has not received the
layout change yet. Geometry updates and the warm terminal cache also retire a
pane that leaves its original window. Chunked input retains its attachment object
and cannot resume into a newer attachment after an acknowledgement arrives.

**Verification:** 25 focused JVM checks passed without skips. The private tmux
3.7c process test moves a pane after its input command is queued but before the
pipe writes it: the command fails and the destination capture has no input marker.
A stale attachment produces neither a snapshot nor output from that moved pane.
A deterministic control test confirms that remaining input chunks do not enter a
replacement attachment. The previous stale-split guard remains covered.

All **four real SSH/tmux Android checks passed in 58.451 seconds** on API 37 with
16,384-byte pages. The new test saves/restores the Compose hierarchy after closing
the old runtime, then creates fresh connection/provider/renderer objects and
reloads serialized host metadata and the fixture key vault. It verifies retained
remote output and a paused Disconnect across a second restoration, followed by
explicit retry. The final capture was inspected. This uses
`StateRestorationTester`; it is not an actual OS-killed full application process
or physical Pixel run. Fixture, visual and arbitrary-TUI limits from the prior
checkpoint still apply.

- App SHA256: `36c3e8f487947f16eb6f238583d8524353c91cc777b60c98e2b8d8f70d62b7ac`.
- Test SHA256: `d109ccb5e425831045d015c2fecf1d633f809441d00eb87019d8293561c01015`.
- Ignored evidence: `captures/runtime/tmux-workspaces/restore-android/`,
  `restore-jvm/`, `restore-build-final.txt` and `restore-fixture-trace.txt`.

The sole emulator and private server were stopped. Signed 284 and the Pixel
installation remain unchanged. Remaining work includes full-Activity/OS process
restoration, failed/stale restored-target acceptance, foreground fault injection,
plain-shell retry, bounded warm caches and atomic collection of abandoned phone
groups, mixed shell/tmux/cmux-tui navigation, SSH Files/browser/media and physical
acceptance. Correction to the original audit note: upstream collection lists
unattached groups and then removes them by name; it does not recheck attachment
inside tmux. An Android execution-time recheck is an additional protection.

## Visible tmux recovery and moved-pane actions (2026-10-01)

The visible tmux route now observes the account's SSH coordinator while the app
is started. A dropped connection can reconnect automatically and reattach the
shown pane, preserving its remote session and captured output. Explicit
Disconnect and declined identity prompts retain the coordinator's persisted
auto-connect pause; failures require an explicit retry. The terminal and workspace
screens expose Reconnect, progress and errors. View cancellation does not cancel
the coordinator's shared dial. Reattachment matches the server/session creation
identity and exact window/pane; missing or replaced targets show an error rather
than opening an unrelated pane. This selection is retained in the current route,
not yet restored after process death.

Split actions now use a fully qualified session/window/pane target for both the
tmux guard and mutation. A real-process regression moves a pane into a different
session before sending the old action and verifies refusal without adding a pane
to that destination. Opening a pane also checks its current inventory membership.
Further races during control capture/input and cold-start restoration remain
separate acceptance work.

**Verification:** 24 focused JVM checks passed without skips (15 tmux, including
the private real-process case, and nine connection-coordinator checks). Debug and
instrumentation APKs built. All three real SSH/tmux Android checks passed in
**50.212 seconds** on the single API 37 emulator with **16,384-byte pages**. The new
route test drops the transport while a pane is visible, waits for the UI itself
to recover before looking up the provider, verifies old and new output, then
checks persisted Disconnect and explicit Reconnect. The existing workspace
creation/split/end and account-retirement checks also pass. The recovered-terminal
capture was inspected; this remains the isolated `cat` fixture, with its line
discipline echoes, rather than physical-device or arbitrary-TUI acceptance.

- App SHA256: `ccb7bc35f74ca6afe6573bd26ba44079eb814072bada677d5676c973ce390a92`.
- Test SHA256: `eba684027e75b2271060083bdcd230a93e30c771f44b1adb5d7524dab2ca6605`.
- Ignored evidence: `captures/runtime/tmux-workspaces/reconnect-android/`,
  `reconnect-jvm/`, `reconnect-build.txt` and `reconnect-fixture-trace.txt`.

The emulator and private fixture were stopped. Signed build 284 and the Pixel
installation remain unchanged. Foreground/background fault injection, failed
reattachment UI, cold-process selection, plain-shell retry, mixed providers,
cache bounds, SSH Files/browser/media and physical acceptance remain open.

The iOS idle-close setting applies to **detached cmux-tui sessions on the remote
computer**, through its `terminal-idle-close-v1` capability and per-surface policy.
It is not an Android inactivity timeout or authorization to kill tmux sessions.
That policy will be connected with the cmux-tui provider; see [host editor][editor]
and [cmux-tui provider][tui].

## tmux workspace UI and Android SSH integration (2026-10-01)

Each saved SSH computer now exposes **tmux Workspaces** beside **New Shell**.
Opening it discovers tmux in the login PATH/common install locations and lists
existing sessions, windows and panes; it does not install a remote dependency.
The host/provider is tied to the existing account-owned SSH connection. A route or
account retirement closes its controls/renderers. Navigation keeps an attached
pane warm, and reopening uses the same terminal object while its stream is live.

The shared terminal screen now accepts both plain PTYs and tmux pane adapters.
The latter uses a silent Ghostty mirror: tmux already answers application terminal
queries. Captures replace the screen before live output, and server layout events
supply the actual pane grid. The phone requests client geometry, while the painter
fits/letterboxes the pane. Current pane titles and split labels update from inventory
without replacing the terminal. Keyboard, composer, toolbar, text paste, Text view,
scrollback and zoom use the existing screen controls.

The workspace UI exposes New Workspace, New Terminal, Split Right/Down, Refresh
and confirmed End Workspace. New windows/splits are detached so they preserve the
original session's selected window. tmux 3.2+ receives `COLORTERM=truecolor` for
new shells; older/unknown version strings use the compatible command form without
that option. Window creation/closing runs under the provider owner, so navigating
away does not cancel and automatically repeat an uncertain mutation.

Session actions carry server PID, numeric session ID and creation time. tmux checks
the server/creation condition in its own command queue before a target mutation;
a stale confirmation cannot silently address a replacement session. The phone's
group is removed before ending the original session, otherwise its linked windows
would keep programs alive. Graceful `%exit` completes that phone detach. Discovery
hides iOS and Android phone groups and collects only unattached generated Android
group names. A failed attachment makes the next discovery reconsider collection.
Topology bursts coalesce and changes arriving during a refresh request a follow-up
pass. Listing malformed geometry or failed operations produces an error.

The current UI is a tmux-specific route beside plain shells. Consolidating all
plain/tmux/cmux-tui workspaces into the complete iOS-style mixed tree remains open.
Also open: cold-process/reconnect acceptance, pane-move races, older tmux runtime,
cache/idle policy, SSH Files/browser/media, physical keyboard/device acceptance
and full accessibility/visual parity. This checkpoint does not advance the broad
upstream reference or constitute a complete parity release.

**Verification:** fifteen focused JVM checks passed without skips, including the
real private tmux process case. Two Android UI checks then passed against an
independent loopback SSH server running **real tmux 3.7c**, through the production
SSH adapter, control client and Ghostty renderer. The final run took **30.598
seconds** on API 37 / 16,384-byte pages. It verified discovery of a pre-existing
session, ANSI/Unicode and bracketed-paste mode, composer input, navigation retaining
the same pane, new window, split, unchanged original selected window, pane-label
updates, cancel/confirm end, removal of the phone group, new workspace creation,
server-identity refusal and account retirement rejecting input.

The first UI run timed out at Split; explicitly waiting for enabled actions
resolved that test failure. A later check verifies that existing
pane headers follow updated inventory metadata. Final screenshots were inspected
in their settled state. These component tests use an isolated vault/store and
empty temporary remote HOME with `cat` panes; caret escape echoes come from that
fixture's line discipline. Their white status bar/edge-to-edge system inset is
not evidence of production Activity styling. Physical keyboard/Pixel, arbitrary
TUIs, older tmux and full app restoration are not established by this run.

Final APK SHA256 values:

- App: `9e5f65e19b41160560b646ac02a6879b2e44f75434b6bedbb76b2cff16ffc667`.
- Instrumentation: `81582a97b62827b794f9a4b5aef36d49a65b3ee72b995c40556db3a64dd7e931`.

Ignored receipts, logs and images are in `captures/runtime/tmux-workspaces/`, with
`android-final/` and `jvm-final/` holding the final results. The sole emulator and
private fixture were stopped. Signed build 284 and the physical Pixel are unchanged.

## tmux control-mode foundation (2026-10-01)

`SshTmuxProtocol.kt` and `SshTmuxControl.kt` implement the tmux pane stream needed
by the mixed workspace provider. This layer is **not yet exposed in the Android
workspace UI**. It follows the upstream control parser/client at `204a11d`, with a
phone-owned `-cmux-android-` grouped session, `destroy-unattached off`, one
non-PTY SSH exec channel per session, and pane IDs independent of UI positions.
The group's own selected window leaves the original session's selection intact.
The transport's `openTmux` path provides that raw exec channel and scoped cleanup.

- Pane output retains its raw bytes, including UTF-8 split across notifications.
  Command blocks keep blank lines and rows that resemble protocol guards; only
  the matching time/command/flags guard completes a reply.
- Startup replies cannot consume command replies. Canceling a caller retains its
  response slot; uncertain writes/deadlines close the control stream without
  replay. Line, reply, command queue and pre-seed output buffers have finite limits.
- Attach pauses output where supported, reads alternate-screen state, captures
  history/screen, restores cursor/modes, then resumes. Android also seeds tmux's
  `bracket_paste_flag`, so paste mode enabled before attachment is retained. Output before capture is
  discarded; output after capture waits until the snapshot is delivered. Stale
  seed callbacks cannot revive a detached pane.
- Layout/zoom changes supply the server's pane grid. Window removal ends affected
  panes and bursts of topology notifications coalesce. Screen title strings are
  stripped across chunk boundaries before terminal rendering.
- Graceful detach requests removal of the phone's grouped session before closing
  the channel. Abrupt disconnect still requires the provider's future stale-group
  collection pass. Group discovery/coalescing, inventory/creation/destruction UI,
  reconnect, and wiring fixed-grid panes into the renderer remain next steps.

**Twelve JVM checks passed without skips**, including a real **tmux 3.7c** process
on a unique private socket, with an empty temporary HOME/config and `/bin/cat`
panes. The live test verifies history and pre-existing bracketed-paste mode seed,
Unicode input, independent selected
windows, 72×18 geometry, split notifications, grouped-session removal, and the
original session remaining alive. The other eleven checks exercise stream parsing,
seed ordering, cancellation, deadlines and owner cleanup. The first live run used
`client_height`, which tmux leaves empty for non-TTY control clients; the corrected
assertion checks actual window geometry. Final XML/receipt is in ignored
`captures/runtime/tmux-control/jvm-final/`. The fixture deletes only its private
server and temporary directory.

The unchanged raw SSH adapter passed **all fourteen Android transport checks**
in **13.572 seconds** on API 37 / 16,384-byte pages, including the new non-PTY
stream check: split UTF-8, NUL/escape/newline bytes without terminal framing,
Main-thread channel close preserving the SSH session, EOF and owner retirement.
The test server still executes only synthetic commands. APKs for that adapter run:

- App: `f6717485d7662c53707ab49719c6e9f620f92251820f26c834c6fe6957f5de43`.
- Instrumentation: `65000d970253fc3d942577abf025d5fde4a1bffb06c0ba8d678763efe5f732a5`.

Receipt: `captures/runtime/tmux-control/android-transport/`. That APK predates the
last bracketed-paste seed addition, which was subsequently compiled and verified
by the final twelve JVM/real-tmux checks. The tmux control client itself has not
been exercised through Android SSH to a real tmux server yet; the separate tests
establish each layer, not their end-to-end integration. The emulator and fixture
were stopped afterward; no new signed build or Pixel installation occurred.

Run with tmux installed and the emulator stopped:

```sh
CMUX_TMUX_TEST_BINARY=/absolute/path/to/tmux ./gradlew --no-daemon --max-workers=1 \
  :app:testDebugUnitTest --rerun --tests '*SshTmux*'
```

The real-process case is opt-in and skips without that variable. A normal JVM CI
pass without it does not establish real-tmux coverage. Neither this fixture nor
protocol tests establish Android tmux UI, arbitrary TUI, physical-device, mixed
provider or full-parity acceptance. Source attribution is bundled in Notices.
Primary protocol reference: [tmux Control Mode](https://github.com/tmux/tmux/wiki/Control-Mode).

## Plain SSH shell and Ghostty replies (2026-10-01)

Saved SSH computers now expose **New Shell**, open and close actions. Each shell
owns a PTY and Ghostty renderer under the account runtime; navigating back releases
the screen but retains the same shell. Multiple shells share the host connection.
Closing one shell releases its channel without disconnecting other host work.
Account retirement and host removal close their shells. Ended shells currently
retain final output until explicitly closed; durable reconnect is not claimed.

The screen uses the existing terminal grid, text selection sheet, direct keyboard,
hardware-key encoder, custom toolbar, modifiers, text clipboard paste, composer,
scrollback and pinch zoom. The PTY receives UTF-8, `xterm-256color`, ordered input
and resize events. Bracketed paste follows the terminal mode. Input and terminal
query replies share a bounded queue (256 commands / 256 KiB); overflow or failed
writes end that shell without replaying uncertain input. Files/media paste and SSH
mouse forwarding remain to be connected.

Ghostty query replies are explicitly opted into for SSH. Cursor/status/size queries
and resize reports return owned bytes through JNI, with a sticky 256 KiB overflow
failure. Existing Mac mirrors stay silent by default; no Java callback reenters
native parsing. Clipboard/title/filesystem effects remain disabled. Native workflow
[36898404839](https://github.com/DocMorphic/cmux-app/actions/runs/36898404839)
at `29fec4c` rebuilt the pinned core/binding and passed its JVM/package and 16 KiB
ELF LOAD/RELRO/APK ZIP gates. Its verified native artifact is used in the app below.

The shell-close regression initially caused an SSH MAC error: channel disconnect
sent its close packet from Main, where Android rejects network writes after JSch
advances encryption state. Channel cleanup now runs on a network worker. Canceled
operations close their resources without interrupting a worker midway through a
packet. The regression verifies the next exec on the **same** transport, remote
shell count zero, reopening, and immediate input rejection on account retirement.

**Verification on the retained API 37 emulator, 16,384-byte pages:**

- Two production shell-screen checks passed in **48.787 seconds**: ANSI/Unicode,
  bracketed paste, live cursor-query response, alternate screen, composer, Text
  sheet, navigation preserving the shell, resize, close and account retirement.
- All thirteen existing transport checks passed in **14.478 seconds**, including
  cancellation, trust, jumps, coordinator ownership and SFTP.
- Four existing Ghostty mirror/render checks passed in **0.137 seconds**.
- Nine native Ghostty runtime checks passed in **0.151 seconds** on the exact new
  native test artifact, including query ordering/resize/overflow and silent mirrors.

Final app APK SHA256:
`68f0c8fdae3ab836cd5fe7441c18cbb52b588283a6cbf1654125f778dc6d6349`.
Instrumentation APK SHA256:
`e8cdaa6dd61889bd52b701640243081c36362b214ec0431e945aa410aad1e8e7`.
Ignored evidence: `captures/runtime/ssh-transport/shell-worker-close/`,
`shell-worker-transport/`, and `ghostty-replies-tests/`. The shell screenshot was
visually inspected. The component fixture's white system status bar is not proof
of production Activity styling. The fixture implements synthetic terminal commands;
it does not establish real tmux/cmux-tui, arbitrary TUI, full-app restoration,
physical keyboard/biometric or Pixel acceptance. Signed build 284 and the Pixel
installation are unchanged. Mixed providers, idle enforcement, password key setup,
SFTP/browser UI and physical acceptance remain required.

## Account-owned SSH hosts and prompts (2026-10-01)

`NativeAppConnections` now owns a `NativeSshRuntime`. Each login incarnation gets
one coordinator; token refresh and team changes retain it, while sign-out,
a different login or closing the shared application owner retire connections
and pending prompts. Admission also checks the current credential incarnation
synchronously, so delayed account observers cannot authorize an old connection.
Loading runs on the owner's IO scope. Unreadable metadata/key storage produces a
retryable error without resetting saved data or preventing native Mac use.

The signed-in Computers and Settings screens open a host list and editor with
address/port/username, optional name (defaults to address), key selection and a
jump-host selector. Validation normalizes bracketed addresses and rejects invalid
ports; the store rejects missing/cyclic jump routes. Adding or changing a route
connects; label-only changes retain the connection. Confirmed deletion removes the
host and retires dependent routes. Disconnect persists automatic-connection pause;
an explicit Connect resumes it. Editor fields survive an excursion into key
management. A concurrently edited/deleted host cannot be silently overwritten.

Root-level identity prompts show the presented algorithm/SHA256 fingerprint and,
for changed keys, the previous fingerprint and explicit replacement action.
Cancellation retains the prior pin and pauses reconnection. Prompt callbacks use
the coordinator's exact question ID. Platform biometric presentation carries the
exact prepared Keystore Signature (API 28+ BiometricPrompt, API 26–27 fingerprint
prompt), queues requests, and ignores callbacks from disposed presentations.
Prompts are presented only while the Activity is started. This source integration
is **not yet a physical biometric/enrollment/rotation acceptance result**.

**Verification:** four account-lifetime JVM checks, two atomic-editor checks and
all nine existing coordinator checks passed. Debug/test APKs built. Three API 37 / 16 KiB host-screen checks
passed against the independent AsyncSSH loopback fixture: editor validation/name
fallback and unknown-key connection; changed-key cancellation/pin preservation
then explicit replacement; key-screen draft preservation/rename retaining the
same transport/confirmed deletion closing it. The runner refuses physical devices
and uses isolated host/key storage. Its `--ui` mode records APK hashes, page size,
instrumentation output and process diagnostics. The initial screenshot capture
caught platform animations before they settled, so the capture helper was corrected
and the suite rerun. Review then identified a compare/write race: host editor saves
now perform that operation under the store lock and synchronize against vault key
deletion. The editor also restores its comparison base alongside its draft, so an
old restored form cannot silently overwrite a newer route. An additional Android
restoration check was added; see the final receipt below. This is component-screen runtime
coverage, not a full-app account/navigation/process-restoration acceptance run.

Final source verification: **15 JVM checks and 4 Android UI checks passed**.
The final Android run took **41.268 seconds** on API 37 with 16,384-byte pages.
All four completed without skips. Final APK SHA256 values:

- Debug app: `b9fb5dc09a3e6a54cec79b962ce68924958985712b7778b6aad7646c8cc798c0`.
- Instrumentation: `b01561ae9c166664cd897d00358fd8ef0b7009deaf30b9be04fbcf6330bca210`.

Ignored evidence: `captures/runtime/ssh-transport/hosts-ui-edit-guard/` contains the
final receipt, instrumentation output, diagnostics and four screenshots. Captures
were visually inspected across the capture/final runs. The component fixture's
white system status bar is not evidence of the production Activity's system-bar
appearance. The single retained emulator and loopback server were stopped afterward.

Remaining: mixed shell/tmux/cmux-tui workspace providers and terminal routing,
idle-session enforcement/editor control, one-time password key installation,
SFTP/browser integration, full app/process restoration, account UI retirement,
API 26 prompt runtime and physical biometric/SSH acceptance. The editor does not
advertise an idle timeout before provider/session enforcement exists. Phone-local
host/key records intentionally remain across sign-out, matching the upstream model;
network owners do not. Existing signed build 284 and the Pixel install are unchanged.

**Engine experiment (2026-09-30):** the opt-in [ssh-spike module](../ssh-spike/README.md)
now passes five checks on API 26 / 4 KiB pages (5.027 seconds) and the same five
on API 37 / 16 KiB pages (6.401 seconds). JSch 2.28.0 + BC 1.86 authenticated with
a nonexportable Android Keystore P-256 key and encrypted OpenSSH Ed25519/ECDSA
imports; verified unknown/changed-key refusal; transported exec, PTY resize and
Unicode input; round-tripped SFTP; and tore down two nested forwarding hops.
The server is an independent AsyncSSH 2.24.0 fixture with generated keys,
synthetic commands and a temporary SFTP root. These are actual Android/SSH runtime
checks, not production UI, biometric, physical-device or real multiplexer acceptance.
That experiment added no dependency to the delivered app; the later production
key-vault checkpoint below adds the tested libraries for key handling.

Final experiment APK SHA256 values:

- App: `32cfab133e49a9baddce2b46f207569d441a36c32bd28a986c13cefcaff8d4aa`.
- Instrumentation: `4c655ffaa4096626680b740620474950366f135b0510ea1f7a86b61e0c3a4f82`.

Ignored evidence lives in `captures/runtime/ssh-engine/api26/` and
`captures/runtime/ssh-engine/api37-final/`; the runner records API, page size,
hashes and instrumentation results. A preliminary five-test API 37 run passed
before strengthening changed-key error assertions; the final runs above use a
different key of the same algorithm and verify the specific exception plus a
subsequent successful connection with the correct pin. The next step is production
identity/storage and session lifetime integration. Biometric/physical validation,
authentication-time cancellation and real multi-host behavior remain open.

**Production host metadata foundation (2026-09-30):** `SshHosts.kt`,
`SshHostStore.kt` and `AndroidSshHostStore.kt` now provide endpoint/record models,
canonical host public keys and fingerprints, saved hosts/pins/last-used selection,
idle policy and persisted auto-connect pause. The Android factory stores metadata
with `AtomicFile` under `noBackupFilesDir/ssh/hosts-v1.json`, with one main-process
instance per storage path. It contains public key material and key IDs only.
The later vault and transport checkpoints connect this foundation to the SSH
engine; Computers UI integration remains outstanding.

The store rejects corrupted/unknown-version trust data rather than loading an
empty set of pins. It rejects missing/cyclic jump routes. Deleting a jump clears
dependent references and the last-used selection, matching the reviewed source;
endpoint pins remain available for other saved logins. Removing a key reference
retires affected dial plans. Legacy persistence-mode data is ignored, and missing
or unknown idle policy defaults to one day.

Dial plans capture every hop plus connection revisions. Address/user/key/jump or
pause changes invalidate them, including A→B→A changes, while label/idle edits do
not. Trust snapshots also have revisions: a stale question cannot replace a pin
after a competing answer or pin round trip. Revisions update before observers see
new state, and writes complete before state/revisions are published. The transport
now validates plans after suspension, fences the supplied account lifetime,
resolves credentials and retires affected live sessions. Connection coalescing,
prompt coordination and the UI owner remain to be integrated. The storage layer
alone does not implement those behaviors.

**16 focused JVM tests passed** on the final source, covering restoration, OpenSSH
fingerprint goldens, malformed metadata, jump cycles, concurrent saves, stale
plans/answers, pause persistence and write-failure rollback. OpenSSH `ssh-keygen`
independently checked the two generated public-key fingerprints used by the tests.
Evidence is in ignored `captures/runtime/ssh-store/`. This is JVM/model evidence;
Android file fault/process-death acceptance, secure key persistence, biometrics,
session/UI integration and physical use remain outstanding. No APK was assembled
or installed for this source-only checkpoint.

## Production key vault — 2026-10-01

`SshKeyCrypto.kt` and `SshKeyVault.kt` now implement device-local private-key
storage and signing. JSch **2.28.0** and Bouncy Castle **1.86** are app dependencies
for OpenSSH parsing/signing. Their complete JSch, jBCrypt, JZlib and BC license
notices are bundled and shown in Open-source licenses. The four notices were
checked byte-for-byte against the source artifacts and the debug APK. No new
native library or process-wide Android security provider was added.

- Generated P-256 keys remain in Android Keystore with no exportable private
  encoding. A prepared one-use signature operation supports a future biometric
  CryptoObject prompt. Optional per-use strong-biometric policy is configured at
  creation, including enrollment invalidation; there is no silent fallback to an
  unprotected key when creation fails. This does **not** establish hardware-backed
  protection or working biometric UI on the Pixel.
- Imported Ed25519/ECDSA OpenSSH keys are validated before saving. AES-GCM protects
  their key bytes/passphrase using a separate Keystore AES key per imported key.
  Authenticated data binds the ciphertext to its key ID and public key. Owned
  temporary byte buffers are cleared; callers retain responsibility for their
  import/UI buffers. RSA import remains unsupported as in the reviewed iOS UI.
- Metadata and ciphertext live in `noBackupFilesDir/ssh/keys/keys-v1.json`.
  Atomic writes are synced and read back before publishing new records. Missing
  encryption aliases are never regenerated while opening an existing record.
- Deletion first persists a tombstone, hides the key and prevents further signing,
  then clears host references and removes the alias. Failed cleanup resumes when
  the vault reopens. Deleted ciphertext cannot be revived by restoring metadata.
  Interrupted creations leave only aliases eligible for scoped orphan cleanup;
  malformed metadata fails before any cleanup.
- Sign operations recheck record/alias existence and the unlocked-device gate.
  Imported-key leases are revoked on deletion and disposed when closed. Generated
  signatures verify that metadata matches the Keystore public identity.

The main-process singleton is the production access path. The connection manager
must still own account admission, lease closure, prompt coordination and network
session retirement. A generated key's Android Keystore API is not itself proof of
hardware protection. The unlocked-device policy flag is used on API 35+; older
versions use the app gate because [Android documents earlier flag issues](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setUnlockedDeviceRequired(boolean)). Physical
lock/biometric/invalidation behavior and actual OS process-death acceptance remain
open. Tests reconstruct the vault from persisted files within their test process.

**Eight Android runtime tests passed on API 26 (2.046 seconds), and the same eight
passed on API 37 with 16 KiB pages (5.347 seconds).** They cover generated signing,
encrypted Ed25519/ECDSA import/reload, wrong passphrases and unsupported RSA,
authenticated ciphertext/public-identity tamper rejection, deletion/old ciphertext,
failed writes/cleanup, the injected locked-state gate and closed leases, and
corrupt-metadata retention. Sixteen existing host-store JVM checks also passed
after introducing the dependencies. No physical device or account was touched.

Final debug APK SHA256:
`210ce310af54a4d31cbccb87682cebabd2e9d2510622162a8c0cf4ab69d2f1b7`.
Final test APK SHA256:
`61a16eeeb3c28905cf54324282915e6f32e5b6be951c78a194c04bf20e619524`.
Ignored evidence is in `captures/runtime/ssh-vault/`, particularly
`instrumentation-26.txt`, `instrumentation-37-final.txt`, `apk-hashes.json` and
`license-hashes.json`. This is a debug/source milestone; signed 284 is unchanged.

The initial API 37 run exposed a test verifier issue: JSch 2.28.0's public-only
Ed25519 `KeyPair.load` path supplies the text line as the raw public point. The
final verifier parses bounded SSH wire fields and uses BC's Ed25519 verifier
directly; generated/private-key loading in the vault did not change for that fix.
Future transport code must avoid that public-only loader path.

## Production SSH transport — 2026-10-01

`SshTransport.kt` connects saved host routes to the production key vault. Its
caller must supply a signed-in account lifetime, an admission predicate and
identity/biometric prompt callbacks. It supports generated P-256 and imported
keys, strict endpoint-key verification, exec, ordered PTY input/resize and scoped
SFTP channels. Imported private-key leases close immediately after authentication.

Jump routes use nested SSH `direct-tcpip` channels without temporary local TCP
listeners. Pins use each original hostname and port. Every suspension rechecks
the saved route and owner; late trust answers cannot pin an edited route. Declining
a changed identity retains its previous pin and pauses automatic connection.
Explicit connection clears the relevant route pauses. Owner cancellation, key
deletion, endpoint/key/jump edits, pin replacement or a dropped hop retire the
route and its owned channels. Label and idle-policy edits preserve a valid session.

JSch's proxy interface can have no Java socket, so its socket read timeout does
not bound a silent jump target. `SshTimedInputStream` supplies a bounded,
backpressured stream with a 10-second handshake read timeout and 15-second
connected read timeout for keepalive handling. Exec also has an output-size bound
and deadline; a timed-out command closes its channel while leaving the session
available. These are transport mechanisms, not evidence of network handoff or
long-duration reconnect behavior.

**Eleven Android transport checks passed on API 37 / 16 KiB pages in 22.932
seconds.** These exercise the production host store/vault/transport against the
independent generated AsyncSSH fixture: generated and imported authentication,
pin reuse, changed-key refusal/pause/explicit retry, cancellation during trust,
late answers after route edits, owner/key/pin retirement, exec/Unicode PTY/resize,
70,000-byte SFTP transfer, two jump hops, exec timeout, a silent jump target and
target disconnect cleanup. Four timed-stream JVM tests and the sixteen host-store
tests passed on the same production source. No physical device was touched.

The interrupted first attempt reported only `Process crashed`; no test result
was credited. The subsequent run used identical APK hashes and saved all eleven
successful instrumentation statuses plus scoped logcat. Evidence is in ignored
`captures/runtime/ssh-transport/api37-resumed/`; the earlier failure remains in
`api37-final/`. Debug APK SHA256:
`fb9ec2b339547244685e9d64e10d1dda759588fdd806ab4f4893a601999452c6`.
Test APK SHA256:
`4601fa87cff9bc16f964ba82f7826751f86d1efd0810b899a31af676d37fed17`.
Signed build 284 and the Pixel installation are unchanged.

Connection coalescing/retry, prompt presentation, Computers/key UI, real biometric
authorization, one-time password key installation, shell/tmux/cmux-tui providers
and SSH Files/browser integration remain outstanding. This transport is not yet
reachable from the app UI. Earlier API 26 vault/spike checks do not establish
API 26 transport coverage; only the existing API 37 / 16 KiB emulator is retained
on this Mac following the user's storage request.

## Connection and trust coordinator — 2026-10-01

`SshConnections.kt` now owns one in-flight/live connection per saved computer
within a supplied login lifetime. Explicit opens and automatic opens join the
same work. A view canceling its wait does not cancel the shared handshake or
identity question. Failures remain visible and wait for explicit retry; a dropped
established connection returns to idle, eligible for a later foreground/list open.
There is no background reconnect loop.

The coordinator exposes ordered trust prompts for root presentation. The same
identity question from concurrent routes shares one prompt; different questions
get distinct IDs and stale dismissal callbacks cannot cancel a newer question.
Rejecting a jump identity pauses that host for all dependent routes. Disconnect
persists pause; explicit open resumes it. Route/key changes and owner cancellation
retire affected work. Name/idle-policy edits keep the connection. The host store
now retains an in-memory receipt for the exact committed trust decision so all
coalesced waiters can confirm it. Any later pin mutation, including removal or a
key round trip, invalidates that receipt; route revisions remain independently
required.

**Nine coordinator JVM tests plus sixteen host-store JVM tests passed.** The
production transport suite now has **thirteen passing Android 17 / 16 KiB tests
(13.136 seconds)**, including real concurrent coordinator opens, view cancellation,
disconnect/pause and a shared jump identity against the generated server.
The APKs were built after restoring only the missing, published native checkpoints
(about 23 MiB); no additional AVD or system image was created.

Debug APK SHA256:
`97cd1e757940e0cfa3a243234a507e892781345f77a999142d2de70a4e9d65c4`.
Test APK SHA256:
`b843449558b44dce9ae690b80bd5ce1a51d9f75a7b18811cc803b832bbdde745`.
Evidence is in `captures/runtime/ssh-transport/coordinator-api37/` and
`coordinator-build-2.txt`. The runner saves crash diagnostics and requires every
case to finish without skips. The earlier eleven-test checkpoint remains
historical evidence.

The supplied owner is not yet wired to the production account/UI runtime.
Computers/key screens, root prompt/biometric presentation, idle closure,
workspace provider integration and physical acceptance still need implementation.
Signed 284 and the physical Pixel installation remain unchanged.

## SSH Keys in Settings — 2026-10-01

The signed-in Settings screen now opens `NativeSshKeysRoute` and the production
`SshKeysScreen`, backed by the encrypted vault. Loading runs off the UI thread;
corrupt/unreadable storage shows a retryable error without resetting keys. The
route observes account-store revisions and hides key management when its captured
login is no longer admitted. Settings/key navigation and public form labels use
saved state; private-key text and passphrases do not.

The screens follow the reviewed iOS key-management flow: generate a P-256 key with
optional biometric policy (default off), import OpenSSH Ed25519/ECDSA text or a
chosen file, show algorithm/origin/fingerprint, copy/share the public key, rename,
and confirm deletion. Deletion removes dependent hosts' key references through
the production vault callback. A wrong passphrase keeps the input available for
retry. File reads are bounded below 64 KiB before UTF-8 decoding, even if a
provider omits its size. The import screen sets `FLAG_SECURE` and restores the
previous window flag when it leaves. Owned secret byte snapshots are cleared on
operation completion, including cancellation before the coroutine starts.

**Three focused JVM checks passed** for file-size limits and strict UTF-8. **Three
Android 17 / 16 KiB UI checks passed in 61.276 seconds**, exercising generation,
rename, actual public-key clipboard contents, canceled/confirmed deletion with
host-reference cleanup, encrypted import after a wrong passphrase, and Compose
saved-state restoration that retains the label while clearing both secret fields.
The screenshot-window flag was checked during import and after leaving it.

An initial capture labeled as the key list still showed generation in progress.
The capture now waits for the actual key-row tag rather than matching the same
name in an input field. The focused management check passed again in 21.890
seconds, and the corrected list/generate screenshots were inspected. This is
component UI evidence, not full-app system-bar or physical-device acceptance.

Debug APK SHA256:
`edba77013c3dfe57d3b79b6917e56d1ee6fa675431491c3ca3e12a8e1b1eecfe`.
Final test APK SHA256 after the capture correction:
`e204b56fad3104e5d9a7cbea1a3dc37c7f1c137024a82e41e28761a3bb2e219e`.
Evidence is in `captures/runtime/ssh-transport/keys-ui-api37/` and
`keys-ui-capture/`. `SshKeysScreenTest` uses its own temporary vault/host metadata
and does not clear accounts. The checks ran only on the retained emulator, which
was stopped afterward. Signed 284 and the Pixel installation are unchanged.

The system document-picker/share-sheet round trip, full Settings navigation after
actual process death, account-retirement race acceptance, biometric prompt/
invalidation and physical hardware behavior still need integrated checks.
The biometric toggle configures the key's policy; connection-time biometric
presentation is not implemented by this screen. Saved SSH Computers and the
coordinator's account/root-prompt integration are the next feature work.

The candidate wires SSH into normal iOS navigation, without a DEBUG gate:

- [DeviceTreeView.swift][computers] includes SSH computers alongside Macs, an Add
  SSH Computer action, and selection/edit/disconnect/delete actions.
- [MobileSettingsView.swift][settings] opens SSH key management.
- [CMUXMobileRootView.swift][root] presents SSH identity questions above other
  sheets, offers SSH from pairing, and retains the normal authentication gate.

The [upstream PRD][prd] contains superseded decisions. D47 requires phone sign-in;
the earlier signed-out SSH entry is removed. D31 replaces one persistence mode per
host with mixed workspace kinds. Neither obsolete flow should be ported. The SSH
server itself needs no cmux account or Mac companion. Hosts and keys stay local
to the phone, without synchronization.

## Required behavior

| Area | Source behavior to reproduce | Android acceptance evidence still required |
| --- | --- | --- |
| Computers | Add/edit a named host, port, username, key, jump host and idle-close policy; connect on explicit selection; disconnect pauses automatic connection | Signed-in UI, no paired Mac required; save/relaunch/edit/delete; meaningful connection errors |
| Address validation | Default port 22, ports 1–65535; trim host/user; accept pasted bracketed IPv6; reject empty/whitespace host and empty username | Source-derived validation cases and actual IPv4/IPv6/DNS connections |
| Keys | Generate nonexportable P-256 keys; optional biometric authorization per generated key, off by default; import OpenSSH Ed25519/ECDSA, including encrypted keys | Android Keystore signing against a server; biometric accept/cancel/invalidation; imported-key/passphrase handling and deletion |
| Host identity | OpenSSH SHA256 fingerprint; unknown-key question; changed-key question showing old/new identity; explicit replacement or cancellation | Correct fingerprint against ssh-keygen; no session opens before approval; changed-key refusal; every jump verified |
| Trust lifetime | Refusing identity pauses auto-connect persistently; explicit open retries; coalesce equivalent questions; ignore stale answers | Relaunch after refusal, concurrent connects and stale prompt answers |
| Key installation | One-time password installs the public key in authorized_keys; routine connections use keys | Isolated password server; resulting key login; password absent from persisted state/logs |
| Routing | Direct TCP over the phone's available network, including external VPN/Tailscale; multi-hop ProxyJump | Two-hop test, cancellation during each hop, endpoint isolation and reconnection |
| Workspace kinds | Plain shells, tmux sessions and cmux-tui workspaces coexist on each host | Real server inventory and creation for all three; missing-kind reason; no per-host mode picker |
| Terminal | Same renderer and input UI, PTY bytes, resize, snapshot/live output, bracketed paste, file/image upload adapters | ANSI/full-screen apps, Unicode, resize, attach/reconnect, scrollback and media paste against each provider |
| Persistent topology | Discover existing sessions; tmux window/pane actions; cmux-tui screen/tab/pane actions; event-driven updates | Laptop-created sessions, phone creation and split both directions; laptop focus preserved; event burst ordering |
| Closing | Confirm ending persistent tmux/cmux-tui workspaces, including laptop-created sessions; plain shell close is immediate | Confirm/cancel, exact target, remote failure and unknown-outcome handling |
| Geometry | tmux fixed pane grid where required; cmux-tui phone geometry only while displayed, released in order on detach | Concurrent laptop frontend, split panes, rotation, release/reconnect and restored laptop size |
| SFTP | Browse from home/current directory; navigate POSIX paths; preview/export/upload with progress; mkdir/rename/delete | Real SFTP server, nested paths/symlinks, transfer cancellation/errors, nonempty-directory refusal |
| Browser | Per-host SOCKS and loopback forwarding, plus cmux-tui streamed browser/On Phone behavior | HTTP/HTTPS/upload via SSH, host switch, reconnect and forwarding retirement; no cross-host tunnel reuse |
| Persistence/lifetime | Local records, last host, idle policy; connection identity fencing; stale callbacks cannot close replacements | Process restoration, logout/key/host deletion, account switch, late callbacks and foreground/background lifecycle |

The host editor, record compatibility and identity calculations are described by
[SSHComputerEditorView.swift][editor], [SSHHostStore.swift][hosts],
[SSHEndpoint.swift][endpoint] and [SSHHostKey.swift][hostkey]. The known-host lookup
uses lowercase host at port 22, or `[lowercase-host]:port` otherwise. Fingerprints
hash the binary public-key blob and use Base64 without padding.

[SSHKeyStore.swift][keys] stores generated Secure Enclave keys and imported secrets
separately from metadata. Android needs the corresponding Keystore-backed design;
hardware protection and biometric behavior must be measured on the Pixel, not
assumed from an emulator. Imported secrets must remain device-local and excluded
from backup. The old PRD mentions RSA import; the current
[SSHKeysView.swift][keyui] explicitly reports unsupported types and directs users
to Ed25519/ECDSA. Do not treat the old RSA sentence as proof of working iOS support.

### Provider and session ownership

[MobileSSHHostProviders.swift][providers] multiplexes workspace kinds on one
connection. The legacy host `persistence` field is decode-only; an unknown old
value must not discard the host record. Idle-close choices are one hour, one day
(default), seven days and never.

Use explicit transport/provider ownership throughout Android. Upstream namespaces
SSH computers with `cmux-ssh-<host UUID>` and kind-encodes local workspace IDs.
Disconnected SSH surfaces still belong to SSH; they must never fall through to
Mac RPC. Changes to address/user/key/jump host retire a live connection, whereas
cosmetic label changes do not. Coalesce concurrent connect attempts and fence
callbacks by connection generation. Sharing the existing terminal presentation
does not imply sharing Mac transport capabilities or its replay proof rules.

For tmux, sessions are workspace rows, with windows and panes below them. Use a
phone-owned linked session to preserve the laptop's selection. For cmux-tui,
workspaces contain screens, tabs and panes; preserve the laptop frontend's view.
Plain shells have no nested-terminal creation controls. Inventory must discover
existing sessions, not only sessions created by this phone. Event subscriptions
must update topology and coalesce bursts; listing must never install software.

### cmux-tui installation and version boundary

[MobileSSHCmuxTUIProvider.swift][tui] pins **0.13.4** at this candidate. On the first
explicit creation requiring installation, the phone fetches a platform-specific
npm tarball, verifies registry SHA512 integrity, caches it and uploads it over
SSH. The remote script extracts a temporary archive and moves the executable into
`$HOME/.local/bin/cmux-tui`. The server does not need Node or internet access.
Test archive/version failure, unsupported platforms, cancellation and cleanup
before enabling production installation.

The PRD also reports server fixes that require a release **newer than this pin**:
laptop geometry restoration and empty-workspace tree-change notification. Those
reported fixes do not prove that the pinned uploaded binary contains them. Select
and test a compatible server version explicitly; do not copy the pin and claim
these behaviors verified. Scrollback over full-screen applications and streamed
browser interoperability also require concrete server checks.

### SFTP and browser lifetime

[SSHFileBrowserModel.swift][files] lazily opens one SFTP channel per sheet, retries
once on connection loss, resolves directory symlinks and uses remote POSIX paths.
Browsing starts at home or the terminal directory; a directory outside home uses
`/` as the navigation root. Uploads choose unused filenames. Deleting a nonempty
directory reports an error rather than recursively removing it. Closing the
sheet closes the channel and removes downloaded temporary content. Existing Mac
artifact authorization is not a substitute for SSH-user filesystem permissions.

[MobileSSHComputers+Browser.swift][browser] binds proxy/loopback routes to the
current SSH connection and host owner. Reconnection must publish fresh forwards
and retire stale ones. Extend the Android browser owner/session design for SSH;
passing SSH URLs through the current Mac tunnel would route them incorrectly.

## Implementation order and completion gates

1. **SSH engine compatibility spike.** Use generated keys and an isolated test
   server, without enabling the user's Mac SSH service or using personal keys.
   Evaluate maintained engines for Android API 26 and API 37 before adding a
   production dependency. Prove Keystore P-256 signing without exporting a private
   key, encrypted Ed25519/ECDSA imports, strict host-key verification, PTY resize,
   exec, SFTP, direct-tcpip/ProxyJump and cancellation. A desktop JVM connection
   alone does not establish Android crypto-provider compatibility.
2. **Identity, storage and connection runtime.** Implement local host/key stores,
   trust prompts, biometric signer, one-time key installation and a connection
   generation model. Cover late answers, key deletion, jump cycles, cleanup and
   process death before exposing reconnection.
3. **Signed-in Computers and shell integration.** Add SSH hosts and key management
   through the existing UI; connect a plain shell through the existing terminal
   renderer/input controls. Verify physical Pixel sessions and account lifecycle.
4. **Mixed persistent providers.** Add tmux and cmux-tui inventory, creation,
   topology, attach/replay/resize and close controls. Verify simultaneous laptop
   and phone use, persistent sessions and interrupted operations.
5. **Files and browser.** Integrate SFTP previews/transfers/mutations and SSH-owned
   browser routes with the same navigation and session lifetime rules.
6. **Delivery.** Run integrated acceptance, including physical reconnect and
   background behavior; audit accessibility and visual parity; then publish a
   signed milestone. None of these gates is complete in this audit commit.

ET/mosh, SSH-config import and embedded Tailscale are upstream follow-ups rather
than requirements inferred from available UI. External Tailscale/VPN routing is
part of direct SSH. Android-specific differences require evidence, not a blanket
platform disclaimer.

## Audit reproducibility

Read the linked files at the exact candidate with `git show <commit>:<path>` in
the upstream cache. Local research copies are in ignored
`captures/runtime/ssh-audit/`; they are convenience copies, not release evidence.
The primary source links below make the audit reproducible without those files.
The original audit changed documentation only. The subsequent opt-in engine
experiment is separately scoped above; it installed only its own emulator test
packages. The production app, physical Pixel installation and signed milestone
remain unchanged by this experiment.

[computers]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/DeviceTreeView.swift
[settings]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobileSettingsView.swift
[root]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/CMUXMobileRootView.swift
[prd]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/docs/prd/ios-direct-ssh.md
[editor]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/SSHComputerEditorView.swift
[hosts]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileSSH/Sources/CmuxMobileSSH/SSHHostStore.swift
[endpoint]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileSSH/Sources/CmuxMobileSSH/SSHEndpoint.swift
[hostkey]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileSSH/Sources/CmuxMobileSSH/SSHHostKey.swift
[keys]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileSSH/Sources/CmuxMobileSSH/SSHKeyStore.swift
[keyui]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/SSHKeysView.swift
[providers]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileSSHHostProviders.swift
[tui]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileSSHCmuxTUIProvider.swift
[files]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/SSHFiles/SSHFileBrowserModel.swift
[browser]: https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileSSHComputers+Browser.swift
