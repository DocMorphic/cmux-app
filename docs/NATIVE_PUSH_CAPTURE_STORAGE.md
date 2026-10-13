# Native capture storage — 2026-10-11

## Stable paused and signed-out heartbeats — 2026-10-13

Live inspection found the installed helper changing processes while the Mac was
signed out. Source review and an executed reproducer identified unconditional
capture-controller retirement for every signed-out or forwarding-disabled host
observation. An authenticated paused Mac could issue a pairing offer, then lose
it on the next heartbeat because its helper was killed and replaced.

The controller now retires on the first denied/paused observation, a new denial,
changed native epoch/scope, or a transition from forwarding to paused. Repeated
observations of the same denied/paused state refresh its lease without replacing
the helper. Explicit invalidation, privacy tightening and account/team retirement
retain their existing fences. Initial authenticated/forwarding observations still
permit recovery from encrypted storage under fresh matching native authority.

The actual compiled Swift owner and production Node helper reproduce immediate
retirement before the fix (`red-ready/behavior.log`, assertion 121). After the
fix, the owner fixture passes on Node 26 and minimum Node 22: three repeated paused
observations preserve the running helper and its pairing session; three signed-out
observations preserve the denied helper, whose ready control endpoint refuses
pairing. Existing privacy replacement, explicit invalidation, descriptor refresh,
encrypted capture, awaited stop and late-preparation cases also pass. All **nine**
Node 26 recovery cases pass, including lost-receipt/SIGKILL, scope ABA, sign-out,
held restore, backpressure and acknowledged-source metadata.

Evidence/source hashes are ignored under
`captures/runtime/native-helper-heartbeat-20261013/`. These are real processes and
encrypted storage with synthetic loopback configuration, not a user's native
account, Pixel or FCM. The installed app and active build 38433167598 predate this
controller fix; it needs the next verified custom Mac artifact. That existing
build continues unchanged to deliver the separate sign-in correction.

Current pairing UI extends the installer to **22 files / eight audited originals**.
See [NATIVE_PUSH_PAIRING.md](NATIVE_PUSH_PAIRING.md); earlier counts below describe
their respective checkpoints. The full tagged Mac/Pixel/provider gates remain open.

Current native phone pin integration extends the installer to **17 files / seven
pins**. Enrollment/renewal/provider authorization require current native phone key
trust; changed pins fence the old helper. See NATIVE_PUSH_TRUST.md for executed
upstream-store/Swift/helper evidence and the remaining acceptance gates. Earlier
checkpoint counts below describe their respective batches.

## Owner integration and restart identity checkpoint

Original captures now commit before fresh host descriptor preparation. Held
preparation/stop and all nine recovery cases, including full-store backpressure,
pass with the current controller. See [NATIVE_PUSH_MAC_KEY.md](NATIVE_PUSH_MAC_KEY.md)
for the reproduced failure and exact current batch evidence.

The production source owner now uses `NativePushSourceCaptureController`: verified
selected-team scope is supplied by the actual native hooks, original captures commit
before helper transmission, and exact durable capture receipts schedule storage
removal. Temporary invalidation retains work; explicit auth/team retirement clears
old captures/transport. Storage work is joined during stop. Current installer scope
is 16 files / six audited originals, including explicit controller membership.

Actual Swift owner→production helper checks pass on Node 22/26 for nine recovery
cases: committed-but-receipt-lost SIGKILL, temporary unavailability, selected-team
mismatch despite `teamID:null`, team A→B→A, explicit sign-out, held startup/stop,
team transition during restore, a full 128-record store, and restart after all
captures were already acknowledged. Full journal backpressure retains the new
native original; acknowledged work keeps its original helper source identity.

The last case failed before the fix in regression commit `b67d7483`: after restart,
the real helper accepted a new source epoch and could retire its pending work.
Encrypted source-context metadata now survives the last receipt and commits before
any host/capture transmission. Only fresh matching native account/selected-team/Mac
state can reuse it. Confirmed retirement clears it; it supplies no lease or
transport credentials and never renews event expiry. Older version-1 files without
metadata remain readable.

