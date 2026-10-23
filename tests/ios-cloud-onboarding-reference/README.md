The manual `ios-cloud-onboarding.yml` workflow compiles the three unchanged
onboarding, backdrop and environment Swift leaves pinned by
`third_party/cmux-cloud/onboarding-manifest.json`. Their Cloud, Support and Core
package dependencies are extracted from the exact Git trees without source edits.
The host includes the pinned application string catalog so `Bundle.main` resolves
the original translations. It creates no session or system VPN controller.

The UI-test process traverses all three source pages, completes the introduction,
relaunches and skips it. It captures `XCUIScreen.main.screenshot()` and public
accessibility controls alongside actual window, language, size category, size
class, direction, font and color observations. The 13 scenes declare 39 frames:
all nine preferred languages, light/dark, landscape, accessibility text and tablet.
Reduced Motion is observed from the real environment, not overwritten with a
private or synthetic value. The source hides its decorative graphic from AX;
the observer does not claim that graphic's rendered frame from nonexistent AX.

`verify-ios-cloud-onboarding.py` associates every scene, page and image with its
immutable source, fixture and host. Its adversarial synthetic unit inputs only
test export rejection rules. A local Swift parser or generated Xcode project is
not an iOS build or UI result. A successful workflow is still limited source UI
evidence: full CloudUI, connected services/VPN, Android, physical devices,
VoiceOver/TalkBack and full app parity require their own verification.
