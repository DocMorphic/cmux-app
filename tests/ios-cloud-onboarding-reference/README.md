The manual `ios-cloud-onboarding.yml` workflow compiles the three unchanged
onboarding, backdrop and environment Swift leaves pinned by
`third_party/cmux-cloud/onboarding-manifest.json`. Their Cloud, Support and Core
package dependencies are extracted from the exact Git trees without source edits.
The host includes the pinned application string catalog so `Bundle.main` resolves
the original translations. It creates no session or system VPN controller.

The UI-test process traverses all three source pages, verifies a Back round trip, completes the introduction,
relaunches and skips it. It captures `XCUIScreen.main.screenshot()` and public
accessibility controls alongside actual window, language, size category, size
class, direction, font and color observations. The 13 scenes declare 39 frames:
all nine preferred languages, light/dark, landscape, accessibility text and tablet.
Reduced Motion is observed from the real environment, not overwritten with a
private or synthetic value. The source marks its decorative visual
accessibility-hidden. The runner's public XCUI tree still exposes the identified
image envelope; that does not establish its VoiceOver reachability or a native
screen-reader result.
If the public XCUI tree exposes the source graphic, its actual frame is recorded
and checked independently; absence is retained as null. Only required copy and
controls are queried, avoiding repeated enumeration of every descendant. The
initial interrupted run produced diagnostic logs but no finalized scene exports
and is excluded. No private quiescence or source animation override is used.
The completed second run's first association failed because its nominal 152/88
dimension check omitted the centered one-point source stroke. Actual XCUI bounds
are 153/89 points, with up to one physical pixel of quantization. The corrected
verifier retains that dimension fence and successfully reassociates the same
immutable 39 images locally; it does not rerender or discard the measurements.

`verify-ios-cloud-onboarding.py` associates every scene, page and image with its
immutable source, fixture and host. Its adversarial synthetic unit inputs only
test export rejection rules. A local Swift parser or generated Xcode project is
not an iOS build or UI result. A successful workflow is still limited source UI
evidence: full CloudUI, connected services/VPN, Android, physical devices,
VoiceOver/TalkBack and full app parity require their own verification.
