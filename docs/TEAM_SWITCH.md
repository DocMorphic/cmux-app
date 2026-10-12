# Team switching and the active Mac session

## Captured-owner local removal and root-stop fencing — 2026-10-12

The local Settings removal action now captures login, pane owner, exact saved Mac,
code, client and retry generation. It checks current presentation/permission before
and inside the credential transaction. Only the unchanged captured pairing is
removed; same-code other-team rows and sibling builds survive. Changed routes or
tickets, ambiguous records, account changes and lost permission cannot mutate it.
An unsaved pasted code can be cleared without deleting saved records. Scoped route
grants/tickets are pruned for the captured owner. Drafts, sign-in and another saved
selection/code remain intact. The accepted action closes its captured root and
clears its pane instead of rebinding to a same-code sibling.

Remote Forget preparation now distinguishes the exact live admitted record from
its exact pending reconnect record. A selected-team row sharing the root's public
locator cannot stop that root; a genuinely pending matching request can be stopped.
Owner/target identity remains checked. This corrects the preparation callback; it
does not yet enable remote removal through a retained original-team detail page.

**56 focused JVM cases pass**, including six new same-locator/build, revision,
account, late-guard, unsaved-code and live/pending-root cases. Debug/test APKs build
and five classes pass Android 17 ART reflection. **Three runtime cases pass in
6.292s**: the real Settings button removes its pairing/closes the root while keeping
sign-in; encrypted storage rejects a late guard without a disk change and retains
the other team's same-locator row on success; and local cleanup retry does not
repeat an already confirmed remote revoke.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-local-removal-*`. The sole existing 16 KB AVD was
stopped/reaped; no physical device was touched. These are local fixtures, not actual
account team switching, Mac/Iroh/Pixel or server revoke acceptance. Signed 654
predates this source; full parity remains unverified.

Source review confirmed iOS `MacComputerDetailView.forgetComputer()` builds removal
from the pairing's own stored user/team (reviewed scoped pin f4b15090), and
`NativeIrohRuntime` retains an original native backend while its foreground lease
is held. Android's selected detail host and runtime operations still require the
selected owner. Next: an exact-target original-owner detail/management path for
appearance, connection settings, live checks, power, private paths/routes and
remote Forget; preserve selected-only new Mac admission and safe post-revoke local
cleanup. Saved Tailscale transport retention also needs its original control-owner
path. Do not simply relax global `isCurrent` or bypass the selected operation guard.

Explicit Settings selection/create and Iroh eligibility teardown remain enabled:
**team selection still disconnects the app**. Combined scope/UI and real-device
acceptance are still required before removing them.

## Foreground agent-feed ownership and lifecycle — 2026-10-12

`NativeFeedSession` now owns one agent-feed session on the exact admitted root
record/client/codes/capability and original live permission. It shares the existing
connection, survives Activity recreation, publishes into the foreground workspace
projection, and retires on client replacement, disconnect, record/permission loss
or disposal. Main and routed agent views use this owner before selected-team feed
sessions. Source inventory, allowed rows and workspace/tab navigation use the pane
Mac; secondary admission remains selected-team scoped.

Subscriptions/polling pause in the background and resume with a fresh snapshot
before actions are available. Newer event revisions also require refresh before
action. Decisions, terminal replies and text reads carry ownership, visibility and
item guards through asynchronous token lookup and wire admission on both root and
secondary feeds. Interrupted writes retain uncertainty while the owner is still
valid, including while inactive; resuming never automatically replays them. A
monitor that loses its owner stops safely instead of throwing out of its parent.
Authorization rejection retires the root.

Read-marker/modal owner keys now follow original login/user/team identity without
a membership-refresh generation. The matching prior generation-key entry is a
migration fallback; unrelated account/team preferences are not scanned. Pull-to-
refresh and routed refresh wake the root session as well as existing feeds.

**114 focused JVM cases pass**, including five new foreground lifecycle, fresh-read,
token-lookup, uncertainty, rejection and read-owner cases, plus the existing 60-case
feed coordinator suite. Debug/test APKs build and seven classes pass Android 17 ART
reflection. **Three runtime cases pass in 23.539s**: an action on the retained root
with the secondary feed paused, then recreation with identical root/session and no
replay; real feed-tab/search/question/permission/reply/tab navigation; and delayed
IME submission using the newest draft with dismissal confinement. The initial
compile identified a refresh method needing `suspend`; it was corrected before the
final verification.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-agent-feed-*`. The sole existing 16 KB AVD was
stopped/reaped; no physical device was touched. These are local fixtures, not actual
account team selection or Mac/Iroh/Pixel/FCM acceptance. Signed 654 predates this
source; full parity remains unverified.

Remaining: foreground Settings/detail/local-pairing-removal operations, final
combined scope integration and actual account-switch UI acceptance. The selected-
team detail host still needs a valid original-owner path. Explicit Settings
selection/create and Iroh eligibility teardown remain enabled: **team selection
still disconnects the app**. Keep fresh connection admission selected-team scoped.

## Record-qualified connection presentation and same-locator replies — 2026-10-12

Connection status now has a record projection keyed by account owner and app
instance, with exact origin/route/ticket matching on lookup. Foreground count and
transport remain separate from a selected-team same-device/same-locator feed's
count, transport and keep-awake state. Pending status requires the intended record,
not code equality. Ambiguous owned instances are omitted. A harmless name change
can keep its verified source; route/ticket changes cannot. This is presentation
metadata and never admits a connection or settings action.

`NativeScreen` derives selected-team legacy maps from this projection, supplies its
record getter to the computer selector, and uses it for selected Settings rows.
Inventory union coalesces harmless foreground/selected name differences without
choosing ambiguous records. Settings row keys and captured action lookup include
account ownership, preventing a removed original row from retargeting to a same-
instance row in another team. The component can render both owners safely; the
production Settings inventory still awaits the foreground detail integration.

