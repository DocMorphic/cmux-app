# Native phone pin lifecycle — 2026-10-11

## Implemented boundary

Native Mac descriptor binding and running-owner refresh are now implemented in
[NATIVE_PUSH_MAC_KEY.md](NATIVE_PUSH_MAC_KEY.md). Its current 192-check helper runs
extend the 189-check phone-pin checkpoint recorded below. Physical Mac key rotation
and full native/Pixel delivery remain open.

The custom native producer now includes `trustedPhones` in each authenticated ready
host snapshot. Each row contains only the Android build label, phone installation
ID, key ID and public key. The native hook reads the audited `PhonePushPeerKeyStore`
v2 public schema and filters exact account, account-scoped `teamID:nil`, Mac device,
instance and bundle identity. It includes only the release/debug Android packages.
Selected-team verification and generation still govern the source epoch.

The native key-exchange completion explicitly refreshes this projection. Changed
pins synchronously invalidate the old helper before sending a replacement host
snapshot. Reset-all/configuration reconciliation also publishes fresh host state;
the existing ten-second heartbeat supplies recovery. Invalid/oversized registry
state fences the source; unrelated or malformed rows grant no phone trust.
Conflicting aliases are refused. No private phone or native Mac key is exported.

The helper now requires the matching native phone pin for enrollment begin/finish,
notification preparation, queue admission, actual provider authorization (including
after OAuth), and registration renewal. Both key ID and public key must match;
an independently enrolled helper registration alone cannot authorize delivery.
Missing legacy trust projection is temporary unavailability, retaining original
work/deadlines; an explicit empty list denies phone trust. Authenticated phone-key
cleanup remains allowed under fresh matching native host identity after pin removal.
Offers are still issued only through the host-local control socket.

The reusable forwarder has an injected synchronous recipient-trust callback; the
production helper supplies the strict native predicate. Generic forwarder consumers
retain their existing host-authority contract. The native source decoder and Swift
receipt queue validate bounded, unique, canonical public pin rows. No event or
incoming phone request can write the native host snapshot.

## Reproduced and executed evidence

Regression commit `dd625cfa` contains two failures observed before repair:

- A valid owner offer without a native pin returned HTTP 200 instead of 403.
- Loss of native phone trust during held OAuth still contacted the injected FCM
  transport once instead of zero times.

The same focused command passes after repair:

```sh
node --test --test-name-pattern='native phone (pin|trust)' push/helper-service.test.mjs
python3 scripts/check-native-push-trust.py --upstream /absolute/path/to/pinned/upstream-cache
```

The actual upstream peer store, storage implementation, payload types, actual
injected trust hook, mapper and Swift delivery queue compile and execute in Swift
language mode 6. Fixture storage contains only synthetic public pins. Checks cover
six unrelated scopes, rotation/removal fencing, corrupt rows, conflicting aliases,
registry bounds, validated snapshot admission and atomic rejection of duplicate pins.
The initial fixture compile attempt used throwing calls inside `precondition`
autoclosures; those fixture errors were fixed and preserved as diagnostics.

The helper suite additionally covers removal, replacement public key under the same
key ID, key-ID replacement, changed build label, enrollment retry/renewal refusal
and authenticated cleanup. Current complete `push/*.test.mjs` runs on Node 22/26
report 189 tests: 188 passed, one existing optional upstream decryptor check skipped
because its external reference fixture was not selected. No failures. No live
Google/FCM request was made; TLS/SQLite/CryptoKit operations are real, provider
transports and account/phone identities are synthetic.

Current installer scope: **17 changed files / seven audited originals** at
`f4b1509054949eaad5d695569ad443c4c18ed68d`. Added original:
`Sources/Mobile/MobileHostService+PhonePushKeyExchange.swift`, SHA-256
`09a9804a0176f1dc31f64fb7944fb99473ed1470542a519cd8ac3b14df5062cf`.
Four installer refusal/rollback cases and eight launch-environment cases pass;
current edited-source/project/Bash parsing and auth/payload/receipt execution pass.

Ignored evidence: `captures/runtime/push-native-trust/`, including preserved red/
green logs, both complete Node runs, upstream source hashes, compiled native receipt,
current hook checks and production-owner run.

## Acceptance still open

Standalone compilation/execution does not establish the full custom app, actual
user Keychain/defaults, physical RPC exchange, live enrollment, FCM/Doze/token/reply
workflows, or native Mac sender-key rotation across restart. Verify those against
the tagged Mac app and configured signed Pixel APK. Full Xcode, trusted HTTPS and
the physical device remain prerequisites. The goal remains full iOS parity.
