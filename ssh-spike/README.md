# Android SSH engine experiment

This opt-in test application evaluates JSch **2.28.0** with Bouncy Castle
**1.86** on Android. It is not part of `:app`, contains no production SSH UI and
must not be published as the cmux companion. Within this module both dependencies
are confined to the instrumentation APK. The later production key vault in `:app`
now uses these versions independently; see `docs/DIRECT_SSH.md` for its separate
evidence and limits. `settings.gradle.kts` includes this module only with
`-PsshSpike`.

## What the checks establish

1. An Android Keystore P-256 key with `encoded == null` can authenticate to an
   independent SSH server. The adapter converts Keystore's DER ECDSA signature to
   the SSH signature format. Exec output, PTY dimensions, resize messages and
   Unicode input cross an actual encrypted connection.
2. Unknown and changed host keys produce their specific JSch exceptions before
   client signing. The changed fixture uses a different Ed25519 key, matching the
   real server's algorithm. Installing the correct pin then allows the same
   signer/endpoint to authenticate.
3. Encrypted OpenSSH Ed25519 and P-256 imports reject the wrong passphrase and
   authenticate with the right one.
4. A 180,000-byte SFTP upload/download matches byte for byte, including a Unicode
   filename; rename/delete work and a nonempty directory cannot be removed.
5. Two nested direct-tcpip forwarding hops authenticate independently; closing
   the outer session tears down both descendants and a waiting exec channel.

The fixture runs on host loopback with generated credentials, synthetic commands
and SFTP confined to a temporary directory. It accepts valid public-key proofs for
a random fixture username. It has no host shell or personal credentials. Forwarding
is restricted to its own loopback SSH listener and a generated loopback TCP peer
which deliberately sends no greeting (used by production transport timeout tests).
This verifies engine mechanics,
not a production command runner or general ProxyJump policy.

## Run

Create a Python environment in an ignored directory and start the fixture in a
separate terminal. Keep it running until tests finish:

```sh
python3 -m venv captures/runtime/ssh-engine/venv
captures/runtime/ssh-engine/venv/bin/pip install 'asyncssh[bcrypt]==2.24.0'
captures/runtime/ssh-engine/venv/bin/python scripts/ssh-engine-fixture.py
```

The fixture writes generated test assets under `ssh-spike/build/fixture-assets`.
Restarting it changes the server key, username and port, so **rebuild the test APK
after every fixture restart**. Never commit its generated assets or APKs.

Build before starting the emulator on low-memory Macs:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/Users/dharmaydave/Library/Android/sdk \
./gradlew -PsshSpike --no-daemon --max-workers=1 \
  :ssh-spike:assembleDebug :ssh-spike:assembleDebugAndroidTest
```

Then boot the desired emulator and run:

```sh
python3 scripts/check-ssh-engine.py --serial emulator-5554 \
  --output captures/runtime/ssh-engine/result
```

The runner refuses physical devices, verifies emulator boot, installs only the
separate experiment packages, uses ADB reverse for the fixture port, and removes
that reverse on exit. It records APK hashes, API/page size and the instrumentation
result. API 26 lacks `getconf`, so its page-size receipt uses the process's own
`smaps` page-size fields without saving memory addresses or paths. Stop the fixture
with Ctrl-C and stop the owned emulator when finished.

The production transport has a separate runner:
`scripts/check-ssh-transport.py --serial emulator-5554 --output <ignored-directory>`.
Build `:app:assembleDebug :app:assembleDebugAndroidTest` before starting the
emulator. This runner installs the debug app and its instrumentation only on an
emulator, supplies public fixture coordinates at runtime, and requires all fourteen
transport tests to finish without skips. Unlike this spike's embedded assets,
those public arguments do not require rebuilding after a fixture restart.
Use `--ui` for the four saved-host UI checks, or `--shell-ui` for the three
production Ghostty shell-screen checks. Each mode enforces its expected test count
and rejects skips. The shell fixture supports ANSI/Unicode, bracketed paste,
cursor queries, alternate screens, failed reconnect feedback/new PTYs and remote
shell-count checks; it never executes
input as host commands. Optional `CMUX_SSH_TRACE=1` logs lifecycle event types and
active shell counts without terminal input or credentials.

On the development Mac, retain only the existing `cmux_api37_16k` AVD and its
system image. The redundant API 37 and API 26 AVDs/images were removed at the
user's request to save storage. Earlier API 26 receipts remain historical evidence;
they do not establish API 26 coverage for later transport changes. Do not recreate
additional emulators as a routine part of development.

## Limits and next integration work

- Emulator Keystore success is not evidence of hardware-backed storage, biometric
  authorization or key invalidation on the Pixel. Test those separately.
- This test adapter is deliberately not the production key manager. Implement
  secure imported-key persistence, backup exclusions, deletion, biometric prompts
  and credential lifetime before integrating account/host UI.
- The BC lightweight implementations are selected explicitly for Ed25519/XDH;
  no Android-wide security provider is replaced. Production configuration must
  avoid unrelated process-global JSch configuration mutations.
- Synthetic shell and same-server hop checks do not cover real tmux/cmux-tui,
  shell quoting, multi-host trust prompts, host edits, transfer cancellation,
  authentication-time cancellation, networking handoff, or long-lived reconnect.
- A production session should support nested channel streams directly, or give
  temporary forwarding listeners an explicit bounded lifetime and owner.
- The app now bundles the complete dependency notices with its key-vault work.
  This experiment establishes compatibility for these exact versions; it does
  not select every production algorithm policy or prove the key-vault integration.

Primary references: [JSch][jsch], [Android Keystore][keystore],
[AsyncSSH][asyncssh]. The complete feature scope remains in
[DIRECT_SSH.md](../docs/DIRECT_SSH.md).

[jsch]: https://github.com/mwiede/jsch/tree/jsch-2.28.0
[keystore]: https://developer.android.com/privacy-and-security/keystore
[asyncssh]: https://asyncssh.readthedocs.io/en/latest/

The same production runner accepts `--ui` for four host-screen checks: creating
and connecting a host through a visible fingerprint prompt; changed-key refusal
and replacement; retaining an editor through key management and a connection
through rename; and rejecting an obsolete restored editor after a route change.
It uses isolated temporary host/key stores and does not sign out an existing app
account. Keep using the single retained `cmux_api37_16k` emulator.

## Real tmux workspace fixture

With `tmux` installed, use the existing AsyncSSH venv to start an independent
loopback SSH server backed by a unique private tmux server. It uses an empty HOME,
explicit config and `/bin/cat` panes. SSH requests are restricted to approved tmux
argument vectors; no SSH-provided shell command is executed. It does not touch the
user's tmux socket or shell configuration.

```sh
captures/runtime/ssh-engine/venv/bin/python scripts/ssh-tmux-fixture.py \
  --tmux /absolute/path/to/tmux --output captures/runtime/tmux-workspaces/fixture.json