Reply candidates now search both foreground and selected inventories by original
notification origin, coalesce harmless renames and reject conflicting aliases.
The root branch requires `paneConnected(record)` rather than code equality. A
same-locator selected peer can therefore borrow its already verified feed while
the original root stays untouched. Selected feed permission and all encrypted
receiver/outbox/epoch and admitted-paste fences remain; no new dial is introduced.

**96 focused JVM cases pass**, including a new real-RPC same-locator selected-feed
reply test, record projection isolation/pending/revision cases and the existing
60-case feed coordinator suite. Debug/test APKs build and seven classes pass
Android 17 ART reflection. **Three runtime cases pass in 7.593s**: same-instance
owner rows retain separate transport and reject captured-row retargeting;
component owner/membership/disposal guards; and the encrypted original-owner
notification reply across an injected picker change.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-record-connections-*`. The sole existing 16 KB AVD
was stopped/reaped; no physical device was touched. These are local fixtures, not
actual account team selection or Mac/Iroh/Pixel/FCM acceptance. Signed 654 predates
this source; full parity remains unverified.

Remaining: foreground Settings/detail/local-removal operations, original-owner
agent-feed source/read/actions when its secondary feed retires, then integration
and actual account-switch UI acceptance. The selected-team detail host still
requires its selected discovery/runtime state. Explicit Settings selection/create
and Iroh eligibility teardown remain enabled: **team selection still disconnects
the app**. Do not broaden fresh connection admission to make a detail view work.

## Foreground replies, workspace inventory and display metadata — 2026-10-12

The direct notification reply provider now finds the individually admitted pane Mac
and uses its original live owner. Historical action generation zero is matched by
login/user/team identity to that live owner; it grants no fresh connection. The
exact admitted record/client/codes/readiness and saved pairing guard still apply.
Other Macs remain selected-team feed scoped. Existing encrypted action, peer epoch,
helper epoch and outbox permission fences remain in the receiver.

New `nativeForegroundReplyAttempt` captures one ready workspace/surface on the
existing handle, keeps input ordering and literal text/Return submission, and
rechecks topology and authorization through asynchronous token lookup and after
acknowledgement. Authorization rejection retires that handle. `pasteAdmitted`
passes this request-local guard through the normal input dispatcher and identified
input RPC; it does not replace the input stream identity. Secondary feed replies
also use admitted paste. A lost acknowledgement remains unconfirmed and cannot
automatically become a relay duplicate.

The workspace list, selected computer and sort inventory now include the admitted
pane Mac. Root snapshot fallback and sort connection status require `paneConnected`
instead of code equality. Inventory memoization tracks the live owner, record and
readiness. A retained Mac also prevents empty-computer onboarding and participates
in additive color slots when absent from the newly selected directory. Scoped
presence/build-label reads reject another team's same-device metadata; local
last-seen history and build-tag fallback remain available. Legacy unscoped selected
rows retain their prior display behavior.

**68 focused JVM cases pass**, debug/test APKs build, and seven classes pass
Android 17 ART reflection. **Four runtime cases pass in 14.261s**: real encrypted
notification/action/receiver delivery on the existing connection after an injected
picker change; lost acknowledgement without relay/second write; original/picker
same-device presence and build labels; and root workspace reordering while the
secondary feed is paused. JVM cases include original-owner isolation, zero paste
bytes after proof loss during token lookup, authorization retirement, literal text,
ready-topology confinement and identified dispatcher submission.

The first JVM run had a new fixture expecting input sequence zero; the actual
protocol starts at one. Only the expectation was corrected, and its original XML
is preserved. A missing test-tag import in the Android fixture was also repaired.
Final XML/logs/source and APK hashes are ignored under
`captures/runtime/foreground-reply-metadata-*`. The sole existing 16 KB AVD was
stopped/reaped; no physical device was touched. These are local fixtures with
injected scope/proof, not actual account team switching or Mac/Iroh/Pixel/FCM
acceptance. Signed 654 predates this source; full parity remains unverified.

Remaining integration found by source review: owner-qualified selected-computer
connection/count/transport presentation (the shared identity map still belongs to
the picker), foreground Settings/detail/local-removal actions, and agent-feed
source/read/action presentation while the original secondary feed is retired.
Explicit Settings selection/create and Iroh eligibility teardown remain enabled.
**Team selection still disconnects the app.** Keep teardown until those remaining
paths and actual account-switch UI acceptance work together.

## Foreground Mac forwarding settings — 2026-10-12

Mac forwarding controls now use the admitted pane's original team and appearance
name. Read and write guards require the exact current handle, admitted saved Mac
record, login/user/team, both code bindings, readiness and live selected or retained
foreground authority. A same-locator selected-team sibling cannot borrow the old
handle after its proof disappears. Route/ticket/record changes, removal, ambiguity,
logout, replacement and disconnect deny settings traffic. Controls disappear when
the guard becomes false; cached settings never grant permission.

**37 focused JVM cases pass**, including four new identity/authority cases. The
existing authenticated RPC test now exercises the actual foreground guard with the
picker scope unavailable; revoking its proof during token lookup sends no mutation
bytes. Debug/test APKs build and four classes pass Android 17 ART reflection.
**Two runtime cases pass in 4.847s**: real Mac settings RPC/UI preserves the original
owner and alias across an injected picker change, changes a setting once, and removes
controls after proof loss; the confirmed/stale controls case also passes.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-mac-settings-*`. The sole existing 16 KB AVD was
stopped/reaped. No physical device was touched. The picker change is a component
fixture, not actual account team selection or full Mac/Iroh/Pixel acceptance.
Signed 654 predates this source; FCM delivery and full parity remain unverified.

Targeted source review found remaining work before removing Settings teardown:

- `NativeScreen.settingsContent`: connection rows/current-detail guards use the
  selected team, and local pairing removal captures the selected owner for the
  active code. Retained foreground controls need their own owner-qualified route.
