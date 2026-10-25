# Original Chief Home search inputs

The original102 rows are retained in BaselineCases.json. Current Cases.json has
5,546 inputs: the baseline plus all ICU77.1 case-fold mappings, BMP canonical
decompositions outside algorithmic Hangul, partial expansions, digit/script
branch selection, prefixed queries and long combining runs. These exercise the complete
`ChiefHomeView.matches` method at the Chief manifest revision. Cases cover case
expansions, canonical composition, preserved accents, non-Latin scripts,
supplementary scalars, emoji components, format controls and source query trimming.
Inputs deliberately contain no authored expected results. Generate them with
`scripts/generate-chief-home-search-cases.py --java-home <JDK17> --icu4j <icu4j-77.1.jar>`.
The Java mapping helper authors query strings only; original native code decides
the boolean result. GeneratedInputs.json binds the baseline, generators, input
jar and resulting corpus hashes. The expanded corpus is not yet accepted on iOS.

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

Run40209688789 at15e50ff9 is independently verified against GitHub artifact12605626655 and its archive/source identity. All918 iOS observations agree with the102 local macOS cases in each of the nine locales. Executing the complete current Kotlin state/matching/model/text-policy declarations differs on30 cases per locale. The Android matcher still needs reconciliation. Home SwiftUI
rendering, native view state lifetime, backend interaction, physical device input
and complete feature/UI parity require separate evidence.

A private forward matcher currently agrees with all102 accepted native cases.
Its raw UTF-16 comparison/canonical folding and the native-observed digit/script
branch use different search paths; applying a single collator to all queries is
incorrect. The expanded local macOS original-method comparison agrees on5,537
inputs and differs on nine historic Cyrillic case-fold mappings. Direct native
mapping observations confirm those scalars remain unchanged there. This remains
research: no new matcher is in production, and a fresh original iOS capture must
validate the expanded corpus before it supports Android acceptance.
