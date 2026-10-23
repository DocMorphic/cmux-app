# Streamed browser dialogs

The implementation follows `manaflow-ai/cmux` revision
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`, specifically
`BrowserStreamDialogCard`, `BrowserStreamStore`, `BrowserStreamPane`,
`BrowserStreamDialogButtonStyle`, shared `View+MobileGlass`, and
`MobileShellComposite+BrowserStream.respondToMobileBrowserDialog`.
Seven exact source hashes are retained in local
`captures/recovery-20261023/browser-dialog-source.json`. No global source pin is
advanced. Shared Core's newer predicate-based dialog queue is a Mac broker
cancellation facility; it does not replace the phone's one pending dialog per panel.

The Android streamed page now presents the dialog inside the page area, leaving
bottom navigation and workspace return reachable. Page pointer/keyboard forwarding
is covered while a prompt is visible. HTTP basic authentication has separate
username and masked password fields. The username uses the host's initial value;
the password starts empty. Accepted authentication uses the exact source wire
`username + NUL + password` (escaped by JSON transport). Cancel sends no text for
all kinds, including ordinary secure prompts. No entered password is persisted
in the panel model or saved composition state.

A response claims the visible prompt before its RPC starts. The response belongs
to its panel owner and survives removal of the card. Host `not_found` consumes the
prompt; other failures restore it only if no newer/resolved prompt or closed
owner superseded it. Restoration recreates fields from the host descriptor,
including an empty authentication password. Leaving or replacing a panel cancels
its response job and fences delayed restoration. Existing client/account/route
admission remains in force.

Eight fallback/field labels use all nine exact source translations (72 values)
from `ios/cmux/Resources/Localizable.xcstrings`, catalog SHA-256
`9facaabb7334f92917f938a5f8ed3508c02b7ead5d401393e5410a49c3f74bfd`.
The generator checks all nine owned XML files. Host-provided labels/messages
remain host content. Two short button labels use a row; three buttons or a label
over twelve grapheme clusters use a column, including emoji cluster handling.
Android adds a bounded scroll viewport and single-line ellipsized field hints.
The rounded card/field and capsule button conventions follow the source, while
Android control metrics, borders, fonts and opaque materials remain provisional.
They are not accepted as a match for UIKit Liquid Glass.

## Verification boundary

All sixteen cases pass in 33.635 seconds in
`captures/recovery-20261023/browser-dialogs-v9`; crash buffer is empty and the
owned emulator exits 0. The cohort uses the production
Compose view and real TCP JSON client with a generated peer on an owned API 36
arm64 emulator. Fourteen cases cover existing stream/input/mode behavior plus
authentication/cancel wire, optimistic dismissal, host failure/not_found handling,
stale pushes, navigation and leaving/reopening the panel during an RPC. Two
component cases cover all nine controlled language contexts at actual font scales
1 and 2 in a measured 260 × 500 dp viewport, secure semantics, scroll reachability,
RTL ordering and source button count/grapheme layout. These are controlled
composition contexts, not actual OS locale changes or TalkBack acceptance.

The final clean committed build for signed candidate 716 has 3,117 JVM passes and four opt-in skips, no failures/errors
(3,121 cases, 425 reports). Fifteen localization/native-detector Python guards
pass. Signed 716 also passes signature/package/Firebase checks and two visually
reviewed fresh owned-emulator launches; it remains unpublished and uninstalled
on the physical phone. These startup checks do not exercise authenticated browser dialogs.
Earlier excluded emulator cohorts are retained: v1 had empty-password assertions
that also matched placeholder text; v3–v6 exposed fixture sizing/paragraph-bound
assumptions. v8 passes all 16 cases, but visual review caught a wrapping Japanese
password hint at enlarged text; v9 constrains and asserts single-line hints.
No failed cohort is counted as a complete pass. The final authentication, normal
Arabic/German and enlarged Japanese frames were visually reviewed; scroll
positions and ellipsized hints are retained honestly. At the narrowest viewport
and doubled text, short button labels wrap within their source-rule row; matched
native typography/layout remains open. Exact APKs, modified/untracked source
inputs and 150 evidence files are hashed in `browser-dialog-checkpoint.json`
under the same recovery folder.

A real Mac-host authentication/prompt lifecycle, Pixel input/autofill and
TalkBack/physical lifecycle checks remain open. Matched native font/material and
broad browser/source/full app parity remain open. No personal phone, account,
pairing registration or actual website credentials were used for these fixtures.