- `NativeComputerDetailsPresentationHost`: `state.account != team` dismisses the
  details view. The original foreground's details need valid per-owner state;
  do not relax the runtime's selected-team authority for new connections.
- `ObservePhoneReplyDirect`: the direct reply provider still requires the selected
  team and selected paired-Mac list. Retained foreground replies need exact owner,
  record, workspace/surface and reply authority before/after delivery.
- `machineColorIndices`/`scopedPresence`: verify foreground display metadata when
  the original Mac is absent from the selected directory, including same-device
  rows. Root sort-computer status still compares code rather than `paneConnected`.
- Explicit Settings selection/creation and the Iroh eligibility effect still close
  the root. Keep those until remaining integration and actual team-switch UI
  acceptance are proven. **Team selection still disconnects the app.**

These findings are a targeted audit, not a claim that all scope sites are complete.

## Foreground appearance and task choices — 2026-10-12

The admitted pane now reads name, color and icon from its original user/team.
An owner-qualified appearance overlay preserves same-device picker rows in the
selected team. Failed foreground reads fall back to that Mac's host name; appearance
metadata grants no connection authority. Task draft restoration, Mac choices and
model-cache retention use the admitted pane's available Macs. Routed sidebar actions
use the existing exact-record pane admission guard, and workspace shell ownership
follows the pane team.

**70 focused JVM cases pass**, debug/test APKs build, and five changed classes pass
Android 17 ART reflection. **Three runtime cases pass in 24.232s**: real appearance
editing/reopening, paginated folder selection with exact path/name/group, and task
model/effort submission. The folder fixture initially skipped host capability
negotiation; it now requests host status and advertises group support before
creation. Product mutation admission checks remain intact. An appearance fixture
was also corrected to use UUID case normalization rather than arbitrary identifiers.
Initial failures, XML, source/APK hashes and final logs are preserved under ignored
`captures/runtime/foreground-appearance-task-*`.

The sole existing 16 KB AVD was stopped/reaped; no physical device was touched.
These are fixture checks, not actual Mac/Iroh/Pixel or full cross-team UI acceptance.
Settings, push controls and remaining selected-scope presentation still need review
before retention is enabled. **Team selection still disconnects the app.** Signed
654 predates this source; full parity and Mermaid pixel acceptance remain unverified.

## Foreground Changes access and reconnect — 2026-10-12

Changes file lists, diffs, stat and fetch requests now prefer the exact admitted
foreground workspace. Requests check client/record/account/capability/descriptor
before and after asynchronous work. Ordinary loss can retain authorized cached
presentation; authorization rejection retires the client and invalidates it.
Main-pane and browser/row Changes actions use this access path, while other Macs
keep their verified feed access.

The modal presentation can rebind to a newly admitted replacement while retaining
its navigation and store. An unchanged foreground does not refresh on recreation;
a healthy feed-backed viewer is not opportunistically upgraded by recreation.
Neither access changes nor cached metadata admit a new connection. Child browser
lifetime guards wrap shared access independently.

**90 focused JVM cases pass**, including five new guarded-wire cases covering all
four read types, descriptor/authority changes, ordinary loss and rejection.
Debug/test APKs build; five changed classes pass Android 17 ART reflection.
**Three runtime cases pass in 39.599s**: foreground Changes with secondary feed
paused, Activity recreation and fresh-client reconnect; selected diff/scroll across
recreation/rotation and retirement on sign-out; and renamed-image Before bytes,
zoom/pan and PDF page retention through recreation without refetching.

Initial failures and screenshots are preserved. The reconnect test initially
observed an old cached page before the asynchronous fresh read; it now waits for
that confirmed read. A preview pixel probe sampled the antialiased center boundary
of a correctly restored green/red pan. The unchanged interior green/red probes,
artifact identity, fetch counts and PDF page checks prove restoration; the redundant
boundary expectation was removed. Successful image and page-2 screenshots were
pulled and visually inspected. Rendering was not changed to satisfy that probe.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-changes-access-*`. The existing 16 KB AVD was
stopped/reaped; no new AVD or physical phone was used. These are local fixtures,
not actual Mac/Iroh/Pixel or full cross-team UI acceptance. Signed 654 predates
this source.

Appearance and remaining task/presentation guards still need review before removing
Settings teardown. **Team selection still disconnects the app.** Full parity remains
unverified; Markdown/Mermaid pixel acceptance remains a separate outstanding gate.

## Foreground pane, tab and picker guards — 2026-10-12

Pane recovery, browser discovery, terminal startup expiry, workspace routes and
remembered tab updates now use the admitted Mac's own team context. Activity
restoration uses that live context when available; cold process restoration still
uses the selected team. Local-browser creation and routed sidebar data/actions can
use the individually admitted foreground record without admitting another old-team
Mac. Notification suppression and Changes pane keys follow the displayed Mac.

The connection owner retains its verified Mac record. Warm picker matches and
foreground status require the exact device/build/account/origin/ticket record,
readiness, both code bindings and current permission. A same public locator in
another team cannot borrow the foreground count/status. Explicit selection of a
different record sharing the code advances retry and pins fresh admission. Browser
and sidebar workspace fallbacks also require the exact admitted record, preventing
another same-code row from displaying its data. Metadata alone is not authority;
existing saved/foreground proof and fresh-connection checks remain.

**73 focused JVM cases pass**, including three new exact-record selection/status
cases. Debug/test APKs build and five changed classes pass Android 17 ART reflection.
**Three runtime cases pass in 11.426s**: exact remembered-browser discovery, late
browser discovery respecting a newer terminal choice, and streamed-browser picker
terminal creation in its own workspace. An earlier run lost its process handle
without a test/lifecycle report; no emulator remained, and that run is unverified.
The fresh terminal run provides the authoritative result.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-pane-guards-*`. Only the existing 16 KB AVD was used
and stopped/reaped; the physical phone was untouched. Same-locator cross-team cases
are controller tests, not full real-account UI or Mac/Iroh/Pixel acceptance. Signed
654 predates this source.

