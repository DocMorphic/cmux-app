# Direct SSH parity and implementation plan

## Status and evidence boundary

**Android direct SSH is not implemented.** Existing native Mac pairing, terminal
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
No SSH dependency has been added to the delivered app.

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
