# Direct SSH parity and implementation plan

## Status and evidence boundary

**SSH computers can now be saved and connected from the Android UI.** Computers
and Settings expose the host list/editor, and Settings exposes SSH key management.
Plain SSH shells now open from the saved-host list with the Ghostty terminal.
tmux workspaces now have a host entry, pane UI and persistent actions. Mixed
cmux-tui workspace integration remains open. Production host storage,
private-key storage and the low-level transport are implemented as described in
the checkpoints below. Existing native Mac pairing, terminal
rendering, Files and browser tunnel tests do not prove SSH/SFTP support. This audit
establishes the required behavior at upstream candidate
`204a11dfcc76280205e50406ab94270a1c152155`; it does not advance the broad implemented
reference or establish which App Store/TestFlight binary contains these features.
Older checkpoints below retain their original evidence boundaries; the shell
reconnect and grouped-session cleanup checkpoint is the latest implementation status.

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
