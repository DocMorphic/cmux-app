# Original sign-in presentation reference

`manifest.json` records the original whole-file hashes at cmux
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`, copyright Manaflow, Inc.,
GPL-3.0-or-later. The repository LICENSE/NOTICE preserves that attribution.
`cmux-sign-in-mark.svg` is unmodified upstream artwork. Its single path and
three-stop gradient are represented by `app/src/main/res/drawable/cmux_signin_mark.xml`.

The code card follows the original separate entry states,430-point maximum card,
20-point horizontal/24-point top/20-point bottom padding,18-point stack spacing,
28-point mark,60-point code pill and fixed32-point semibold monospaced code.
Five original catalog keys across nine languages are separately bound in
`third_party/cmux-localization/sign-in-code-strings.json`.

The sign-in grid uses the existing Cloud colony/Canvas implementation:36×52,
80ms tick, toroidal B3/S23,22% seed, low-population/120-step reseeding,6-point
padding,52% cell size and source gradient/opacity rules. The shared implementation
also has Android lifecycle/reduced-motion handling; exact original sign-in motion
behavior under system accessibility settings has not been accepted.

The Android capsule paint and system typeface do not execute UIKit Liquid Glass
or establish exact source rendering. Original provider methods, entry scaffold,
restore status, transitions, window/keyboard variants and real accessibility
remain open. Scoped runtime evidence and remaining requirements: `docs/SIGN_IN.md`.

`oauth-reference.json` adds seven whole original provider/coordinator/client/
composition/SDK sources, their identity at detected79d982f9 and the unmodified
AndroidX Browser1.10.0 artifact hash. The implementation uses the original JSON
authorization-location preflight, S256 PKCE, legacy scope and form token exchange.
Android callback paths include the application ID and a flow nonce so release/
debug builds and retired flows remain separate. The pinned provider labels,
email divider and fixed email-button label contribute45 exact localized values.
Apple's original ASAuthorizationController sheet is unavailable on Android;
the existing Stack project also configures its web provider. Endpoint preflight
does not establish an actual provider login, browser isolation/dismissal, the full
auth coordinator or final UI parity. Those gates remain explicit in SIGN_IN.md.

`completion-reference.json` adds thirteen whole coordinator/SDK/model/test sources
for token-write ownership, fetched-user publication, tolerated membership failure
and restoration. A shared Android owner stages credentials under a pending writer
receipt, verifies the captured account and rejects anonymous/restricted users
before publication. A source-compatible Foundation Bool reference includes numeric
0/1 conversion. Conditional cleanup protects newer writers; an orphan can refresh
expired access before verifying its user. Scoped checks and remaining coordinator,
metadata, browser, UI and device gates remain in `docs/SIGN_IN.md`; no full auth
or application parity is asserted by the reference manifest.

`auth-work-reference.json` records fifteen whole original phase/token/preflight/
root sources and tests. The Android adapter tracks true operation completion
separately from deadline waits, shares work ownership by credential document,
fences cancelled refresh writes/clears and joins owned token work before sign-in.
Token absolute expiry, exclusive races, automatic dampers and explicit user
supersession retain their distinct source rules. It does not establish complete
coordinator, cache/loading, browser, UI or device parity; see `docs/SIGN_IN.md`.
