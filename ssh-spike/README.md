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
emulator, supplies public fixture coordinates at runtime, and requires all thirteen
transport tests to finish without skips. Unlike this spike's embedded assets,
those public arguments do not require rebuilding after a fixture restart.
Use `--ui` for the four saved-host UI checks, or `--shell-ui` for the two
production Ghostty shell-screen checks. Each mode enforces its expected test count
and rejects skips. The shell fixture supports ANSI/Unicode, bracketed paste,
cursor queries, alternate screens and remote shell-count checks; it never executes
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
