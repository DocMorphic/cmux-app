# Mac pairing setup guidance — 2026-10-03

The sign-in screen and Computers picker now open a dedicated setup guide. It uses
the official iOS source's unmodified dark/light Mac Settings images and the actual
Mac control label, **Enable iOS pairing**, with an explanation that this setting
also enables the Android companion. The guide describes same-account/team setup,
automatic Iroh discovery, and the alternative Tailscale pairing-code path.

The minimum stable Mac version comes from the same current policy selected for
connection admission. A policy with no applicable requirement omits the version
sentence. The download action uses a fixed official release URL, independent of
remote policy text. Opening help does not enable the Mac listener, change a
connection method, scan a code, or establish a connection.

For signed-in users, Find my Mac dismisses help and refreshes the existing account/
discovery path. The Tailscale action reveals the existing scan/paste controls. On
the sign-in screen, that action is disabled and the primary action returns to
sign-in. Closing help preserves the pasted pairing text. Help visibility, pairing
options and draft text use saved-instance state scoped to the displayed account
and team; they reset when that display identity changes.

## Source review

Reference: cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:

- `OnboardingPairingView.swift`
- `OnboardingPairingSettingsScreenshot.swift`
- `OnboardingConnectionView.swift`
- `OnboardingFlowView.swift`, `OnboardingStage.swift`, `OnboardingConnectionPhase.swift`

Images are copied byte-for-byte from the OnboardingPairingSettings image set.
Upstream copyright and GPL attribution are in NOTICE.md and the in-app notices.
The global parity pin remains unchanged.

### Correction to the earlier presence-version work item

At this source revision, `PresenceInstance.swift` contains identity, display name,
bundle, capabilities, online/last-seen timestamps and routes, but no app-version
field. `RegistryDevice.swift`/`RegistryAppInstance` also has no version field.
`MobileShellComposite+BuildCompatibility.swift` evaluates versions obtained from
authenticated host status. The previous tracker suggestion to add version metadata
from presence was unsupported by these DTOs and is withdrawn. Android continues
using authenticated host versions and their saved history. No invented presence
field or presence-based admission has been added.

## Verification

37 focused JVM tests passed (compatibility policy/gate and presence reducer/runtime).
Debug and instrumentation APKs built in 1 minute 23 seconds. Four Android UI tests
passed in 40.686 seconds: the two new help tests and two existing reconnect tests.
Screenshot review found a dialog system-bar contrast issue; the fix built in
52 seconds and both help tests passed again in 23.841 seconds.

The help tests cover signed-out restrictions, fixed download navigation, policy
changes including the unconstrained state, saved-instance restoration of open
help and a pasted code, and exactly-once refresh/pairing callbacks. Final dark and
light screenshots were reviewed. The production MainActivity was also launched
signed out, its setup link opened, and the actual app-theme screenshot inspected;
Back returned to sign-in. Production navigation-bar contrast is correct (the
bare test Activity has different navigation-bar defaults).

All runs used the existing API 37 / 16 KB AVD, which was stopped afterward. No new
AVD was created. The initial boot showed a System UI ANR before instrumentation;
Wait was selected and all tests subsequently passed. This is not physical Pixel
or whole-app rotation evidence: state restoration is emulated by the Compose test.
Evidence lives under captures/runtime/pairing-help.

Final debug SHA-256: 984b32f01b342d5a9f59adc19b8d7ebe9371b2e12a20c176a54c88271b954739.
Final test SHA-256: 05f98982fac3641cd54736863e6726a52b476be06159ddf631907307e2ce930f.
Both Settings images match the upstream blobs byte-for-byte; sizes and SHA-256s
are recorded in the local assets.json evidence. The source/asset attribution is
included in the APK notices.

## Remaining onboarding work

This guide remains reusable independently of the new
[five-stage introduction](ONBOARDING.md). The introduction now adds persisted
progress, connection phase UI, method choice, Settings replay and the connected
keep-awake offer. Physical Mac/Pixel onboarding, rotation and configured push
acceptance remain open. See the feature document for exact verification scope.