Appearance and remaining task/presentation/Changes access guards still need review
before removing Settings teardown. **Team selection still disconnects the app.**
Full parity remains unverified.

## Captured foreground disconnect recovery — 2026-10-12

The screen connection now retains the admitted recovery owner and retry generation
through Activity recreation. Disconnect handling identifies the actual client,
login, code and generation instead of requiring the picker to remain on its team.
Replacement handles, login/code changes and newer retry generations ignore stale
failures. The timer rechecks ownership and selected scope before retrying; a picker
change during that delay enters selected-scope recovery. Cold connection entry also
consumes a closed client's recorded failure before starting another attempt.

Admission rejection remains held across a fresh generation of the same team, so
A → B → A cannot erase its failure without explicit retry. Other users/teams/routes
do not inherit that block. New admissions still capture the selected exact scope;
recovery metadata never authorizes a connection.

An encrypted per-user/team bookmark records only a fully admitted foreground's
origin/device/build, without its pairing code or credentials. Following a drop in
a different selected scope, recovery uses that scope's current, unhidden, permitted
saved candidate and normal fresh admission. Missing, replaced, ambiguous or
unpermitted candidates return to the selected team's computer list. Foreground
panes/routes and stale connection ownership retire; saved pairings remain.

**40 focused JVM cases pass**, including new disconnect decisions, candidate history
and same-team rejection-generation coverage. Debug/test APKs build and five changed
classes pass Android 17 ART reflection. **Three runtime cases pass in 11.091s**:
ordinary loss enters a fresh connection and retains the pane; a rejected admitted
foreground stays the same closed handle/generation through recreation until explicit
retry; and a live admitted client survives recreation and still performs RPC.
The first rejection case counted all factory connections and saw the independently
resumed feed. The repaired check explicitly proves the foreground handle, generation,
readiness and rejection block. The original run is preserved.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-disconnect-recovery-*`. The existing 16 KB AVD was
stopped/reaped; no new AVD or physical phone was used. Cross-team recovery choices
are controller/metadata tests, not complete real-account UI, Mac/Iroh/Pixel or
process-death acceptance. Signed 654 predates this source.

Remaining tab/sidebar/browser creation and presentation scopes still need integration
before removing Settings teardown. **Team selection still disconnects the app.**
Full parity remains unverified.

## Foreground notification actions — 2026-10-12

Notification reads, read/unread acknowledgements and mark-all actions now prefer
the exact admitted foreground client. Notification navigation and resume/banner
reconciliation accept that Mac's original live scope; saved/cached membership alone
does not authorize it. A navigation acknowledgement is pinned to its captured
client and cannot fall back to another feed connection. Secondary Macs keep their
existing verified feed path. Foreground acknowledgements wake an existing matching
feed without admitting a new connection.

The retained adapter serializes reads and writes, verifies current notification
IDs, and fences snapshots with server revisions. Older post-acknowledgement feeds
get up to three reads without repeating the mutation; they cannot publish old
unread state. Authorization rejection retires the client. Its revision survives
Activity recreation and resets on client/code replacement. Foreground projections
can supply newer notifications while preserving newer feed revisions and exact
saved identity. Selected-team feed filtering remains in place.

**91 focused JVM cases pass**, including seven new wire/failure cases. Debug/test
APKs build, and five changed classes pass Android 17 ART reflection. **Three actual
runtime cases pass in 12.512s**: read and mark-all through the retained client with
secondary feed paused, revision retention without replay through recreation,
Mac-specific banner reconciliation on resume, and late-background reconciliation
rejection with a later resume retry. The first runtime action case completed both
writes but failed its revision assertion because the shared fixture omitted
revisions. Optional versioned fixture responses now exercise that assertion;
the original failure is retained. Production checks were not weakened.

Evidence, XML and source/APK hashes are ignored under
`captures/runtime/foreground-notification-actions-*`. Only the existing 16 KB AVD
was used and stopped/reaped; the physical phone was untouched. These are local TCP
fixtures, not actual Mac/Iroh/Pixel, FCM/Doze or complete team-switch acceptance.
Signed 654 predates this source.

The disconnect handler still ignores a foreground failure when the picker scope
changed. Fix its captured-client recovery ownership, remaining tab/sidebar/browser
creation and presentation guards before removing Settings teardown. **Team
selection still disconnects the app.** Full parity remains unverified.

## Foreground reordering and customization — 2026-10-12

Optimistic workspace moves now accept a live foreground projection and dispatch
through the exact admitted client. The queue retains its three-request bound,
intermediate-order reconciliation, rollback and owner-removal behavior. Fresh
window, destination, pin and group-anchor checks remain. A refresh that consumes
the prediction does not turn its confirmed acknowledgement into a failure; live
connection authority is still checked after the response and refresh.

Customization uses the same admitted client and serialized owner. The existing
field-by-field save/rebase workflow keeps fresh reads, conflicts, truncated
descriptions, partial results and explicit retry behavior. Each read/write checks
owner and capabilities, and authorization rejection retires the client. Editor
keys and browser customization use their Mac's original team context. The last
accepted presentation rows remain available during an in-flight mutation; subsequent
writes independently require a fresh snapshot. Foreground projections retire on
client replacement, disconnect or lost membership/saved-record proof. Feed metadata
is merged only for the same saved identity.

**115 JVM cases pass**, including 13 foreground-wire cases and 14 move-queue cases.
Debug/test APKs build, and five changed classes pass Android 17 ART reflection.
**Three actual Android runtime checks pass in 26.027s**: reordering and terminal
creation on the existing client while the secondary feed is paused, plus offline
editor recreation/reconnect with no automatic edit replay and all four metadata
writes. Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-workspace-editing-*`. The existing 16 KB AVD was
stopped/reaped after 56.161s; no new AVD or physical phone was used. These are local
fixtures, not complete Mac/Iroh/Pixel or team-switch acceptance. Signed 654 predates
this source.

