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
