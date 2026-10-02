# Account restoration

## iOS contract reviewed

Reviewed the following sources at audited candidate
`204a11dfcc76280205e50406ab94270a1c152155`:

- `Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator+SessionPriming.swift`
  restores cached user presentation alongside stored credentials and preserves it
  through transient session validation failures.
- `AuthCoordinator+TeamScopes.swift` in the same directory grants a team scope
  only for a verified session generation and a currently available membership.
- `AuthCoordinator+TeamSelection.swift` persists confirmed selection before
  publishing it; `AuthCoordinator.swift` reconciles selection with fresh teams.
- `Packages/Shared/CMUXAuthCore/Sources/CMUXAuthCore/Models/CMUXAuthUser.swift`
  and `Persistence/CMUXAuthIdentityStore.swift` define the cached identity.
- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobileSettingsAccountSection.swift`
  displays the account name and email.

This narrow audit does not advance the broad app parity pin. Android uses its
existing Keystore-backed credential storage, not iOS Keychain accessibility rules.
Android also caches team labels for display; the audited iOS coordinator starts
with an empty available-team list and separately persists the selected team ID.

## Android behavior

The last verified account name, email, team names and selected team survive
controller reconstruction in the existing encrypted account store. The versioned
snapshot belongs to one local login incarnation and authentication environment.
It contains no access/refresh token, live connection scope or scope generation.
Invalid, oversized, duplicate or mismatched cached data is ignored.

Settings displays saved account details immediately, including an offline
explanation. Team selection and creation remain disabled until a fresh account
and membership request succeeds. Restoring a cache never establishes live
authority or starts a native endpoint. The connection owner preserves the cache
during initial login observation rather than clearing it before refresh.

Fresh membership reconciles the server-selected team, or the same user's saved
choice if the server supplies none. A removed choice falls back to the first
current membership; an empty membership list produces no team scope. A changed
server user ID immediately retires the old scope and cached profile, even if the
subsequent membership request fails.

Transient HTTP/network failures preserve cached display. Definitive account
rejection clears this login's cache and authority. Sign-out/account replacement
prunes the cache in the same encrypted transaction as credential changes. Late
responses cannot clear a replacement login's cache or publish its predecessor's
profile. Closing a controller releases requests without erasing the durable
profile. Team selection updates the cache only after a confirmed server response.

A cache write failure reports that offline details could not be saved; it does
not discard freshly verified membership. Snapshot contents are display data and
are never accepted as evidence of account or team authorization.

## Acceptance boundary

Tests use generated identities and loopback HTTP. Controller reconstruction with
real encrypted Android storage is covered. Actual OS process-kill restoration,
physical Pixel/Mac account acceptance, biometric/device-unlock behavior and older
Android API coverage remain open. These checks do not establish full app parity
or production push delivery. No production account or signed release was changed.

## Verification — 2026-10-02

All **51 focused JVM tests passed**, zero failures/errors/skips: eight profile
cache cases, sixteen account-team cases, eighteen native runtime cases and nine
email sign-in cases. Coverage includes read-only restoration, failed refresh,
removed/empty memberships, confirmed selection persistence, rejected sessions,
late responses after account replacement, changed server identity, malformed
profile data, schema/environment ownership and storage failure.

All **nine Android tests passed in 59.424 seconds**, API 37 / 16,384-byte pages,
zero failures/skips. Two new tests cover real encrypted cold restoration,
disabled cached-team controls, offline failure, refresh and selection, sign-out,
and replacement-login protection. Three team UI and four account-deletion tests
passed in the same run. Offline and verified account screenshots were inspected;
both show the expected identity, team and enabled/disabled controls.

Both APKs built and passed 16 KB ZIP alignment. All five native libraries passed
LOAD/RELRO alignment checks. SHA-256:

- Debug: `72a8a9dc73b4c8e3c9933ad9bda17b28b9ea8149717470fa42c529f7212f369d`
- Test: `52a4c9c0331104efcbcd04236655946f89b2fdcec7ac6343c1ca3ee0d6f709f2`

Ignored local evidence is under `captures/runtime/account-profile/`, including
`final-build.txt`, `final-android.txt`, JVM XML and the two account screenshots.
The existing emulator was stopped after testing; no new AVD was created.
