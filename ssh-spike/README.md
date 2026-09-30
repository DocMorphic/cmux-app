# Android SSH engine experiment

This opt-in test application evaluates JSch **2.28.0** with Bouncy Castle
**1.86** on Android. It is not part of `:app`, contains no production SSH UI and
must not be published as the cmux companion. Both dependencies are confined to
the instrumentation APK. `settings.gradle.kts` includes the module only with
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
is restricted to its own loopback SSH listener. This verifies engine mechanics,
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
- Review licenses/dependency notices and current engine advisories before adding
  either library to the delivered app. The experiment establishes compatibility
  for these exact versions; it does not select every production algorithm policy.

Primary references: [JSch][jsch], [Android Keystore][keystore],
[AsyncSSH][asyncssh]. The complete feature scope remains in
[DIRECT_SSH.md](../docs/DIRECT_SSH.md).

[jsch]: https://github.com/mwiede/jsch/tree/jsch-2.28.0
[keystore]: https://developer.android.com/privacy-and-security/keystore
[asyncssh]: https://asyncssh.readthedocs.io/en/latest/
