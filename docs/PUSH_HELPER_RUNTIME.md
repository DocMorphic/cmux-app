# Production push helper startup — 2026-10-11

A manual full Mac build on a GitHub ARM64 runner is now available:
[MAC_NOTIFICATION_BUILD.md](MAC_NOTIFICATION_BUILD.md). Local Xcode is optional
for producing that artifact; live TLS/runtime/Pixel acceptance remains required.

Current Mac pairing UI and installer scope (**22 files / eight pins**) are in
[NATIVE_PUSH_PAIRING.md](NATIVE_PUSH_PAIRING.md). Older checkpoint counts below
remain historical. Native Mac descriptor binding is in NATIVE_PUSH_MAC_KEY.md.

Current native phone pin integration extends the installer to **17 files / seven
pins**. Enrollment/renewal/provider authorization require current native phone key
trust; changed pins fence the old helper. See NATIVE_PUSH_TRUST.md for executed
upstream-store/Swift/helper evidence and the remaining acceptance gates. Earlier
checkpoint counts below describe their respective batches.

The native source owner now integrates encrypted capture commit/receipt retirement
and freshly verified scope recovery. Current installer: **16 files / six pins**.
Encrypted source-context metadata survives the last receipt so helper work can
resume with its original identity after restart. Nine production-helper recovery
cases pass on Node 22/26, plus eight storage groups and both actual owner runs.
See NATIVE_PUSH_CAPTURE_STORAGE.md for the reproduced acknowledged-source regression
and exact evidence. Actual Keychain, whole tagged app and live delivery remain open.

## Current provisioning and tagged launch checkpoint

Independent sender/registration/source/outbox keys are provisioned privately at
`/Users/dharmaydave/.config/cmux-app/push-owner-keys`. All four files are 32 bytes,
0600, in a 0700 directory. `helper.json` contains only the matching public X25519
descriptor and independent random identities. The reusable setup command is:

```sh
node scripts/provision-push-owner-keys.mjs --directory /absolute/private/parent/push-owner-keys
```

The parent must already be private and owned. A rerun verifies and preserves every
key and identity; incomplete/unsafe material is refused without regenerating it.
Eight focused cases passed on Node 26.8.2 (245.40775 ms) and 22.16.0
(255.745334 ms), including a changed public descriptor, nonprivate parent and
symlink/hard-link/incomplete material. The real owner command succeeded twice:
first provisioned, then verified/preserved. No private bytes were printed.

The production CryptoKit sealer built successfully as a Mac arm64 executable.
This build establishes compilation, not actual notification encryption/delivery
with these provisioned keys. No listener or provider request was started.

Regression `7c2fc140` reproduced the tagged upstream launcher's `env -i` dropping
the three explicitly selected owner paths. The installer now patches `reload.sh`
to forward exactly those paths and refuse partial/relative configuration. The
same executed launch fragment passes eight cases after the fix; no app/helper
is launched by that fixture. Current installer scope is **13 files / six audited
originals**, including reload SHA-256
`b42199aa1416b20b51f48cc507e3bc0595347fcacf0fb470fb302912498d5216`.
Four installer refusal/rollback cases and current edited Swift/project/Bash
parsing, actual auth/payload mapping and compiled receipt-queue interop also pass.

```sh
python3 scripts/check-native-push-launch.py --upstream /absolute/path/to/pinned/upstream-cache
```

Current prerequisites observed on this Mac: full Xcode is absent (only Command
Line Tools selected), approximately 16 GiB disk space is free, Tailscale is running
with MagicDNS but supplies no certificate domains, and ADB reports no phone.
The owner is asked to install/open Xcode and enable HTTPS Certificates in Tailscale
DNS settings; both remain pending. Do not expand another checkout/download native
build dependencies until Xcode and sufficient storage are available. No Android
emulator or Gradle process was started in this batch.

Use one stable tag (`android-push`) for the eventual build. A local-backend-only
build does not establish production account/Cloud acceptance. The reviewed pinned
upstream supports `--prod-auth` for this tagged account test; it selects production
Stack auth, cmux APIs and the broker without copying the official app's credentials:

```sh
CMUX_DEV_BACKEND_MODE=local CMUX_RELOAD_NO_GLOBAL_CLI_LINKS=1 ./scripts/reload.sh --tag android-push --prod-auth
```

