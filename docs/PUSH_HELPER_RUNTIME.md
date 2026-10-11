# Production push helper startup — 2026-10-11

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
environment and private stdin/stdout. `NativePushSourceProcessSink` already owns
that process/pipes; production composition injection and native-descriptor
publication remain to be installed in the custom app.

## Local pairing control

The runtime binds `stateDirectory/control/owner.sock` in a private directory, with
socket mode 0600. It refuses to remove/replace an existing owner socket or file.
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
helper never logs it automatically. A Mac UI/QR presentation is still required
for product setup. Phone HTTPS exposes only enrollment/maintenance challenge
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

## Next native composition step

At scoped upstream `f4b1509054949eaad5d695569ad443c4c18ed68d`,
`Sources/AppDelegate.swift` calls `PhonePushClient.shared.configure(auth:)` during
`configure(...)`. Its original SHA-256 is
`e24fe250dfd1d95e0f64f09060281370b05e1ae6c172a25233a301d47ffca7b0`.
Retain one explicitly enabled owner, publish the current native public descriptor
off the main actor, then inject the process sink into that call. The current key
API is `PhonePushKeyMaterial.current(bundleID:)` in `PhonePushCrypto.swift`; project
only its installation/key IDs and `publicKeyData`, using the actual tagged bundle
ID. Do not export its private key or copy it into the helper sender identity.

Follow tagged/local-backend build instructions; preserve the running official cmux
and the Pixel's account/app data. Full app linking, native startup/recovery, key
rotation, encrypted unacknowledged producer persistence, configured signed Android
milestone and actual foreground/background/process-dead/Doze delivery remain open.
