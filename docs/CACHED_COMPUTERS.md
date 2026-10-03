# Computers during offline account restoration

## Behavior

The reconnect list and Computers management now show the saved Macs belonging to
the last verified account and selected team while account revalidation is pending
or fails with a temporary network/server error. A banner explains that the app is
showing saved computers. Custom names/icons/colors, saved connection method and
endpoint labels, hidden rows, last-seen history and Mac update warnings remain
available. Unknown presence remains unknown; saved data does not imply that a Mac
is online or keeping awake.

Cached row connections, details and visibility changes are disabled until fresh
account membership is established. Refresh computers remains available. A
successful refresh replaces cached presentation with the normal verified list;
new account/team membership controls which rows survive. A definitive rejection,
sign-out or replacement login removes the cached projection, while unrelated
pairing records remain stored.

The account controller already restores the encrypted profile without authority.
This extension uses a separate `NativeComputerDisplayOwner`, never a fabricated
`NativeTeamScope`. It verifies the profile environment, login incarnation, user,
selected team and membership snapshot before projecting any rows. Pairing
ownership and consumer build filtering apply to each saved row. Corrupt metadata
fails closed without mutating the credential state.

The projection is used only by the reconnect and management presentation. It is
not added to the foreground connector, feed aggregation, presence subscriptions,
workspace/task owners, grant promotion or background worker authority. Appearance
and connection preference stores expose read-only state flows for display owners.
Encrypted version history has a read-only display path; cached warnings follow
the current minimum-version policy without registering or retiring an RPC wire.

## iOS reference

Scoped source review: cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:

- `AuthCoordinator+SessionPriming.swift` restores cached identity and preserves it
  after temporary session validation failures.
- `TeamScopedPairedMacStore.swift` scopes paired-Mac reads to the selected team.
- `MacComputerSnapshot+Store.swift` shares immutable saved-Mac snapshots between
  management and disconnected reconnect lists, with per-Mac connection method,
  customization and presence. Keep-awake status requires a live connection.

Android uses its existing stricter account-admission boundary for network and
mutation actions. This work preserves saved presentation during that check; it
does not claim parity for all upstream authentication transitions or advance the
global parity pin. Upstream copyright: Manaflow, Inc.; GPL-3.0-or-later.

## Verification

38 focused JVM tests passed with zero failures/errors/skips, covering cached
projection ownership, consumer build filtering, route isolation, version history,
policy gates, account cache and saved-list behavior. Debug/test APK assembly passed
in 1 minute 39 seconds; the final test-only fixture adjustment built in 19 seconds.

Six Android tests passed in 45.889 seconds on the existing API 37 / 16 KB AVD:

- Encrypted account-controller recreation displays the saved custom name and Mac
  warning without account requests or connection callbacks. A 503 retains rows;
  a successful refresh enables selection under current verified scope.
- Hidden management rows remain disabled. Repeated 401 responses remove the cached
  projection without deleting saved pairings.
- Two existing reconnect and two Mac/SSH management tests pass with the shared
  components, including tap-time authorization and canceled deletion.

The offline screenshot was visually reviewed and the emulator stopped. No new AVD
was created. Local evidence is under captures/runtime/cached-computers. Initial
failures are retained: an invalid grant UUID in the new fixture, then a callback
signature incompatible with an existing trailing-lambda test; both were fixed.
An install attempted before Android's package service was ready was retried only
after boot completion. The UI run passed on its first attempt.

Debug SHA-256: d48585dff21039093d0a3a399474c364715fcd9f19c95ff408114f4753afc5f9.
Test SHA-256: 7677bf131311b657241240d239e07395c8542665fafb4a05946472e7057b2353.

The new fixtures recreate storage/controller objects and use MockWebServer; they
do not kill/relaunch the entire production app or connect to a real Mac. The Pixel
is absent from ADB. Physical offline/reconnect, current native Mac acceptance and
configured push remain open. Signed build 474 predates this work; no signed build
was dispatched for this individual feature.
