# Computer connection and presence rows

## Scoped iOS reference

Reviewed at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MacComputerRow.swift`,
`MacComputerSnapshot+Store.swift`, `MacComputerDetailView.swift`,
`MobileMacConnectionStatus+Display.swift`, and the shell's `PresenceMap.swift`.
The global parity pin is unchanged.

The Computers row leads with this phone's actual connection and workspace count.
Its separate diagnostic line describes the Mac's heartbeat. A reconnect row
instead leads with presence/last-seen and omits cached workspace counts. Green in
the Computers list means the phone is connected; green in a reconnect row means
the Mac is reported online. Unknown heartbeat is suppressed beside a connected
phone to avoid suggesting the live connection is unhealthy. The current detail
view uses a connection section; its unused presence-footer helper alone is not
evidence for adding another detail section.

## Android implementation

Saved Settings computer rows now show the connection phrase, a known workspace
count, build badge, a separate presence line and an accessible connection dot.
The computer selection screen uses the same label component in reconnect mode:
online/last-seen/unknown presence, a presence dot or reconnect spinner, and no
cached workspace count. Available account-directory membership is no longer
presented as a generic liveness claim. Connection authority, actual session
state and the keep-awake indicator continue to come from their existing sources.

The presence projection retains exact-instance online flags and timestamps.
Seen ticks update an existing instance's last-seen time without changing its
online flag or creating new computers. A legacy untagged row may use a sole
instance; multiple sibling builds remain ambiguous. Invalid dates/flags stay
unknown. A missing record does not invent a last-seen date or claim that the Mac
cannot be reached. This UI projection does not alter dialing eligibility.

Verified foreground and secondary host handshakes record local last-seen history
under the current login, exact saved pairing and connector permission. This
small display cache is part of the existing encrypted account state, separate
from pairing records: writing a timestamp does not change data-class equality,
route identity, aliases, drafts or live connection ownership. Writes run off the
UI thread and failures cannot fail a terminal connection. Stale login callbacks,
revoked/removed/replaced pairings and non-increasing dates cannot update history.
Sign-out clears it with account state; it cannot recreate signed-out credentials.

History uses the maximum known timestamp across a computer's existing authorized
origin aliases. Account writes prune keys no longer owned by any saved pairing,
including after Forget, and cap the cache at 4,096 entries. Route upgrades retain
history through the existing alias mapping. Only timestamp values are retained
in Compose presentation state. No network polling or history write occurs on
every heartbeat.

Relative last-seen text refreshes once a minute while displayed, using Android's
localized date formatting. Future timestamps render no later than the current
time. An older pairing without a known timestamp shows unknown rather than a
fabricated date. Presence and last-seen never imply authorization or change the
phone's Connected/Reconnecting/Not connected status.

## Remaining scope

Live Pixel/Mac presence-service acceptance is pending. This checkpoint covers
row status, badges and history; full list grouping, route-description captions,
older-duplicate markers and the entire iOS Computers layout still need an audit.
Presence-based route refresh, workspace announcements and push recovery are
separate unfinished consumers.

The row and history changes are included in [signed build 468](PIXEL_INSTALL.md),
with CI, independent packaging checks and a signed-out emulator upgrade verified.
Physical acceptance remains open.


## Verification — 2026-10-03

**46 JVM tests passed** with no failures/errors/skips: presence parsing/runtime,
last-seen history, connection projection and the feed coordinator. A new real
local-RPC case proves the history callback runs only after exact host identity
verification; a mismatched host never reaches it. History cases cover unchanged
pairing records, reload, non-regression, revoked/removed/replaced pairings,
retired logins, alias migration and Forget pruning.

**Seven Android cases passed in 63.019 seconds** on the existing API 37 / 16 KB
emulator: three new presence-row/encrypted-history cases plus two pending-switch
and two workspace/notification integration cases. Actual encrypted-store reload
preserved the timestamp and exact pairing record; a late write after sign-out
did not recreate state. The row fixtures distinguish Online from Not connected,
suppress unknown heartbeat for a connected phone, hide cached workspace counts
in reconnect mode and display relative last-seen text.

Two component screenshots were visually inspected: Connected with three
workspaces and no heartbeat, and offline with Last seen 2 minutes ago. Badge,
text and green/grey status dots render without clipping or a system overlay.
The first screenshot's system navigation bar is transitional; only the component
rendering is claimed. The emulator again started with a System UI ANR dialog;
this was observed and closed before instrumentation, with a clean launcher dump
saved. No claim is made about the cause of that emulator OS problem.

Debug/test builds passed in **53 seconds** and packaged attribution matched its
source. Evidence: `captures/runtime/computer-presence-rows/` (ignored), including
build log, JVM XML, instrumentation result, initial/clean emulator UI dumps,
screenshots and hashes. No extra AVD or signed APK was created, the existing
emulator was stopped, and the physical Pixel remained absent from ADB.

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `19b212be5fdb4710f27ae0becaea64a20b1f6d9f83d5ab71e8c9ccb6381d00f0` |
| Test APK | `9fa0e5dbb201e1b65e7f9613d4d5f9f106de83e066ae1e348c77a78b8c1fe795` |
