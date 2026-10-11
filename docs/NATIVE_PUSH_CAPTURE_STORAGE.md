# Native capture storage — 2026-10-11

## Owner integration checkpoint

The production source owner now uses `NativePushSourceCaptureController`: verified
selected-team scope is supplied by the actual native hooks, original captures commit
before helper transmission, and exact durable capture receipts schedule storage
removal. Temporary invalidation retains work; explicit auth/team retirement clears
old captures/transport. Storage work is joined during stop. Current installer scope
is 16 files / six audited originals, including explicit controller membership.

Actual Swift owner→production helper checks pass on Node 22/26 for committed-but-
receipt-lost SIGKILL recovery, fresh selected-team mismatch, temporary unavailability,
team A→B→A, held startup/stop/team transition, and a full 128-record store. The helper
retains one original journal identity for replay; full source-journal backpressure
leaves a new native original durably retained. Classic process supervisor and current
mapping/receipt/installer/launch checks pass. These use synthetic keys/identities,
real local TLS/SQLite/CryptoKit and no enrolled recipient/Google request.

**Still open at this checkpoint:** when all native captures were already acknowledged
but helper delivery remains pending, source identity must also survive restart.
This metadata boundary is being implemented next; the current recovery proof covers
unacknowledged native originals. Full app/Keychain/Firebase/Pixel acceptance remains
unverified. Evidence is `captures/runtime/push-native-recovery/`.

```sh
python3 scripts/check-native-push-recovery.py --node /absolute/path/to/node
```

`NativePushCaptureRepository` is an implemented actor for encrypted producer
captures that await a durable helper receipt. `NativePushCaptureKeyStore` supplies
an independent AES-256 key for the custom bundle through the macOS Keychain. The
pinned installer wires both files explicitly into the app target; current scope
is **15 changed files / six audited originals**.

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
expiry. Seven scenario groups also cover duplicate/conflict handling, exact receipt
removal, expired slots/count/byte limits, no renewed expiry, absent-store passivity,
wrong/missing keys, authenticated corruption, ciphertext transplant, private modes,
symlink/hard-link refusal and rejecting host controls. Encryption/storage are real
CryptoKit/filesystem operations. The key closure is synthetic; **actual Keychain
access was not executed**. Logs: ignored `captures/runtime/push-native-captures/current/`.

Current patched Swift/project/Bash parsing and the actual auth projection/payload
mapping/ten receipt groups/private-pipe journal interop pass in
`captures/runtime/push-native-captures/hooks/`. Four installer cases and eight
tagged-launch environment cases pass. No whole app, provider or phone was launched.

## Integration still required

The earlier storage-only checkpoint below predates the owner integration above.
Neither standalone storage SIGKILL recovery nor project membership alone proves
production notification recovery.

The next owner integration must:

1. Derive stored account and selected-team context from the actual verified native
   identity; the account-scoped wire `teamID:null` cannot stand in for selected-team
   verification. Select the repository/key loader at the composition root.
2. Commit original captures outside the main actor before their helper transmission,
   retain exact capture-to-record IDs, and remove storage only after durable receipts
   or explicit native retirement. Await pending owned IO during stop/replacement.
3. Require freshly verified native account/team/Mac state before replay. Stored
   context never establishes authority. Distinguish temporary unavailability from
   confirmed auth/team/policy retirement and cancel stale restoration work.
4. Preserve original correlation, expiry and source identity across a lost receipt.
   `PushSourceJournal.accept()` keys records by source epoch plus correlation and
   fingerprints the full request; blindly assigning a new epoch creates a new
   identity. Conversely `NativePushSourceAdapter.accept()` retires an old epoch
   against a fresh host. Resolve this boundary with actual producer/journal behavior
   and tests before enabling recovery; do not skip either gate.
5. Verify producer/helper committed-but-receipt-lost cases, privacy/auth/team ABA,
   expiry, storage failure/backpressure and whole tagged app lifecycle. Then verify
   actual Mac/Pixel delivery with the configured Firebase provider.

Full Xcode and trusted HTTPS remain pending owner setup; the goal remains active.
