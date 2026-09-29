# Scoped paired-computer records

Checkpoint: 2026-09-29. This extends the existing encrypted pairing array; it does
not change the upstream QR format or send local metadata to cmux services.

## Ownership and identity

Production authenticated writes now store three optional fields alongside the
existing code, device ID, display name and build tag:

| Field | Purpose |
| --- | --- |
| `owner_user` | Verified account user ID |
| `owner_team` | Verified team ID |
| `stable_origin` | Durable key for drafts, notifications and computer selection |

New origins hash the account, team, canonical device and exact build, independently
of the current QR/native locator. A verified same-owner/same-build route change
retains the existing origin. The writer keeps the row's position and preserves
an existing native code/name when a QR adds another coordinate. Connection-method
preferences remain unchanged. UUID case is normalized for comparison; opaque
identifiers remain case-sensitive.

A standalone QR row can now upgrade to a native locator after an authenticated
native connection. Its saved drafts, notification route IDs/unread baseline and
computer selection still refer to the same origin. Saved Tailscale grants remain
available after the upgrade. The QR-attachment fix from `6a1c90b` is included.

Legacy records remain readable. Fully scoped native locators can establish their
existing owner. A historical QR record is eligible for migration only when the
stored route grants identify exactly one owner for its source/device/build,
consistent with any QR user hint. Its existing origin is retained in that case.
An ambiguous historical row cannot donate its origin to a different owner; a
separate explicit scoped record gets its own origin. Partial owner metadata,
invalid stored origins and contradictory native scope hints are rejected. Invalid
or unresolved historical entries are retained in storage rather than erased.

The same QR can be saved by two teams without overwriting either record. The
foreground list, feed and notification service filter by the row's owner and
current matching grant. Notification/workspace navigation rechecks ownership.
Saved-row connections carry the captured scope into the Tailscale authority before
resolution/token acquisition; native connections carry explicit user/team hints.
The returned connection is checked again before publication. A dispatcher
cancellation after socket acquisition closes the acquired client.

Local removal now deletes only the current team's row and QR grant and clears its
selection. Another owner's identical QR survives. Native Forget also verifies
captured owner/origin metadata before removing a row, so a stale captured record
cannot remove a replacement with a different scope or durable identity. Actual
server revocation is unchanged and was not exercised against a real Mac.

## Verification

- **75 focused JVM cases passed** with no failures/errors/skips: 17 pairing
  persistence, 23 route authorization, 16 feed, 7 notification ledger and 12
  native computer-removal cases.
- The upgrade test saves/reloads a real `TaskDrafts` value and notification ledger,
  then verifies reopening the draft, retaining the notification route UUID,
  preserving unread acknowledgement, and surviving origin pruning. Additional
  cases cover scoped duplicates, historical ambiguity, corrupt metadata, grant
  target changes, exact-build isolation and scope change before saved dialing.
- The first compile attempt found a nullable decoded-row access in Forget; an
  explicit non-null check fixed it. Adding the captured-scope dial argument later
  exposed one outdated Details call site; using a named token argument fixed it.
  Both errors occurred before APK/runtime verification of their respective edits.
- **Eight Android 17 emulator tests passed in 10.817 seconds** before the final
  saved-dial fence. On the final APK the same eight passed in **10.940 seconds**:
  four grant/persistence cases and four notification-delivery/service cases.
- The new Android cases use the real Keystore and separate fixture preferences:
  QR-to-native upgrade reloads the same origin, retains Tailscale preferences and
  grants, and preserves selection. Two teams save the same QR; removing one
  leaves the other's encrypted row and grant intact. These fixtures do not
  connect to a real Mac or demonstrate physical Tailscale traversal.
- Main and instrumentation APKs built together, also incorporating the previous
  QR attachment commit. Native ELF LOAD/RELRO and ZIP 16 KB alignment checks pass.
  Main SHA-256: `54be30ab14114589b9cbc336ecfbb013ea655561e68c70ece3f5df52e3902aa3`.
  Instrumentation SHA-256: `4799331cc75dd7e74ad02624bb94b042b63e7bb22874717c1e9c20ee76b73970`.
- Logs, XML and APK/alignment receipts are in ignored
  `captures/runtime/scoped-pairing-records/`. The emulator was stopped afterward.
  ADB detected no physical Pixel. Last Pixel install remains `f0dfc7f`, and
  published signed build 157 is unchanged. No real Mac or phone setting changed.

## Remaining acceptance and integration

- Already duplicated historical rows with multiple origins are not automatically
  coalesced. Multiple owned rows for one exact Mac/build currently reject an
  ambiguous merge. A resolution flow must retain old draft/notification aliases
  without guessing ownership or revoking a real Mac to clean up local duplicates.
- Unresolved native hints lacking scope need explicit reconnect/migration rather
  than automatic adoption of their previous owner's data.
- Standalone QR-only records still use the legacy connector and settings entry
  point until native discovery upgrades them. Full per-computer Details/method
  behavior for those records needs comparison with the iOS compatibility path.
- Physical Mac/Pixel acceptance remains: scan, sign out/in, change teams, add a
  native identity, reopen a task draft, follow an existing notification, remove
  only one scoped pairing, and switch routes during terminal/background activity.
