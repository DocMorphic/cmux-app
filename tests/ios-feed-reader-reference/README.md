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
