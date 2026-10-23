# Original iOS Full text sheet reference

This target compiles 22 whole files pinned at upstream `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`:
original `AgentFeedFullTextView`, its real model, the embedded/native/web renderer
declarations, their reachable Markdown helpers and both complete string catalogs.
There are no substitute model types, modified original views, extracted method
bodies, fake web views or disabled original branches. The native rendering branch
is selected by the unchanged original Feed view. Web declarations compile but are
not exercised, and no viewer assets are bundled in this offline host.

`OriginalSheetAdapter.swift` is an authored public entry point into the original
internal view, with a synthetic immutable item and caller-supplied loader.
`ReferenceApp.swift` supplies an offline sheet presenter, deterministic loading /
failure-then-retry results, URL interception and public UIKit/SwiftUI observation.
The actual original NavigationStack, title, Close, retry and presentation modifiers
remain untouched. The observation reports actual sheet interface level; compact
height adaptation must not be assigned an invented elevated/base requirement.

The independent XCUITest process captures 52 compositor images: loading, error,
loaded and dismissed states for 13 declared locale/style/orientation/device/type
profiles. It verifies loading cancellation on Close, one retry producing a second
load, real localized controls and the absence of an authored Source toolbar.
`verify-ios-feed-reader.py` binds every image to its exact whole source, adapter,
package/preparer, host/test/fixture, clean Git commit, runtime, public traits and
AX target. Synthetic verifier guard images are not source UI evidence.

Prepare with `scripts/prepare-ios-feed-reader-reference.py`; run the
`ios-feed-reader.yml` workflow where the pinned Xcode SDK family is available.
Source and adapter hashes are recorded before capture. Retain failed attempts.

This reference does not instantiate original accounts, transport, sessions or the
production URL router. Passing it does not prove Android acceptance, real-device
behavior, whole app appearance or full parity. Review its actual captures before
using them to adjust Android's full-sheet controls, errors, title and palette.


Verified capture: run [39961701278](https://github.com/DocMorphic/cmux-app/actions/runs/39961701278)
at a0b825d5 passes all three methods and the complete 52-image association.
CI/local receipts and hashes match. Retain the failed prior selector attempts.
This is a component reference with an authored presenter, not production app
appearance acceptance. Actual title collision allocation, bounded toolbar
growth, tablet placement and material/context effects must guide Android work;
do not substitute a universal centered-title or semantic-color assumption.

Typography capture [39969462359](https://github.com/DocMorphic/cmux-app/actions/runs/39969462359)
at 8c3d0817 also succeeds, with all 52 frames verified locally. Its original
CI/local receipts match. The later local typography guards additionally bind
each presented title's public UILabel font/frame to the independent XCUITest
title frame; all 39 presented pages pass. The enhanced verifier's own hash
differs from the earlier CI receipt; image hashes and whole-source provenance
remain identical. All 25 synthetic integrity guards pass.

Actual title UIFont sizes are 17 points at ordinary text and 21 at accessibility
medium, while body sizes are 17 and 28. The bar is 54 points high; the standard
tablet bar starts 10 points below the panel top. These measurements do not
establish Close's UIFont: its rendered text is not among the observed UILabels.
Keep Close typography, native materials and production-caller context open.

Public bar-layout collection adds `navigationBarMetrics` (frame in window, bounds/
layout-guide frame in owner coordinates, physical/directional margins, safe area,
margin behavior, effective direction, size classes and immediate UIView children).
`windowChrome` records the actual scene orientation/size classes and public status-
bar manager availability, hidden flag, raw scene frame and style. No original
view is edited, no private selector is called, and these observations do not
establish Close UIFont identity or automatically prove a particular margin rule.

The verifier now requires and binds these observations to the measured navigation
frames for all52 compositor pages and checks dismissal metadata as well. All53
synthetic integrity guards pass locally; they reject malformed/unbound metrics
and are not UI evidence. Swift syntax parsing and whole22-file host preparation
pass locally. Actual UIKit compilation/capture must succeed on the existing CI
runner before accepting the new measurements. Earlier receipts remain bound to
their earlier exact collector/verifier hashes.

The actual hidden landscape manager in run40025958696 reports raw status style
-1 with a zero scene frame. This is retained as an observed raw value, not named
as a public enum case; guards permit it only when hidden with the exact empty
frame. Retained original captures can be associated after a guard repair with
`ios-feed-reader-associate.yml`; it binds the original clean commit/artifact and
requires all three actual capture steps to have succeeded. This adds no new UI
execution and cannot establish Android/full app parity by itself.