Only launch after the private runtime configuration, trusted hostname certificate
and native setup are ready. Pass the three explicit owner paths to the patched
reload's `--launch`. Preserve the official running cmux and existing phone app data.
Full app build/linking, tagged sign-in, actual Keychain publication, configured
Android milestone and live FCM delivery are still unverified.

Evidence: ignored `captures/runtime/push-native-owner/owner-keys-node22.log`,
`owner-keys-node26.log`, `sealer-production-build.log`, `tagged-launch-red.log`,
`tagged-launch-green.log`, and `launch-hooks/`.

`scripts/cmux-push-helper.mjs` now composes the implemented native source channel,
durable encrypted stores, CryptoKit sealer, bound private service-account token
provider, FCM transport and HTTPS enrollment/maintenance. `helper-runtime.mjs`
owns their startup/cleanup. Import/construction is passive. Explicit startup reads
the owner's private configuration/keys; it does not obtain OAuth until actual
authorized work requires it.

The CLI requires private pipe/socket stdin and stdout. Stdout is exclusively native
receipts; diagnostics use a fixed coarse stderr message. It does not read defaults
from gcloud, environment credentials or incoming phone requests. Source EOF or
explicit stop fences admission and closes both listeners, awaits component work,
then clears the runtime's owned secret buffers. Auth/privacy transitions in the
native process sink retire the owned child; a provider request already submitted
cannot be recalled.

## Owner configuration

Generate the configuration with the explicit local setup command after obtaining
the hostname certificate. The containing directory of `--output` is a dedicated
runtime root; its parent must already be private and owned. Keep it separate from
the owner key directory. Example paths below are placeholders, not provisioned files:

```sh
node scripts/provision-push-runtime.mjs \
  --keys /absolute/private/parent/push-owner-keys \
  --firebase /absolute/private/firebase/google-services.json \
  --provider /absolute/private/firebase/provider-service-account.json \
  --tls-key /absolute/private/tls/hostname.key \
  --tls-cert /absolute/private/tls/hostname.crt \
  --hostname mac.example.ts.net --listen 100.64.0.10 --port 58468 \
  --sealer /absolute/trusted/cmux-push-seal \
  --output /absolute/private/parent/push-runtime/config.json
```

The command binds exactly one release/debug Firebase client to the selected
project/sender and validates the selected service account without OAuth. Offline
TLS checks cover the private key, hostname SAN, server purpose, validity dates,
chain signatures and a runtime public root. The CLI has no custom-root bypass.
Android's actual connection still must verify the certificate. Select an explicit
IP listener; wildcard addresses are refused. Runtime/native output cannot overlap
key material, including through filesystem aliases. A rerun preserves existing identities, keys, file bytes and inode
when parsed configuration matches, including different JSON field order. Changed
or unsafe configuration fails without replacing the existing file.

Setup writes only private paths/public metadata; it does not publish a native
descriptor, create authority, start listeners or contact Firebase. Native public
key publication and account authority still come from the running custom app.
Nine focused groups passed on Node 26; the Node 22 full helper suite passed
200 of 201 checks with the existing optional reference-decryptor skip. Eight initial
setup groups and the initial 199/200 suite also passed on Node 26. Evidence
is in ignored `captures/runtime/push-runtime-setup/node22.log`. This establishes
setup behavior using isolated certificates/provider fixtures, not live delivery.

Configuration is a private JSON file (0600, containing directory 0700) with exactly
these top-level fields. Paths are absolute and selected by the trusted Mac owner.
Secret files must also be private, regular, singly linked, bounded files. Symlinks
and nonprivate permissions are rejected. Keep them outside the repository/APK.

| Field | Content |
| --- | --- |
| `version` | `1` |
| `stateDirectory` | Private encrypted-store root. Use a short path; the resulting Unix socket path must fit 100 UTF-8 bytes. |
| `helper` | Independent public descriptor: `version:1`, `algorithm:"rawX25519"`, `installationID`, `keyID`, canonical base64 32-byte `publicKey`. |
| `nativeDescriptorPath` | Private owner-published **public** native descriptor JSON. Reloaded for each offer/admission check. Publish from the actual custom app's current push key; never use a phone-supplied descriptor. |
| `keys` | Four private file paths: `sender`, `registrations`, `source`, `outbox`. Each file contains exactly 32 raw bytes. `sender` is the helper's independent X25519 private key; its derived public key must match `helper.publicKey`. |
| `tls` | Private PEM file paths `key` and `cert`. Provision a certificate valid for the advertised hostname and trusted by Android. |
| `provider` | `path` to the private service-account JSON, exact `projectID`, exact `accountEmail`, and Firebase `sender` project number. See FIREBASE_SETUP.md for the provisioned project. |
| `clients` | Declared `{phoneBuildID, application}` pairs. `application` is the corresponding Firebase Android app ID, not the package name. |
| `endpoint` | Exact advertised HTTPS URL ending `/v1/push/enroll`, without credentials/query/fragment. |
| `listener` | Explicit `{host, port}`. Port zero is for local fixtures; their advertised endpoint also uses zero and is replaced with the bound port. |
| `sealerExecutable` | Absolute trusted path to `cmux-push-seal`, built by `scripts/build-push-seal.sh`. |

