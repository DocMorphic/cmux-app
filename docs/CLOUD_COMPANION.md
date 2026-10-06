# Cloud companion parity

## Source audit — 2026-10-06

Scoped upstream revision: `c2715faa02c260b07012bc0b386597cfb333021d`.
The global parity pin is unchanged. This is evidence about that source tree,
not proof of the version currently distributed through the App Store.

The Cloud packages are wired into the iOS application, not just unused libraries:

- `ios/cmux/cmuxApp.swift` supplies the active Iroh installation device ID.
- `ios/cmuxPackage/Sources/cmuxFeature/CMUXMobileRootScene.swift` constructs
  `MobileCloudComposition`, holds the controller/bridge, supplies the Cloud tab,
  and connects Cloud workspaces to the shared workspace store.
- `MobileCloudComposition.swift` configures live coherent Stack tokens and the
  resolved team, private identity storage, a tunnel starter and terminal connector.
- `MobileAuthComposition.cloudAPIBaseURL` selects `https://cmux.com` in production;
  development uses its configured remote origin or the staging fallback.
- `CloudPrimaryTabView.swift` mounts the Cloud flow and manages scene lifetime.
- `CmuxTerminalClientCloudAdapter.swift` uses Rust `WireGuardNet` and
  `TerminalClient`, not the paired Mac's mobile RPC. It lists/creates workspaces
  and terminals, attaches output, sends input, resizes and disconnects.
- The optional system VPN is separately keyed for browser/private-port access;
  its UI is conditional on an embedded packet-tunnel extension. It is distinct
  from the in-process terminal tunnel.

The root owns account/team reset, restoration, foreground/background transitions,
the workspace bridge and a shell lease while the account has machines. A user
with no Cloud machines does not automatically enroll a terminal tunnel.

## Implemented API foundation

`CloudModels.kt`, `CloudApi.kt`, and `NativeCloudAccount.kt` now cover the scoped
`CloudMachine`, `CloudAPIRequestBuilder`, `CloudAPIResponseDecoding`, and
`CloudVMService` contracts:

| Operation | Wire contract |
| --- | --- |
| Catalog | GET `/api/vm`, machines, image kinds, plan/memory limits and resource pool |
| Create | POST `/api/vm`, retained caller-supplied Idempotency-Key, kind/provider/image/home/memory options, 16-minute deadline |
| Pause/resume/delete | POST pause/resume subpaths or DELETE machine; resume gets 16 minutes |
| Tunnel enrollment | POST `/api/vm/tunnel`, saved registry device ID, public key, fingerprint, terminal/browser purpose and name |
| Tunnel revocation | DELETE `/api/vm/tunnel`, fingerprint and purpose |
| Terminal attachment | POST machine attach-endpoint with `cmux-remote`, fingerprint and bounded capability slugs; 90 seconds |
| Invitation approval | POST machine cmux-remote/approve with invitation ID |

Requests carry one captured account/team's access/refresh pair and optional
`X-Cmux-Team-Id`. The native factory uses the existing account's coherent web
session snapshot and the exact verified team generation. No redirects, shared
cookies/cache, authentication retry or connection retry; responses are bounded
to 2 MiB even when chunked. Closing a client cancels pending calls. Preflight and
postflight ownership checks reject old successes, HTTP failures and refresh
failures. The future controller must close the client when its owner retires.

Machine IDs are encoded as one path segment. Unknown lifecycle states retain the
row while disabling unsupported destructive actions. Missing capability data
stays different from a supported empty set. Only an explicit Boolean
`trustedCarrier: true` permits carrier trust; missing invitations do not imply it.
Tunnel configuration, invitations and credentials use redacted object descriptions.

This is callable application code with a native account adapter, but it is **not
yet mounted in the UI or started by a controller**. No live account requests,
machine creation/change/deletion, tunnel enrollment, VPN activation or cloud
provisioning were performed. The terminal-sizing UUID is not a substitute for
the Iroh device-registry ID required by Cloud enrollment.

## Verification

**15 JVM checks passed**, zero failures/errors/skips: five model/decoder checks,
ten request/HTTP/ownership checks. MockWebServer coverage includes exact headers,
encoded paths, no cookies, refusal to follow redirects to a second listener,
mutation failure without retry, stable create identity, owner changes during
token acquisition and response delivery, rejected stale refresh errors, owner
closure/cancellation and an oversized chunked body. Pure checks cover machine
names/lifecycle/resources/limits, separate tunnel roles, capability sanitation,
required fields and strict carrier trust.

Initial main compilation and 14 checks passed in 24 seconds. Adding the native
adapter and stale-token-error regression produced the final 15-check pass in
7 seconds. Evidence: `captures/runtime/cloud-api/`. No APK/device run, emulator
or signed promotion was needed at this foundation checkpoint.

## Required continuation

1. Port the controller's owner/operation gates, list refresh and reconciliation,
   machine creation idempotency persistence, lifecycle actions and retry states.
   Connect the current native account factory and close clients on retirement.
2. Expose the existing Iroh registry identity to Cloud; persist separate terminal
   and browser WireGuard keys, pending revocation, and validated wg-quick routes.
3. Audit/build the Android Rust/JNI terminal client and in-process WireGuard
   transport with 16 KiB-compatible libraries. Do not route Cloud daemon traffic
   through the paired Mac RPC. Port invitation/trust and output-reducer contracts.
4. Project Cloud machines/workspaces/terminals into the retained Android navigation
   and terminal owners. Handle concurrent Mac/Cloud sessions, local scroll/resize,
   replay, input ordering, early input and lifecycle without cross-owner leakage.
5. Port Cloud onboarding/list/create/resource/plan/error screens and their real
   actions from CloudFlowView/CloudSectionView, then compare visible iOS states.
6. Investigate Android system-VPN private-port behavior alongside the user's
   Tailscale setup. Any unavoidable platform constraint needs concrete evidence.
7. At integration milestones, test the transport and screens against controlled
   fixtures; then verify authorized real-account/Pixel workflows, background and
   process recovery, account/team changes, revocation and signed upgrade.

Cloud notification behavior and any further workspace/file/task support require
the remainder of the upstream source audit. These are open requirements, not
exclusions from full parity.