Remaining selected-scope sidebar/browser creation, notification, tab/recovery and
presentation guards still need integration before removing Settings teardown.
**Team selection still disconnects the app.** Full parity remains unverified.

## Foreground workspace and task actions — 2026-10-12

Workspace actions, terminal/browser creation, workspace/group creation and group
actions now prefer the exact admitted foreground client. Task creation also uses
that owner. A retained adapter serializes foreground mutations, resolves current
window/group destinations, keeps capability and attach-ticket checks, and checks
admission before and after asynchronous work. Authorization rejection retires the
client; rejection during the subsequent refresh cannot return success. Each write
reconciles the workspace list, including failed or uncertain outcomes, without
repeating the write. Secondary Macs retain their existing feed path.

Creation requests and saved waiters use their Mac's original team scope. Pane
creation and task/browser destinations use the foreground Mac context. The first
runtime check with the feed paused exposed a completed creation stuck in Ready:
SideEffect read the result without observing it during composition. The navigation
owner now observes that state eagerly, so completion opens the terminal without
waiting for an unrelated feed publication. The original failure and diagnostic
are preserved.

**116 focused JVM cases pass**, including eight new mutation-wire cases; debug/test
APKs build. Four changed classes pass Android 17 ART reflection. **Three runtime
checks pass in 27.548s**: creation on the existing client with secondary feed paused
and restoration without another write; a delayed pre-create refresh cannot remove
the new terminal; and explicit terminal/back navigation survives recreation.
Evidence, XML and source/APK hashes are under
`captures/runtime/foreground-workspace-actions-*` (ignored). Only the existing
16 KB AVD was used and stopped/reaped; no physical phone was touched. Task/group
wire cases are local fixtures, not full physical UI acceptance. Signed 654 predates
this source.

Reorder/customization, notification/sidebar/tab recovery and remaining presentation
scopes still require integration. Settings teardown remains enabled: **team
selection still disconnects the app**. Complete Pixel/Mac parity remains unverified.

## Foreground browser ownership — 2026-10-12

Existing browser tabs and Mac tunnel networks now retain the admitted foreground
Mac's original team generation across picker changes. Secondary networks retire;
pending browser creation is cancelled. The retained owner requires the exact saved
record, current login and live foreground proof. Losing that proof closes the
network, including while the screen composition is inactive.

Foreground browsing uses the existing admitted client's lanes and checks authority
before and after asynchronous work. Browser keys, sidebar selection and pane
rendering use the foreground Mac context. New browser creation and task operations
still require their remaining scope integration; Settings teardown remains enabled.
**The app still disconnects on team selection.**

Verification: **53 focused JVM cases passed**, debug/test APKs built, four changed
classes passed Android 17 ART reflection, and **three browser runtime checks passed
in 9.228s** (retained DOM/history/WebView through recreation, admitted native HTTP
proxy, and failed POST non-replay). The initial run exposed two outdated fixture
expectations: native RPC authorization is supplied by the authenticated admission
handshake, and dynamic text was not found through UIAutomator. Retention now checks
the DOM directly; dynamic accessibility updates remain unverified. Original failure
logs are preserved. The existing 16 KB AVD was stopped/reaped after 37.828s; no new
AVD or physical phone was used. These are local fixtures, not complete Pixel/Mac
acceptance. Signed build 654 predates this source.

Evidence and source/APK hashes: `captures/runtime/foreground-browser-scope-*`.

## Foreground Files and panel access — 2026-10-12

Files and file panels now prefer the exact admitted foreground client instead of
requiring a secondary selected-team feed connection. Existing feed access remains
a recovery fallback. The pane's original Mac/team supplies the panel and Files
keys. Foreground admission checks the exact saved identity, live client, account,
capabilities and current workspace/surface descriptor. A same public locator in
another team cannot reuse that client. Panel requests are limited to the displayed
workspace/surface/path; terminal requests keep their terminal binding and existing
same-Mac chat gallery/folder capabilities. Native artifact lanes still use the
server-issued resource capability after the authorized RPC.

Every transfer checks admission before and after asynchronous work. Descriptor,
capability, visibility, saved-record or authority changes reject old results.
Completed bytes can survive ordinary transport loss under valid saved admission;
explicit authorization/trust rejection invalidates them. A rejected foreground
artifact RPC retires its client. Foreground presentations do not hold a secondary
feed merely to keep their transfer alive.

All **108 focused JVM cases pass** (five new foreground-access, four pane-context,
nine foreground-holder, five cache-policy, 59 feed-coordinator, 13 preview-controller,
nine preview-failure and four Files-memory cases). Debug/test APKs build, and four
changed classes pass Android 17 ART loading/reflection. **Three actual panel runtime
cases pass in 28.458s**: transfer through recreation/removal, rendered reading state
through network loss/recreation/refresh, and private-byte deletion on native and
workspace-refresh revocation. The first runtime run reproduced retained bytes
after explicit refresh rejection. All rejection paths now set the cache-denial
flag; foreground access honors it immediately, and panel composition observes
feed-side denial even when foreground access wins. The original failure is retained.

Evidence, XML and source/APK hashes are ignored under
`captures/runtime/foreground-artifact-access-*`. The existing 16 KB AVD was
stopped/reaped after 60.335s on the final run; no new AVD or physical phone was used.
These use an emulator-local TCP fixture, not actual Mac/Iroh/Tailscale/Pixel or
complete team-switch UI acceptance. Signed 654 predates this source.

Still integrate browser, creation/task, notification and remaining tab/recovery
scopes before removing Settings teardown. **The app still disconnects on team
selection.** Full parity and the physical workflow remain unverified.

## Foreground pane and input scopes — 2026-10-12

