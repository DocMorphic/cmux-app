## Localized read-only Plan UI and retained window — 2026-10-23

Implementation `5afe6886` localizes the existing read-only Plan screen and
Settings row. Eighteen exact native labels (162 values across nine languages)
come from the a5911a1f iOS app catalog; seven Android browser/recovery labels
are separately project-authored. Resource generation/checking remains separate
from the older general-label dictionary. Known plan names, billing sources,
loading, legal links and semantic error states use the caller's current locale;
retained failures no longer store English presentation strings. Header actions
have explicit 48 dp bounds. Measured label/value widths select horizontal or
stacked layout, avoiding the large German word-fragmentation found in review.

The pinned official BOM selects Compose UI 1.9.1. Its source remembers a dialog
wrapper by view/density but keys show() to Unit; actual controlled density
replacement left the new Plan window unshown, even after a five-second wait.
Official 1.9.2–1.9.4 sources retain that logic. The Plan call now restarts on
inherited-density changes, with its presentation/account/error owner and real
scroll state above the replacement. No global dependency/dialog change was made.
The exact source/BOM artifacts and hashes are in
`captures/recovery-20261022/compose-dialog-1.9.1/root-cause.json`.

Final owned-emulator `account-plan-locales-v8` passes all six cases in 23.558 s
on a 260 dp viewport: 18 loading and 18 loaded locale/scale profiles, existing
web/Apple/team actions and simulated browser return, localized retained load/
browser recovery, and actual positive scroll across density/window replacement.
All nine large profiles report actual layout scale 2 and larger rendered line
heights; header targets/order and current label/value layouts pass. German
current labels remain one line at both scales. The window case retains the
same plan object and two account reads; its 798 px offset clamps to the new
680 px maximum rather than resetting. Twenty-six compositor frames are retained;
final enlarged German/Arabic captures were reviewed. Full JVM suite: 3,112 passes,
four opt-in skips, zero failures/errors (3,116 total, 423 XML reports). Eight
existing localization guards and generated-resource checks pass. Tested sources
and both APKs are archived, hashes revalidate, crash buffer is empty, restore
commands succeeded and the owned emulator stopped. Earlier fixture/window/
target failures and the green-but-visually-rejected v7 are excluded from final
UI acceptance. Checkpoint: `account-plan-locales-checkpoint.json` in that folder.

The 14-file Billing package delta from 186cec79 to a5911a1f is now reviewed:
monthly/yearly selection and fallback, yearly equivalent prices, account-matched
Apple entitlements/refund targets, product-unavailable/retained-offer recovery,
and purchase/product/refund reporting. These still depend on actual store and
server integration; the new period capability header belongs to the Apple route,
not Android's existing personal-plan GET. Android products/publisher/server
verification, purchase/restore/refund and accepted analytics remain unresolved,
not waived platform differences. A project-identification question is pending;
no backend/purchase/analytics request or enablement occurred. Receipt:
`billing-a591-detail-reconciliation/receipt.json`. No global source pin advanced.

This is production-component evidence with controlled local contexts/density,
not actual OS locale/font changes, authenticated MainActivity, process restoration,
matched native paywall, physical account/browser or full accessibility acceptance.
Other dialogs and broader release/backend/source gates remain open. Full parity
stays active. Pixel remains released at last verified signed 712; the phone
availability question is pending. Local signed 714 remains unpublished and
predates this Plan change and its notice update; no newer signed build exists.
Do not operate the phone on the strength of old availability replies.

## Backend and source delta reconciled at a5911a1f — 2026-10-22

The personal plan GET route at a5911a1f92f3eeb11c4d7f3e2ef89204718c5009
retains the exact subscriptionPlanId/personal versus explicit-team schemas used
by Android. The scoped diff from 186cec79 has no change to that route or
teamPlanStatus.ts. The broader billing delta has 373 additions/25 deletions
across fifteen files, including product periods, offer presentation and purchase
reporting, so this is not complete iOS billing/UI delta acceptance.

A source search over web, workers and CmuxMobileBilling finds no Google Play /
Android Publisher / Play Billing verification integration. Its purchaseTokens
hits are StoreKit fake-client UUIDs, not Google Play purchase-token handling.
The API inventory supplies Apple account-token/transactions/notifications and
web billing routes. Android product, publisher, verification/entitlement and
native purchase/restore requirements therefore remain unresolved and are not
waived as platform differences. No transaction, entitlement mutation, API probe
or financial account action was performed. Source receipts are in ignored
`captures/recovery-20261022/backend-a591-reconciliation`; global pins are unchanged.

