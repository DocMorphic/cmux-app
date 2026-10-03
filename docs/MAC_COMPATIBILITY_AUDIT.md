# Mac compatibility policy audit — 2026-10-03

## Scope

Read-only comparison against cmux 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
This does not advance the global parity pin or implement an admission policy.
The source was inspected while signed build 474 ran; that APK's source remains
c45920e8ba20c036bd3ca7c11491a6e03c42d763.

## Authorities in iOS

| Source | Contract |
| --- | --- |
| CmuxMobileShell/MobileMacBuildCompatibilityPolicy.swift | Official builds admit exact default/nightly/rc tags and official namespaces; development builds admit their matching development tag plus explicitly granted development tags. Staging is not an official allowed tag. Missing tags are rejected except a narrowly authorized legacy Tailscale version window. |
| CmuxMobileShell/MobileShellComposite+BuildCompatibility.swift | Authenticated host admission first applies channel/tag policy, then the minimum-version policy. A stricter refresh revalidates and disconnects an incompatible foreground Mac. Registry and presence projections also apply the build audience policy. |
| CmuxMobileShell/MobileMacCompatPolicy.swift | GET /api/mobile-mac-compat supplies ordered, version-tiered requirements with per-build-kind overrides. Fully invalid payloads retain the last good policy; a valid empty policy explicitly lifts constraints. Uncovered app versions are unconstrained. |
| CmuxMobileShellUI/MobileMacCompatCenter.swift | Cache is isolated by API origin and complete app build identity. Startup uses cached or baked policy; refresh is non-blocking. Old environment/build entries are pruned. |
| CmuxMobileShellModel/MobileMacVersionCompatibility.swift | Stable and nightly grammars differ. Missing/unparseable constrained versions are outdated. Nightly compares numeric base then unsigned 64-bit build; stable rejects nightly grammar. No applicable minimum is unconstrained. |
| CmuxMobileShell/MobileMacCompatPolicy+Channel.swift | Version floors cover default/legacy stable and nightly. RC, staging and development tags are outside this particular version-floor policy, separately from build-audience admission. |

The inspected baked iOS policy uses minimum stable 0.64.25 and nightly
0.64.25-nightly.3522337919701 for its production lane. These are facts about the
pinned source, not independently verified current service values or an established
Android compatibility minimum. Older beta/internal lanes have different floors.

## Android findings at c45920e

- NativeMacBuildLabel is explicitly cosmetic; it does not implement admission.
- NativeMacPresenceInstance retains identity, bundle ID, online and last-seen,
  but no advertised app version or version requirement.
- NativeScreen's authenticated host handling verifies device/build identity and
  capabilities, but does not implement the reviewed minimum-version policy.
- The native source tree has no mobile-mac-compat loader, build-scoped cache,
  minimum-version comparison or corresponding row warning.
- PhonePushKeys reads the authenticated Mac namespace for push key exchange;
  that use is not a complete connection admission policy.

An icon added to a row would leave these underlying behaviors missing.

## Implementation work to continue

1. Define an explicit Android protocol compatibility profile against the vendored
   transport/protocol era and reviewed upstream tier. Android's own 0.2.0 is not an
   iOS marketing version; passing it directly to the iOS tier selector would
   silently select no tier. Do not silently rename the Android release or claim
   official iOS provenance. Verify the actual API schema and applicable upstream
   version/build configuration before selecting the profile.
2. Port the exact comparison and strict complete-payload parsing contracts,
   including stable/nightly separation, integer bounds, max-version exclusion,
   build-kind consistency, invalid payload retention and the valid empty policy.
3. Add bounded loading and origin/profile/app-build-scoped cache with offline
   fallback. Treat policy data as data, not authority to alter accounts/routes.
4. Apply the selected policy consistently to authenticated foreground and saved
   connections, retained/background peers and policy refresh. Preserve current
   account/device/build ownership and route checks. Capability negotiation alone
   does not reproduce the upstream version floor.
5. Project update guidance onto visible/hidden/reconnect rows and onboarding;
   retain enough display metadata for offline warning state without confusing
   presence claims with authenticated host proof.
6. Verify malformed/empty/offline policies, stable/nightly boundaries, legacy
   local Tailscale exception, stricter-policy retirement and sibling/account
   isolation. Exercise actual stable and nightly Macs before claiming parity.

The development-audience allowlist, full discovery/presence filtering and exact
warning layout require additional scoped source review. This audit is actionable
research, not evidence that any of those behaviors passed Android tests.
