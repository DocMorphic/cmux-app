# Team switching and the active Mac session

## Scoped source audit — 2026-10-12

This is an implementation contract and a confirmed parity gap, **not a completed
Android feature**. The source audit uses upstream
`f4b1509054949eaad5d695569ad443c4c18ed68d`; it does not advance the global parity pin.

The iOS behavior is confirmed in the implementation, beyond the Settings comment:

- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobileSettingsView.swift`
  renders the inline team picker and describes preserving the live terminal.
- `CMUXMobileRootView.swift`, `prepareResolvedAccountScope()` and
  `accountScopeDidChangeAfterBootstrap()`, applies scope cleanup once. The root
  starts stored-Mac reconnect after a scope change only when disconnected.
- `MobileStartupConnectionCoordinator.swift`, `prepareAccountScope()`, invalidates
  pending startup ownership and coalesces duplicate scope delivery.
- `Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite.swift`,
  `currentTeamDidChange()` (lines 2531–2599), removes secondary subscriptions,
  old-team lists, presence and pending restore/switch attempts. It keeps the
  foreground workspace and notification snapshot, plus device-local external/SSH
  hosts. It marks an already connected foreground session as satisfying the new
  launch-connect window, so a subsequent drop can enter recovery.

Those paths establish connected-session retention on team selection. They do not
prove Android backend authority, revocation behavior or physical acceptance.

## Android gap at signed build 652

Deleting the two Settings `client.close()` calls is insufficient:

| Boundary | Current behavior | Required change |
| --- | --- | --- |
| `NativeAccountTeams` | Selected team owns one exact generation; returning to a team creates a fresh generation. Cached profiles are display-only. | Keep this selection boundary for new admissions and UI actions. Model already admitted foreground authority separately. |
| `NativeIrohRuntime` | `collectLatest` cancels the previous team's complete backend and connection pool; transport permission checks require the selected scope. | Preserve only the admitted foreground transport/backend as needed, with its original authority. Retire old-team secondary leases and pending dials. Discover the selected team independently. |
| `NativeSavedTailscaleRuntime` and `TailscaleConnector` | Scope changes retire pooled legacy/saved routes and ticket guards require the selected team. | Support retained foreground continuation for the actual saved route and ticket; do not silently change it to the new team. |
| `NativeAppConnections.connector` | Pairing eligibility, saved identity refresh and ticket admission use the selected team. | Distinguish continuation of an existing foreground lease from pairing/opening another Mac. Revalidate logout, revocation, route replacement and ticket expiry. |
| `NativeScreen` | Settings selection/create explicitly closes the foreground client. Eligibility, reconnect effects, pane navigation, tabs, browser ownership and many operation guards also use the selected scope. | Keep the connected Mac's pane identity and operation scope while refreshing the selected team's picker/feed. An in-flight old-scope attempt must not publish into the replacement selection. |
| Feed, browser, notifications and compatibility owners | Selected scope keys cause old owners to be retired. | Retain only foreground state/authority. Audit each actual owner before changing its guard; selected-team background work must still stop. |

The original connection scope must remain attached to the live Mac. A terminal
retained from team A must never be relabeled or persisted as a team B pairing.
Do not loosen `NativeAccountTeams.isCurrent()` globally: it guards selection,
ticket admission, settings mutations and delayed callback retirement throughout
the app. Existing generation/ABA and cached-profile protections remain necessary.

## Implementation order

1. Define the foreground continuation lease independently of the selected-team
   generation. Bind it to the verified login/user, Mac instance, authenticated
   route/ticket and existing admitted client. Confirm the production authority
   contract before making membership assumptions. Cached profile data cannot
   create this lease, and removal/re-addition cannot revive a retired lease.
2. Separate runtime discovery ownership from foreground transport ownership for
   Iroh and saved/legacy Tailscale. Keep one foreground continuation and retire
   old-team secondary/pending work. Admission of any new client stays selected-team
   scoped. Ensure endpoint/service cancellation joins owned work and that changing
   connection settings or a grant cannot preserve a stale route.
3. Give foreground panes, tabs, input, Files, browser, creation and notifications
   their actual Mac scope. Build the picker and other-Mac feed from the new team.
   Preserve local SSH/external hosts. Remove explicit Settings teardown only after
   these ownership paths work together.
4. Keep Activity recreation idempotent. Following foreground loss or process death,
   apply the pinned iOS recovery policy using the newly selected scope; never
   resurrect an unverified old lease from persisted UI state.

No portion of these steps is claimed implemented by this audit.

## Acceptance cases

- A connected terminal survives A → B without a new dial, lost pane selection,
  changed Mac identity or duplicated terminal input. The picker/presence reflects B;
  A's secondary workspace/notification subscriptions disappear.
- Switch through A → B → A while pending admissions, restore, computer checks,
  mutations and cached UI callbacks are suspended. Only the current selection's
  pending work can publish; the admitted foreground remains individually scoped.
- Repeat for native Iroh, a saved native identity using Tailscale, and legacy
  ticket/grant routes. Exercise sibling Mac build tags and hidden/replaced records.
- A disconnected switch starts recovery for the selected team. A retained session
  dropping later must not leave startup recovery permanently parked.
- Logout/account replacement, host authorization rejection, ticket expiry and
  confirmed revocation retire the original foreground authority. A new account,
  team re-addition or offline cached profile cannot revive it.
- Activity recreation keeps the same admitted lease; cold process restart verifies
  fresh admission. Verify browser/Files/tab/task destinations and notification
  routing do not cross scopes.
- Finish with the actual Pixel/Mac workflow, recorded source/APK/host versions and
  visible input/output, preserving the user's account, data and workspaces.

The scoped source evidence is sufficient to identify the gap. It is not evidence
that any of these Android continuation or physical cases passed.