Configuration contains no cmux account/team authority. Only fresh authenticated
native host frames establish that authority. A stored registration or provisioned
provider credential cannot start delivery by itself. The helper's sender and all
three native public-descriptor identity fields must remain independent.

The trusted native owner launches the absolute Node executable with arguments
`[absoluteHelperScript, "--config", absoluteConfigurationFile]`, a minimal
environment and private stdin/stdout. The pinned installer now injects the
`NativePushSourceOwner` at `AppDelegate.configure(...)`. Explicit enabling uses
three nonsecret paths in the tagged Mac app's environment:

| Variable | Absolute owner-selected path |
| --- | --- |
| `CMUX_ANDROID_PUSH_CONFIG` | Private configuration JSON described above |
| `CMUX_ANDROID_PUSH_NODE` | Trusted Node executable |
| `CMUX_ANDROID_PUSH_HELPER` | `scripts/cmux-push-helper.mjs` |

With no configuration variable the owner is absent. Construction/start without a
fresh native observation performs no publication/helper launch. Before **each**
launch, `NativePushOwnerDescriptorRepository` runs file/Keychain projection outside
the main actor, publishing the actual tagged bundle's public descriptor atomically
as a private file. It rejects extra/private fields and unsafe targets. The process
sink cancels and joins retired preparation before replacement; awaited stop also
joins preparation. Full custom app build/runtime verification is still required.

## Local pairing control

The runtime binds `stateDirectory/control/owner.sock` in a private directory, with
socket mode 0600. It preserves existing active owners and ordinary files. After a
native SIGKILL/reap, it may remove only an owned private socket whose connection
probe confirms no listener and whose inode/device/mode/timestamps remain unchanged.
Timeouts, permission failures or changed filesystem objects fail closed. Stop
cancels/joins the probe; it cannot unlink the socket after shutdown admission closes.
This is separate from phone HTTPS and the authenticated native source pipes.
Commands are one bounded NDJSON request, exactly `{action,value}`:

- `offer`: value is a declared Android build/package identifier. The service
  requires a fresh native lease and returns a short-lived, epoch-bound offer.
- `cancel`: value is an offer ID.
- `resume`: value is `credentials` or `rejected`, after the owner fixes that issue.

For explicit local setup:

```sh
node scripts/cmux-push-owner.mjs offer --socket /absolute/private/state/control/owner.sock io.github.docmorphic.cmuxapp
```

That explicit command prints the private offer JSON for the existing Android
helper-pairing flow. The offer contains a short-lived pairing secret; the running
helper never logs it automatically. The configured custom Mac build now presents
the same offer in its Pair Android Notifications menu/window, with QR, fingerprint,
expiry and cancellation (NATIVE_PUSH_PAIRING.md); whole-app/Pixel acceptance remains
open. Phone HTTPS exposes only enrollment/maintenance challenge
steps, not local control or account/identity selection.

## Executed checks and limits

**122 focused checks passed** on Node 26.8.2 (1386.285 ms) and 22.16.0
(1098.408 ms), including six new production runtime cases. They exercise real
local TLS/storage, native bootstrap/host frames, private owner offers, descriptor
reload, sender/provider binding, existing-socket preservation, partial startup,
stop/EOF cleanup and the actual CLI child process through private pipes. Provider
transport is injected in module tests; CLI fixtures enroll no recipient and make
no Google request. The configured sealer path is not invoked by these six cases;
existing CryptoKit evidence remains separate. No whole custom Mac app is built.