The main pane now derives its saved Mac/team from the exact admitted foreground
binding, separately from selected-team computer discovery. The binding query checks
route, device/build, account/team, origin, native route and ticket revision;
renaming is harmless. A same-code record in another team cannot borrow it. The
pane context requires the active client and verified matching login/user/team;
saved/cached records cannot supply continuation, and ambiguous candidates fail.
Picker data remains selected-team scoped. For identical public locators, only the
admitted identity wins in the pane's list; the picker list is unchanged.

Pane navigation, displayed/checkpoint tab identity, workspace snapshot retention
and input/sizing owner retention use this context. Foreground workspace access
checks the exact client/record, while new admissions still use the selected scope.
Terminal input compares the complete computed owner instead of treating missing
account fields as wildcards; the admitted foreground can continue independently
of the picker. Workspace events/polling use the client's connected code, so a late
old-client event cannot fetch under a newly selected locator and mislabel its cache.

All **50 focused JVM cases pass**: four pane-context, nine foreground-holder, five
pane-navigation, ten tab-navigation, eight tab-restoration, 11 input-session and
three retained-connection cases. They include actual verified MockWebServer
membership selection, exact bound-record rejection, same public locators in two
teams, original pane/tab identity, ambiguous/closed/missing-proof contexts and old
event retirement. Debug/test APKs build in 1m25s. Evidence and source/APK hashes are
ignored under `captures/runtime/foreground-pane-scope-*`. These are controller/data
and fixture-wire tests; no emulator or Pixel/Mac workflow was run for this batch.
Signed 654 predates it.

Browser, Files/panel, creation/task, notification and remaining tab/recovery guards
still need their per-Mac contexts. Settings selection and the old selected-scope
teardown remain enabled until those owners work together. **The app still
disconnects on team selection.** Complete team-switch UI and full parity remain
unverified.

## Foreground Activity ownership — 2026-10-12

`NativeFeedSession` now owns the admitted screen connection and sizing subscription
through Activity recreation. Connection data (client/code/readiness, retry admission,
host capabilities/name, notification snapshot and terminal transport) lives in a
memory-only owner. Recreated compositions read that owner; view/touch/scroll callbacks
remain local. Repeated client assignment does nothing; replacement or ViewModel
clear closes only the previous handle and releases its foreground/input/sizing
resources. Cold process restart constructs a new owner and performs fresh admission.

The connection effect reuses a ready, live, still-authorized handle only for its
same code and admitted retry generation. Explicit retry, changed code, lost proof,
closed client or failed readiness follows normal recovery. Terminal replay and
viewport request generations now belong to the retained owner, preventing an old
composition's cleanup from clearing a newer viewport on the same connection.
The sizing event-session job belongs to the ViewModel and handles connection loss.

All **37 focused JVM cases pass** (three connection-state, ten resume, eight
foreground-holder, four recovery and 12 Mac-switch cases). Debug/test APKs build;
the final source build took 57s and Android-test corrections assembled in 21s.
All four changed classes pass Android 17 ART loading/reflection. **Three actual
Activity lifecycle cases pass in 12.688s**: identical foreground client and RPC
following recreation, closure when the Activity finishes, task-draft/background
restoration, and loaded notification-window restoration with the server offline.
The existing 16 KB AVD was stopped/reaped after 40.855s; no new AVD or physical
phone was used. These run against an emulator-local TCP fixture, not the live
Pixel/Mac or Iroh/Tailscale transport. Evidence/source/APK hashes are ignored under
`captures/runtime/activity-foreground-ownership-*`.

The first runtime run had two test setup failures, preserved separately: the new
case enabled a background-feed action without opening a foreground connection;
the existing pagination case used obsolete list keys. It now explicitly opens
and waits for foreground admission, while pagination uses the renderer's current
keys (unchanged from the prior commit). The same three cases then passed.

Per-Mac scopes for panes/feeds/browser/notifications and Settings team selection
still require integration. **The app still disconnects on team selection.** Signed
build 654 predates this source; full parity and physical workflow remain unverified.

## Legacy Tailscale foreground ownership — 2026-10-12

The legacy authority now distinguishes admitted sessions from pending connection
attempts. Only an exact registered, admitted wire can activate a single foreground
reservation under the verified account membership proof. Unknown, forged, pending
and unactivated handles cannot keep a session. The original owner/grant, captured
routing epoch, learned build and saved/external ticket guards remain required.
Selected-scope confirmation and new connections are unchanged. Foreground release
and membership, grant, visibility or routing invalidation retire the original wire.

Each pending admission has an owned child coroutine, including resolver waits.
Retirement cancels invalid attempts and closes secondary sessions without cancelling
the caller. Cancellation at the dispatcher/scope return boundary closes an acquired
client even if its result is discarded. The production screen's reservation hook,
compatibility predicate and saved-ticket guard now select both native and legacy
transport ownership. Legacy route predicates carry their own route authorization;
the authority separately enforces selected scope or exact foreground continuation.
Credential revision caching uses an atomic stamp instead of a monitor around reads.

Initial tests exposed five missing disconnect signals: explicit socket close
suppressed the root event observed by UI/foreground ownership. Retirement now
fails the root wire before disposing its view, delivering the disconnect event.
Original failure XML/logs are preserved. All **175 focused JVM cases pass**: 64
legacy-authority, five common-ticket, 14 external-route, six saved-route, 30
saved-runtime, 37 Iroh, eight foreground-holder and 11 compatibility cases. New
cases verify a manual ticket across verified team selection/return, secondary
retirement, pending resolver cancellation, membership removal/re-addition, grant
replacement, rejected reservations, external and saved ticket guards, and cancelled
result cleanup. Debug/test APKs build in 1m16s. Evidence is ignored under
`captures/runtime/legacy-tailscale-continuation-*`, with source/APK hashes and XML.
These use fixture sockets/credentials and MockWebServer memberships, not actual
Pixel/Tailscale or Mac workflow acceptance. No emulator was started.