Eight storage scenario groups, production-owner checks on both Node runtimes, and
current mapping/auth/receipt/installer/launch checks pass. These use synthetic keys
and identities with real local TLS/SQLite/CryptoKit; no enrolled recipient or Google
request. Actual Keychain access, whole custom app and Mac/Pixel/FCM acceptance remain
unverified. Evidence: `captures/runtime/push-native-recovery/final-node26/`,
`final-node22/`, `final-storage/`, `final-hooks/`, `final-owner-node26/`,
`final-owner-node22/`, and preserved `acknowledged-red/`/`acknowledged-green/`.

```sh
python3 scripts/check-native-push-recovery.py --node /absolute/path/to/node
```

`NativePushCaptureRepository` is an implemented actor for encrypted producer
captures that await a durable helper receipt. `NativePushCaptureKeyStore` supplies
an independent AES-256 key for the custom bundle through the macOS Keychain. The
pinned installer wires both files explicitly into the app target; current scope
is **16 changed files / six audited originals**.

## Implemented storage contract

- Construction is passive. File and key operations run in the repository actor.
- The store contains only original capture bodies, admission time, original expiry,
  record IDs and original verified account/team/Mac context. Host controls, live
  leases, pipe authentication keys and transport sequences are never persisted.
- At most 128 records / 8 MiB of bodies; individual bodies at most 1 MiB. A full
  store rejects new work without evicting unexpired captures. Expired work releases
  capacity; no operation extends an original event's 120-second lifetime.
- AES-GCM uses a fresh nonce and authenticates the version/domain plus the owner
  directory. The encrypted file is bounded to 12 MiB, including base64/metadata.
- Writes use a private 0600 exclusive temporary file, file fsync, atomic rename
  and directory fsync inside an owned 0700 directory with a private owned parent.
  Unsafe files, links and permissions are rejected. Temporary files contain only
  ciphertext and are removed by normal completion/failure cleanup.
- Exact duplicate IDs/bodies preserve the original capture/expiry. Conflicting
  IDs, malformed bodies, corruption and missing/wrong keys fail with coarse errors
  while preserving existing storage. Receipt removal uses exact IDs.
- Reads never create a replacement key. The Keychain loader may create a key only
  when no ciphertext exists; it preserves an existing key and handles concurrent
  creation by reading the winner. Keys are separate from native push encryption
  and from the helper sender/storage keys.

One native owner must own the repository directory. Actor serialization governs
that owner's IO; this is not a multi-process transactional database.

## Executed evidence

```sh
python3 scripts/check-native-push-captures.py
```

The actual repository and Keychain implementation compile in Swift language mode
6 with the installed Swift compiler. A fixture commits an encrypted capture,
intentionally SIGKILLs itself, and a new process restores its exact body/context/
expiry. Eight current scenario groups also cover duplicate/conflict handling, exact receipt
removal, expired slots/count/byte limits, no renewed expiry, absent-store passivity,
wrong/missing keys, authenticated corruption, ciphertext transplant, private modes,
symlink/hard-link refusal, rejecting host controls, and preserving/clearing source
context after the last receipt. Encryption/storage are real
CryptoKit/filesystem operations. The key closure is synthetic; **actual Keychain
access was not executed**. Latest logs: ignored `captures/runtime/push-native-recovery/final-storage/`.

Current patched Swift/project/Bash parsing and the actual auth projection/payload
mapping/ten receipt groups/private-pipe journal interop pass in
`captures/runtime/push-native-captures/hooks/`. Four installer cases and eight
tagged-launch environment cases pass. No whole app, provider or phone was launched.

## Acceptance still required

1. Build/link the full tagged custom Mac app, sign in separately, verify its actual
   Keychain loader and native lifecycle with the configured source owner.
2. Provision trusted HTTPS and private production runtime configuration. Verify
   owner enrollment and native peer/key revocation with the real account.
3. Build the configured signed Android milestone; verify retained sign-in/pairing
   and Firebase resources in that exact APK.
4. Verify real Mac/Pixel foreground/background/process-death/Doze delivery,
   token rotation, notification taps/replies and revocation. Local fixtures do not
   establish those workflows.

Full Xcode and trusted HTTPS remain pending owner setup; the goal remains active.
