# Saved Mac version observations — 2026-10-03

This extends [minimum-version compatibility](MAC_COMPATIBILITY.md) so a saved
computer's update warning can return after the Android process restarts, without
dialing the Mac. The warning is recalculated from the current policy and last
authenticated version; a stored warning string is not treated as current policy.

## Ownership and persistence

- Observations live inside the existing Android Keystore encrypted credential
  state, outside pairing records. Writing them cannot change a route, grant,
  pairing origin or live-connection key.
- Keys contain account user, team, canonical Mac device ID and exact instance
  tag. A missing tag is a separate identity, not a wildcard. Same-device stable
  and nightly observations cannot overwrite one another.
- Only an existing, owned saved pairing can receive durable history. The current
  login incarnation and team authority fence writes. Late callbacks cannot
  recreate a signed-out account or forgotten pairing.
- A present null version means the authenticated host omitted its version; it
  remains distinct from having no observation and can produce an update warning.
- An authenticated host probe records the observation synchronously when the pairing
  already exists, including when its version is too old for admission. A credential revision saves it if a first pairing is committed
  after its handshake. The storage observer is independent of discovery and HTTP.
- The latest immutable observation is read inside the credential transaction.
  A delayed callback cannot overwrite a newer observation using an old snapshot.
  Equal values do not rewrite encrypted storage or emit another revision.
- Hiding retains history. Forgetting prunes history when no owned pairing for
  that exact Mac/build remains. Route changes and aliases retain the same exact
  identity's history. Sign-out clears the encrypted account state.
- At most 256 observations are stored, with versions bounded to 1,024 characters.
  Duplicate keys, malformed scalar types, invalid identities or oversized caches
  are discarded. This metadata is not a source of connection authority.

## Restoration

The gate keeps restored display metadata separate from observations authenticated
in the current process. Restoration never registers a wire, requests a token or
sends an RPC. Fresh authenticated observations override cached values. Refreshed
minimum-version policy re-evaluates both display sources, but only authenticated
live-wire state can cause a connection to be retired.

Account retirement removes restored warnings and rejects late restoration from
the old scope. Restarted runtime generations can read the same login/user/team's
encrypted history after the account scope is verified again.

The cached-account-profile UI currently has no connection-authority scope until
account refresh succeeds. This feature does not make the entire Computers UI
available during a completely networkless account bootstrap. That display-only
projection remains separate work; cached versions must not fabricate authority.

## Verification

60 focused JVM tests passed (history, policy/gate, visibility and pairing
persistence). Final debug and instrumentation APK assembly took 52 seconds.
Five Android tests passed in 31.355 seconds on the existing API 37 / 16 KB AVD.
The three new history tests cover encrypted reload, a restored hidden-row dialog,
forget/sign-out cleanup, delayed transactional reads, unchanged-write suppression,
first-pairing backfill and team retirement; two existing update-guidance tests
also passed. The hidden-row dialog screenshot was visually inspected and the
emulator stopped. Evidence is under captures/runtime/mac-version-history; artifact
hashes are in the parity checkpoint.

No physical Mac/Pixel acceptance or process-wide OS force-stop journey is claimed
by these fixtures. They create new store/gate instances using the real encrypted
storage and the production observation binding, with synthetic account data.
The existing signed build 474 predates this work.

## Separate build-audience policy

The reviewed iOS MobileMacBuildCompatibilityPolicy at
0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc applies a separate policy before version
admission: distributed builds accept default/nightly/rc tags and official Mac
namespaces; tagged development builds use an explicit matching tag and granted
sibling tags. Missing authenticated tags have only the locally authorized
Tailscale 0.64.17–before-0.64.18 exception. The minimum-version floor still applies
after that audience check, so an audience exception alone does not admit 0.64.17
under the current production floor.

[Consumer audience enforcement](MAC_BUILD_AUDIENCE.md) now covers authenticated
admission, discovery/presence, saved rows and background workers. Both Android
variants explicitly use the consumer audience. An eventual internal development
profile still needs explicit identity and grant persistence. These scoped source
reviews do not advance the global parity pin.
