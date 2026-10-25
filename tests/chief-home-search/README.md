# Original Chief Home search inputs

These 102 input rows exercise both title and preview routes through the complete
`ChiefHomeView.matches` method at the Chief manifest revision. Cases cover case
expansions, canonical composition, preserved accents, non-Latin scripts,
supplementary scalars, emoji components, format controls and source query trimming.
Inputs deliberately contain no authored expected results.

The Original iOS Chief bundle lookups workflow compiles the complete original
`MobileChiefConversation` declaration and unchanged predicate in a Foundation
sidecar. Only the query state is supplied by the wrapper. It records results in
nine fresh original-app launch languages/locales, alongside resource and unread
observations. The actual original app sources and resources are unchanged.
`verify-ios-chief-bundles.py` checks source hashes, regenerates the sidecar source,
binds every input/result and checks the actual locale and app/resource identities.

The schema 3 capture also adds positive unread boundaries through Int.max.
Previous schema 1/2 artifacts retain their historical verifier/source commits;
do not rewrite them or interpret a new unexecuted corpus as accepted behavior.

The 102 source-method cases have run on local macOS Foundation. The new iOS run
must be verified before using its results as an Android reference. Home SwiftUI
rendering, native view state lifetime, backend interaction, physical device input
and complete feature/UI parity require separate evidence.
