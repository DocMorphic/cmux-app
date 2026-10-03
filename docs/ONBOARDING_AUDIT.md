# Full onboarding continuation audit — 2026-10-03

Reference: cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`. This is a scoped
source review for the next implementation, not evidence of full Android parity.
The reusable [Mac pairing guide](PAIRING_SETUP.md) is already implemented.

## Required flow

| Requirement | Upstream source / behavior | Android continuation |
| --- | --- | --- |
| Post-sign-in gate | MobileOnboardingGate: authenticated, restoration settled, progress not complete; a live connection does not suppress unfinished onboarding | Observe the existing account controller; cached display identity alone must not admit onboarding network work |
| Durable progress | MobileOnboardingStore: per-install defaults key for this design; welcome, connect, complete; invalid/missing values start at welcome; forceComplete ignores writes | Add a versioned progress store and a test-only bypass without modifying real install state |
| Stages | OnboardingStage: agents → notifications → push → pairing → connect | Build all five scenes, retaining setup help as reusable pairing content |
| Back / Skip | OnboardingSceneChrome: Back except agents; Skip only agents/notifications/push | Keep these distinct from Back/cancel in an open pairing dialog |
| Push opt-in | Enable invokes one async request; Not Now has no OS side effect; racing second actions are ignored; grant or denial advances to pairing only if still on push | Use Android permission/provider state honestly. Permission granted is not proof of configured FCM delivery |
| Pairing prerequisite | Primary: “I've enabled iOS pairing”; no Skip; enters connect | Reuse original Mac Settings asset and exact toggle wording |
| Connect entry | onReachedConnection once per flow instance; authentication becoming valid on connect triggers it again | Persist connect milestone, scope request to current account and preserve across configuration changes |
| Connection phase | ready outranks searching; otherwise idle until search finishes, then fallback | Derive from actual connection/search state; directory presence alone is not readiness |
| Automatic method | idle: Check for My Mac; searching: no primary; fallback: Check Again; ready: Open Workspaces | Drive existing verified Iroh discovery and foreground connection |
| Tailscale method | idle/fallback: Scan Pairing Code; fallback has Check Again secondary; ready still Open Workspaces | Use existing explicit QR/paste consent and authenticated grant path |
| Method selection after ready | Method picker stays visible; choosing Tailscale while already ready starts pairing instead of finishing | Keep method choice distinct from onboarding completion |
| Cancellation | Unfinished progress keeps onboarding visible; canceling QR returns to connect | Preserve stage, route choice and pending pairing state without erasing existing connections |
| Keep-awake offer | OnboardingKeepAwakeOffer only for connected, supported and known state; mutation names the exact settled Mac/device/tag | Use NativeMacPowerSession and existing ownership guards; hide unknown/unsupported state rather than offer an inert switch |
| Complete / Skip | Explicit callbacks persist completion; successful connection alone is not automatic dismissal | Keep completion action reviewable; avoid replaying the tour after completion |
| Settings replay | Starts at agents with replay context; Skip/Complete dismiss without writing first-run progress; Tailscale dismisses replay before opening the scanner | Keep replay state separate from durable progress and retain current account/connection guards |
| Paging and accessibility | Swipes and buttons commit the same stage; inactive pages cannot receive touches or accessibility focus; resumed connect page has an explicit initial scroll anchor | Use one navigation state, restore the actual visible page, honor reduced motion and hide offscreen semantics |
| Early discovery | Root runs discovery only for a settled account, active scene, welcome milestone and no connected Mac or injected attach route; attempts share startup coordination | Reuse existing connection ownership; do not create a competing onboarding dial loop |

## Root and push lifecycle details

The root distinguishes a real account session from temporary attach-ticket
authentication. Attach-ticket authentication can enter the shell to finish its
connection but does not enter first-run onboarding. A cached identity during
restoration must not briefly enter the tour. Entering connect persists that
milestone, sets searching before the asynchronous reconnect begins, and clears
the pending-search marker when the attempt ends. Readiness comes from a connected
Mac, not merely a directory result.

The pre-connect discovery owner is keyed by user and team. Losing authorization
or switching that identity hard-cancels the old loop. Each attempt and re-arm
checks live eligibility, shares the startup reconnect claim, and backs off from
4 seconds by a factor of 1.5 to a 15-second ceiling (claim contention retries in
1 second). Moving to connect, connecting, completing the tour or leaving the
active scene disables further searches. Android should adapt these ownership
semantics to its existing lifecycle rather than blindly copy another scheduler.

The iOS push coordinator separates user opt-in, current OS settings, device token
and backend registration. The first undetermined permission prompt belongs to an
explicit onboarding Enable action or Settings toggle; merely showing workspaces
does not request it. Workspace visibility may recover registration when permission
already allows delivery, but preserves an explicit app opt-out. Foreground entry
refreshes settings because OS permission can change while suspended. Android must
also distinguish permission from Firebase configuration, token registration and
working Mac delivery. A successful permission result alone cannot say push works.

## Existing Android integration points

- `NativeScreen` already observes the shared account/team controller and maintains
  incoming pairing/deep-link precedence. Its `signedIn` value starts from stored
  credentials; this value alone is insufficient for the settled-account gate.
  `NativeAccountTeamsState.cached`, `loading` and current verified ownership must
  be considered before the tour can initiate account-scoped work. Do not suppress
  account/team recovery controls behind an onboarding gate that requires those
  controls to succeed.
- The existing notification permission launcher enables
  `NativeNotificationService`, a foreground connection service. Its durable
  `background_enabled` setting is not a Firebase registration receipt.
  `PhoneFcmService` implements data-only encrypted ingress and explicitly leaves
  Firebase project/token registration disabled. Keep these states distinct in
  onboarding copy and callbacks.
- `NativeMacPowerSettings` and `NativeMacPowerSection` already expose scoped live
  power state and guarded mutations. Reuse the session and ownership checks for
  the final page's keep-awake offer.
- `NativePairingHelp` supplies original light/dark Settings screenshots and policy
  copy. Extract reusable scene content if necessary while retaining its existing
  signed-out help entry point, saved drafts and tested dialog behavior.

## Scene layout reference

The first three scenes introduce workspace tracking, the combined notification
feed, and lock-screen alerts/replies. They use production UI screenshots, not
interactive controls pretending to be a connected Mac. Android illustrations
should reflect the actual Android UI and available notification configuration.

`OnboardingSceneContainer` keeps one shared header and footer around the pager.
In portrait, scene copy is centered above a bounded visual (24-point horizontal
insets); compact-height layout places copy and visual side by side. Large
accessibility text avoids the regular-width horizontal layout. The footer stacks
actions in portrait and places them side by side in compact height. A hidden
secondary-action slot reserves space to keep the primary button aligned across
pages, but cannot receive focus or input. Android needs equivalent reachable
actions and readable copy at large font scales rather than blindly fixed heights.

## Reviewed sources

- CmuxMobileShellModel: MobileOnboardingStore.swift, MobileOnboardingProgress.swift.
- CmuxMobileWorkspace: MobileOnboardingGate.swift.
- CmuxMobileShellUI: OnboardingStage.swift, OnboardingFlowView.swift,
  OnboardingSceneChrome.swift, OnboardingConnectionPhase.swift,
  OnboardingConnectionView.swift, OnboardingKeepAwakeOffer.swift,
  OnboardingPairingView.swift, OnboardingPairingSettingsScreenshot.swift,
  OnboardingContext.swift, OnboardingPageViewport.swift,
  OnboardingAgentsView.swift, OnboardingNotificationsView.swift,
  OnboardingPushView.swift, OnboardingSceneContent.swift,
  OnboardingSceneContainer.swift, OnboardingSceneFooter.swift,
  OnboardingMacDiscoveryKeepAlive.swift, CMUXMobileAppView.swift,
  CMUXMobileRootView.swift, MobileSettingsView.swift,
  MobilePushCoordinator.swift (explicit enable and workspace-visible recovery).

Before implementation, map these transitions onto the Android account, connection
and notification owners and finish the connection/pairing layout comparison. The source review
does not authorize new analytics collection or automatically enable Mac
listeners. Existing user pairing authorization remains in force for device tests.

## Acceptance to establish

Use real state transitions for signed-out/restoring/cached/verified accounts,
permission grant/deny/cancel, discovery failure and retry, Iroh success, Tailscale
fallback cancellation and success, method change after ready, exact-Mac keep-awake
success/error, Back/Skip, configuration restoration, process restart and Settings
replay. Inspect portrait/landscape and large-text rendering. Physical Pixel/Mac
pairing and working push remain required for the full goal; fixtures cannot prove
them. Keep the current known signed APK available while this work proceeds.