The initial Node 22 run passed five cases and failed the final stderr assertion
because Node 22 prints its standard experimental SQLite warning. The actual child
had exited zero and its source/control cleanup had passed. The assertion now
allows exactly that known warning while rejecting other diagnostics. Its original
log is retained as `helper-runtime-node22-initial.log`; this was a fixture
expectation change, not a production source failure.

```sh
node --test --test-timeout=15000 push/helper-runtime.test.mjs push/private-credentials.test.mjs push/fcm.test.mjs push/helper-service.test.mjs push/native-source*.test.mjs push/source-journal.test.mjs push/source-dispatcher.test.mjs push/forwarder.test.mjs push/enrollment*.test.mjs push/maintenance.test.mjs
```

Evidence is ignored `captures/runtime/push-native-owner/helper-production-node26.log`
and `helper-production-node22.log`. Temporary listeners/children were reaped. No
production listener, FCM message, APK, emulator or physical check ran in this batch.

## Native injection and restart checkpoint — 2026-10-11

At scoped upstream `f4b1509054949eaad5d695569ad443c4c18ed68d`,
`Sources/AppDelegate.swift` calls `PhonePushClient.shared.configure(auth:)` during
`configure(...)`. Its original SHA-256 is
`e24fe250dfd1d95e0f64f09060281370b05e1ae6c172a25233a301d47ffca7b0`.
The installer now retains one explicitly configured owner, projects the public
descriptor off the main actor and injects it into that call. The current key API
is `PhonePushKeyMaterial.current(bundleID:)` in `PhonePushCrypto.swift`; only its
installation/key IDs and `publicKeyData` are serialized, using the actual tagged
bundle ID. The helper sender remains independently provisioned. The installer now
writes **12 files against five audited original pins**, with explicit PBX membership.

Two behavior failures were reproduced before their repairs:

- `903a0514`: real production helper is killed/reaped; its replacement exits before
  bootstrap because the stale owner socket remains. Six other runtime cases passed.
  Probed/inode-checked recovery now passes the same seven-case command on Node 22/26,
  including preserving an active owner.
- `06e81493`: actual compiled native mapping emits selected-team `teamID` where the
  official native key exchange, recipient lookup and encrypted push tuples all use
  `teamID:null`. Android's strict native-tuple enrollment check would reject that
  offer for selected-team accounts. Wire authority now matches the account-scoped
  native tuple; verified selected-team identity/generation remain in the source
  epoch, including team A→B→A retirement. The same mapping command passes after
  recompilation on both runtimes; authenticated team-resolution gates remain.

The actual Swift owner/process queue/repository now compile in Swift language mode
6 and drive the **production CLI/helper** on Node 26.8.2 and 22.16.0. Executed
scenarios include passive/lease-gated startup, public descriptor/owner offers,
original native capture into encrypted storage, privacy kill/reap/replacement,
authority invalidation/fresh recovery, awaited stop, held cancelled preparation
with no late launch, and rejecting private fields/symlink publication. They use
synthetic identities/keys and enroll no phone; they do not test user Keychain or
actual FCM/Pixel delivery. The fixture sealer path is not invoked.

The initial owner fixture used system OpenSSL's default 0644 key file and was
correctly rejected by the private-file reader; direct stat/read diagnostics proved
that cause. Its first unhandled fixture error appeared as a Swift top-level trap,
not the earlier DispatchIO actor assertion. Fixture key mode is now explicitly
0600, readiness waits for actual capture receipts, and failures exit normally with
a fixture assertion location. Original logs remain in the ignored evidence folder.

**123 focused checks passed** on Node 26.8.2 (1307.370 ms) and 22.16.0
(1659.332 ms), in addition to the native owner scenarios, eight actual auth
projection cases, compiled mapping checks, ten receipt/replay groups and four
installer cases. Native edited-source/project parsing passes; none establish
whole-app linking or actual account behavior.

```sh
python3 scripts/check-native-push-owner.py --node /absolute/path/to/node
```

Evidence: `captures/runtime/push-native-owner/production-node26/`,
`production-node22/`, `native-owner-suite-node26.log`, `native-owner-suite-node22.log`,
`stale-owner-socket-red.log`, the green logs and native tuple red/green logs/cases.

Follow tagged/local-backend build instructions; preserve the running official cmux
and the Pixel's account/app data. Helper keys/TLS provisioning, full app linking,
actual native startup/recovery, key
rotation, encrypted unacknowledged producer persistence, configured signed Android
milestone and actual foreground/background/process-dead/Doze delivery remain open.