```

Build debug/test APKs with the emulator stopped, then boot the retained AVD:

```sh
python3 scripts/check-ssh-transport.py --serial emulator-5554 \
  --tmux-ui --fixture captures/runtime/tmux-workspaces/fixture.json \
  --output captures/runtime/tmux-workspaces/result
```

The runner requires four tmux UI checks without skips and records exact APK hashes:
workspace actions/rendering, account retirement, visible drop/manual recovery, and
saved pane restoration against a fresh runtime with persisted Disconnect respected.
Stop the fixture with Ctrl-C; it removes its private server and temporary HOME.
The checks install only on an emulator and use isolated host/key storage.

## Real cmux-tui relay check

`SshCmuxProcessTest` accepts an explicitly supplied executable. The recorded run
used the official npm `cmux-tui-darwin-arm64` 0.13.4 tarball, verified against its
registry SHA512 integrity before extracting `package/bin/cmux-tui`. Exact version,
integrity and observed compatibility gaps are in [DIRECT_SSH.md](../docs/DIRECT_SSH.md).
The test neither downloads nor installs a binary automatically.

With the emulator stopped:

```sh
CMUX_TUI_TEST_BINARY=/absolute/path/to/verified/cmux-tui \
CMUX_TMUX_TEST_BINARY=/absolute/path/to/tmux \
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest --rerun \
  --tests '*SshCmux*' --tests '*SshTmux*'
```

The cmux-tui fixture uses a short unique `/tmp/ct-*` HOME and session, private
runtime/config/data directories, and a `/bin/cat` terminal. It verifies actual
relay replay/input/resize/detach, stable-resource reattachment and explicit
terminal termination. Cleanup resolves the exact fixture session, closes its
terminal resources, stops the owner and performs confirmed state reset before
deleting that directory. If cleanup fails, inspect the retained fixture path;
never erase its state while its terminal hosts are alive. No personal cmux socket,
configuration or workspace is used. This is a JVM process check; it does not prove
Android SSH transport/UI or physical-device acceptance.

The process check also exercises production `SshCmuxRemote` discovery commands,
using a symlink at the fixture HOME's `.local/bin/cmux-tui` to avoid copying the
binary. It connects only to the uniquely named fixture socket. After attachment,
it restarts that owner, verifies its generation changed and resolves the saved
durable selection against a fresh typed inventory before checking replay history.
The complete focused command above currently runs 40 checks when both opt-in
executables are supplied; absent opt-ins produce skips rather than real-server
verification.

The provider checkpoint extends that focused command to **45 checks**. The real
process uses provider-owned durable creation and guarded end-workspace actions,
including refusal when the confirmation's content set changed.

For the standalone cmux-tui renderer component checks, build the debug/test APKs
with the emulator stopped, then boot the same retained AVD and run:

```sh
python3 scripts/check-ssh-transport.py --serial emulator-5554 --cmux-renderer \
  --output captures/runtime/cmux-tui/provider-android
```

This mode needs no SSH fixture or reverse port. It requires exactly three successful
instrumentation checks, records API/page size/APK hashes and rejects skips. The
production control/renderer/shared terminal screen receive deterministic wire
events; it must not be cited as an end-to-end Android SSH/cmux-tui check. Native
Ghostty checkpoint 36920005789 or a fresh matching build is needed after the
single-cell JNI change; do not bypass the native source/artifact gate.