Per-Mac panes/feeds/browser/notifications, Activity lifetime and actual terminal UI
still need integration before removing Settings teardown. **The app still
disconnects on team selection.** Signed build 654 predates this source batch.

## Initial native ticket continuation — 2026-10-12

Native `connectTicket()` now attaches the initial ticket through the same bound
client/session guard used for saved tickets. Account, ticket-route, expected-host
and selected-scope admission checks still run before binding. The returned ticket
view can continue only under the runtime's activated original foreground wire.
Failed attachment/binding closes the acquired handle; foreground ownership cannot
admit a new ticket view while that scope is not selected.

External-link admission now has separate confirmation and route-only continuation
checks. Confirmation still requires the selected generation and matching discovery.
Continuation preserves the captured native build/method/epoch or exact Tailscale
grant and routing policy, rather than resolving from the newly selected team's
list. An unresolved cold proposal cannot capture identity through continuation.
These route-only checks grant nothing without the separately bound session and
live runtime ownership. Unspecified continuation preserves the original strict
check. Native wire identity, directory grants, namespace/audience and revocation
remain enforced by its runtime. Ticket expiry/narrowing policies are unchanged.

All **175 focused JVM cases pass**: 14 external-route, 15 ticket-pairing, five
saved/common-session, 37 Iroh, 30 saved-runtime, 58 legacy-authority, eight foreground
holder and eight RPC-ticket cases. A production-runtime fixture uses the actual
attachment factory, retains the original ticket through verified team selection/
return on one wire, and rejects writes after route/foreground loss. Other cases
cover cold confirmation, method round trips, grant replacement, strict fallback
and acquired-handle cleanup. Debug/test APKs build in 1m33s. Exact source/APK hashes,
XML and logs are ignored under `captures/runtime/initial-ticket-continuation-*`.
These use fixture HTTP/credentials/wires; no actual QUIC, Pixel or live ticket
service was tested. No emulator was started. Signed 654 predates this source.

The Tailscale initial/external path still invokes its strict legacy authority:
its transport, saved-route and learned-route guards need foreground integration.
Per-Mac panes/feeds/browser/notifications and Activity ownership still need work
before removing Settings teardown. **The app still disconnects on team selection.**

## Saved primary-ticket continuation — 2026-10-12

`connectSaved()` now binds its primary-ticket guard to the one returned client.
Before binding, admission requires the exact selected scope. Afterward it can use
the native runtime's activated foreground ownership for that original account/Mac
wire. The guard checks authorization before and after its saved-record read; an
old foreground exception cannot bind a new handle or authorize a reconnect.
Binding failure closes the returned ticket view and underlying acquired handle.

The original owner, saved ticket revision, visibility and route binding remain
checked by `NativeAttachTicketStore`. The captured routing choice and configured
audience remain required. Revision caching now uses an atomic stamp instead of a
monitor around credential reads, and rejects changes during revalidation. Existing
attach-ticket expiry, narrowing, account fallback and mutation policies are
unchanged; this continuation cannot mint or widen a ticket.

All **157 focused JVM cases pass**: four new saved-ticket session, 15 ticket-store,
30 saved-runtime, six saved-route, 58 legacy authority, 36 Iroh and eight foreground
holder cases. The real saved-runtime reservation fixture keeps its original ticket
through verified account selection/return and rejects RPC before transport write
when released. Other cases cover saved record removal/hiding/replacement, logout,
route changes, failed binding, rename and unchanged expiry authority. Debug/test
APKs build in 1m13s. XML, logs and exact source/APK hashes are ignored under
`captures/runtime/saved-ticket-continuation-*`. Account HTTP, credentials and wires
are fixtures; no physical phone, emulator, actual QUIC or live account was used.

External/initial pairing ticket guards and legacy Tailscale still require their
own continuation integration. Per-Mac panes/feeds/browser/notifications and Activity
lifetime must be fixed before removing Settings teardown. **The app still
disconnects on team selection.** Signed build 654 predates this batch.

## Foreground compatibility policy tracking — 2026-10-12

The version gate now keeps tracking an already admitted foreground Iroh or saved
Tailscale wire after selected-team discovery changes. It consults the runtime's
activated exact wire and verified continuation, using a read-only atomic lease
lookup without taking the runtime monitor. A matching pooled borrower refers to
the same authenticated wire; another wire, login/user/team, unactivated or closed
reservation does not qualify. A fresh selection generation in the same verified
account/team can reuse that live wire. New admissions remain selected-team scoped.

Remote policy refresh still retires every borrower of an incompatible retained
wire with `MacUpdateRequired`. Observations and cached picker warnings remain
selected-team scoped; warning restoration cannot register or revive a wire.
Releasing ownership removes its exception, and runtime logout/revocation/route
retirement still closes the transport independently.

All **126 focused JVM cases pass**, including an actual saved-runtime reservation
with verified MockWebServer membership followed by a policy rejection after team
selection, pooled-borrower rejection, stale-admission/cache regressions and exact
Iroh/saved ownership checks. Debug/test APK assembly succeeds in 1m08s. Evidence
is ignored under `captures/runtime/foreground-compatibility-*`. These are fixture
transports, not actual QUIC, Pixel or live policy-service acceptance. No emulator
was started and no physical data was touched. Signed 654 predates this source.

Still integrate saved/external ticket continuation, legacy Tailscale ownership,
per-Mac panes/feeds/browser/notifications and Activity lifetime before removing
Settings teardown. **The app still disconnects on team selection.**

## Saved Tailscale foreground transport — 2026-10-12

Saved native identities routed over Tailscale now have separate selected-team and
foreground ownership. A verified, already admitted pooled client can activate one
foreground reservation through the native runtime facade. Selection retires
secondary wires and pending admissions; returning to the retained team reuses its
wire/manual ticket with a fresh selection generation. Grant/route replacement,
membership removal and logout invalidate continuation. Unknown, forged and
unactivated reservations cannot preserve a client. Settings construction occurs
outside the runtime monitor; a selected-team configuration failure reports an
error without retiring another team's valid foreground.

