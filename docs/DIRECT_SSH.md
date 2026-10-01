# Direct SSH parity and implementation plan

## Status and evidence boundary

**Android direct SSH is not yet available through the app UI.** Production host
storage, private-key storage and the low-level transport are implemented as
described in the checkpoints below. Existing native Mac pairing, terminal
rendering, Files and browser tunnel tests do not prove SSH/SFTP support. This audit
establishes the required behavior at upstream candidate
`204a11dfcc76280205e50406ab94270a1c152155`; it does not advance the broad implemented
reference or establish which App Store/TestFlight binary contains these features.

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