# Account plan and subscription settings

Scoped source: `186cec79781256867ad4516f0802118738bd2393`. The global parity pin
is unchanged. Reviewed:

- `Packages/iOS/CmuxMobileBilling/Sources/CmuxMobileBillingUI/`:
  `MobileSettingsPlanSection.swift`, `MobilePlansView.swift`, `BillingPlanCopy.swift`.
- Billing account/current-plan models and `HTTPBillingAPI.swift` in that package.
- `web/app/api/billing/plan/route.ts`, `subscription/route.ts`, `portal/route.ts`,
  and `web/services/billing/pro.ts`, `teamPlanStatus.ts`, `apple/accountToken.ts`,
  `apple/config.ts`.

## Source contract and Android implementation

iOS Settings shows a Plan summary for an authenticated billing account. Its Plans
screen shows the current plan, billing source, offers, load/retry state, legal
links, and StoreKit purchase/restore/manage controls. The iOS account-token POST
creates an Apple purchase identity and selects products by Apple bundle and
environment; Android does not call that endpoint or submit Apple receipts.

The server also supports `GET /api/billing/plan` with native bearer and
`X-Stack-Refresh-Token` headers. Its `subscriptionPlanId` is the exact personal
plan; legacy `planId` is only free/pro and must not override Go or Max. The response
includes authenticated status, user identity, billing source and management kind.
Its explicit `?teamId=` response is a different schema. Android currently reads
the personal plan only; it does not label the implicit team status as the selected
team's subscription or infer billing permissions from Cloud machine limits.
Implicit team coverage explains admin management and suppresses personal web
offers; an active personal web-billed plan retains its own management controls.
These display rules do not grant billing permissions. Server checkout remains
authoritative, and the source's exact StoreKit eligibility/offer protocol is not
implemented by this personal-plan GET.

Settings now exposes Plan and a native Plans dialog. Loading, refresh, retry,
current-plan/source labels and fixed browser destinations are implemented. It
refreshes when returning from an opened browser page. The existing authenticated
Cloud HTTP transport supplies coherent credential snapshots, bounded responses,
no cookies, no redirects, cancellation and account/team retirement fences. The
plan response must identify the current user. Neither decoded account data nor
tokens are stored in the dialog's saved state. Failed refresh shows a last-loaded
plan label; an expired session clears that last-loaded plan.

Web-managed subscriptions open `https://cmux.com/dashboard/billing`; the offers
button opens `https://cmux.com/pricing`. Apple subscriptions use the source's
fixed `https://apps.apple.com/account/subscriptions` destination and do not offer
a duplicate web purchase. Server-provided arbitrary management URLs are ignored.
These external pages may require their own browser sign-in. This client sends no
purchase, cancel, resume or subscription-switch request. The plan GET may reconcile
existing server billing metadata as described in the server implementation.

## Remaining requirements

Native Android purchase/restore parity is **not implemented**. Google Play products,
publisher configuration and a supported server verification/entitlement path must
be established. This is an unresolved integration requirement, not an asserted
unavoidable platform difference. The authenticated live plan endpoint, actual
browser sign-in/management and account/team changes need physical verification.
No paid transaction is part of the fixture checks or authorized by this change.

## Verification — 2026-10-07

- **16 distinct JVM checks passed across the builds**: ten existing Cloud HTTP
  ownership/auth/cancellation tests and six account-plan cases. The first run
  had five plan cases; a later run passed all six after adding team coverage.
  Checks cover exact Max/Go versus legacy free/pro, invalid identity/schema,
  fixed destinations, team management, actual GET/header contract, safe errors,
  signed-out data removal, superseded results and retirement.
- Debug and Android test APKs built. **Three Android cases passed in 20.963s**
  on the single API 37 / 16 KiB AVD at 1,536 MiB. They exercise the actual Plans
  composable with generated account states: retry, current-plan replacement,
  fixed web/Apple link callbacks, team controls and lifecycle-triggered refresh
  after simulated browser return. The browser launcher is injected; no actual
  external sign-in or subscription management is claimed.
- All three screenshots were inspected. Boot/final ANR/crash event logs are
  empty. Gradle stopped; emulator stopped/reaped. Receipts, logs, final plan JVM
  XML and screenshots are in ignored `captures/runtime/account-plan/`.
- The live native-account Settings wrapper, production endpoint and Pixel/Mac
  flows have not been exercised in this batch. No signed APK or purchase was made.