All **109 focused JVM cases pass**: 28 saved-route, 36 Iroh, 15 pool, eight
foreground-holder and 22 account cases. Debug/test APKs build in 1m16s. Original
logs, final XML and source/APK hashes are retained under ignored
`captures/runtime/saved-tailscale-continuation-*`. These use fixture wires and
MockWebServer account membership. No emulator or physical phone was used for this
batch, and no new AVD was created.

This is transport ownership support. Existing ticket guards, legacy pairing,
compatibility tracking, per-Mac pane/feed/browser/notification state and Activity
ownership still need integration before removing explicit Settings teardown.
**The app still disconnects on team selection.** Signed build 654 predates these
saved-route changes; neither live Tailscale nor full UI continuation is verified.

## Native transport ownership — 2026-10-12

The production Iroh runtime now separates selected-team discovery from an admitted
foreground wire. Each live login/user/team owns its backend and pool. The current
foreground can retain that owner under its verified continuation proof while
discovery moves to another team. Returning to that team reuses the retained
backend/endpoint with a fresh selection generation. Retiring an owner joins its
in-flight binds and endpoint teardown before a replacement starts.

Foreground reservation uses the exact already admitted pooled wire; an unknown
client, a candidate handshake or a locator cannot claim it. The reservation takes
effect only when the foreground holder accepts the verified client/Mac record and
activates it. Activation is single-use, and stale disposal cannot clear a newer
holder. Failed activation preserves the previous binding. Reservation release
signals cleanup atomically, without taking the runtime monitor inside a credential
transaction. The production feed/screen factory now supplies this runtime hook.

New admissions and management/diagnostic operations still require the selected
scope. Their child jobs capture that exact generation and are cancelled on scope
change; A → B → A cannot revive an old operation on the reused backend. A returned
client is closed if cancellation discards its result. Old-team secondary wires and
pending candidates retire; the retained foreground still checks its original
directory record, permission expiry, audience and exact dial intent. Its transport
is also bound to the specific backend/pool incarnation.

Verification: **92 focused JVM cases pass** (35 runtime, 15 pool, eight foreground
holder, 22 account, eight profile-cache and four recovery-gate cases). They include
fixture foreground RPC across selection/return with one endpoint owner, secondary
and pending retirement, membership removal/re-addition, stale delayed operations,
rejected/unowned reservations, ownership transfer, teardown ordering and typed
backend auth failure. The final debug/test build succeeded in 1m21s. Original
compiler failures and the 91/92 run are retained. The auth case reproduced generic
EOF masking the control-plane rejection; failure is now classified and delivered
before ordinary directory trimming retires the wire.

All ten changed debug classes also pass Android 17 ART loading/reflection on the
existing 16 KB AVD. The owned emulator was stopped/reaped; no new AVD was created
and no APK was installed or cleared. Exact source/APK hashes, final XML and the
ART/lifecycle receipt are retained under ignored `captures/runtime/team-switch-transport-*`.

This is the native transport layer. Saved/legacy Tailscale continuation, retained
compatibility tracking, per-Mac pane/feed/browser/notification scopes, Activity
ownership and the explicit Settings close calls still need wiring. **Android still
disconnects on team selection.** These fixture results do not prove actual QUIC,
Pixel/Mac, lifecycle UI or full parity. Signed 652 predates this source batch.

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

The authority capture below implements part of the first step. Transport and
screen retention still require the remaining ownership work.

## Authority foundation — 2026-10-12

`NativeAccountTeams` now keeps memory-only stamps for verified memberships,
separately from the selected-team generation. `captureContinuation()` can issue
a proof only for the current selected scope after online account verification;
it cannot use the offline profile cache. `allowsContinuation()` preserves that
proof during selection and ordinary transient account request failures. It
invalidates it on confirmed membership removal, principal/login replacement,
authentication rejection, clear or close. Removal and later re-addition creates
a different stamp, so the old proof cannot revive. The existing `isCurrent()`
selection/generation semantics are unchanged.

`NativeForegroundSessionAuthority` binds the original scope to the exact admitted
client and authenticated saved Mac record. The production screen captures this
after host identity, ticket, workspace and persistence verification, and its
disconnect observer uses the captured live scope separately from the selected
scope used for admission/retry. The holder belongs to `NativeFeedSession` and
reconciles account and saved-record changes. Route, ticket, ownership, visibility
or ambiguous-record replacement retires an observed binding; renaming is harmless.
Disposing an old handle cannot clear a different replacement handle. The holder
does not own or extend the backend wire, and does not replace its authorization.

The holder uses an atomic reference rather than a second monitor around account
or credential operations. This avoids introducing another lock order into the
shared account/credential transaction. Admission uses compare-and-set so a delayed
old admission cannot overwrite a newer foreground binding.

All 41 focused JVM cases pass, including controller generation/cache regressions,
holder-level RPC after selection and two deterministic concurrency checks.
Debug/test APKs build; all five affected debug classes pass Android 17 ART
loading/reflection. One existing 16 KB AVD was started and stopped/reaped in
17.763s; no new AVD or app installation/clear. Original and final XML, source/APK
hashes and ART/lifecycle evidence are in ignored `captures/runtime/team-switch-authority-*`.
The holder-level RPC uses a fixture wire and does not exercise native backend
ownership. Native
Iroh, saved/legacy Tailscale, selected-team eligibility and pane/feed teardown are
still unchanged: **Android still disconnects on a team switch**. The next batch
must keep the foreground backend/transport alive while retiring secondary and
pending work, then wire per-Mac screen/operation scopes. The continuation proof
does not authorize a new dial or prove a host grant, ticket, backend or Pixel flow.
Signed build 652 does not contain this subsequent source batch.

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
