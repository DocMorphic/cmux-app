# Selected cmux UI translations

Source: `manaflow-ai/cmux` at `f4b1509054949eaad5d695569ad443c4c18ed68d`.
Copyright Manaflow, Inc.; GPL-3.0-or-later. See LICENSE and the root NOTICE.md.

`strings.json` holds 84 unformatted static labels with their original catalog/key,
all nine upstream language values and SHA-256 hashes of the four source catalogs.
Duplicated English labels are admitted only when every language agrees, except
the explicitly reviewed Done and Try Again dismissal/retry actions. Reset and the
ambiguous Mac-update title were deferred for context-specific mapping.

Generate/check the owned Android resource files:

```sh
python3 scripts/mobile-ui-localization.py
python3 scripts/mobile-ui-localization.py --check
```

`--migrate` converts only complete static arguments of unqualified Material3 Text
calls using this selected dictionary. It skips templates, computed expressions,
comments, embedded programs and qualified protocol/model constructors. It does
not translate user/workspace/terminal content or alter request identifiers.

Resources use standard Android locale selection, including script-qualified
Chinese resources. The application declares RTL support. There is no new language
picker or change to the device language. Full application localization, formatted/
plural messages, dynamic statuses, accessibility labels and rendered RTL/locale
acceptance remain open. This subset does not establish nine fully localized apps.

The separate `billing-strings.json` imports eighteen labels used by the existing
read-only Plan screen from `ios/cmux/Resources/Localizable.xcstrings` at
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`, including the complete nine-language
values and exact catalog SHA-256. `billing-android-strings.json` contains seven
project-authored translations for Android browser/recovery controls absent from
that catalog; their provenance is separate. The same generator writes/checks
`cmux_billing_strings.xml`. Billing labels use explicit call sites, not the
general English-label migration dictionary. This scoped import advances no
global source pin and implements no purchase, refund, restore, or analytics API.

`browser-dialog-strings.json` imports eight browser dialog fallback/field labels
(72 exact values across nine languages) from the same a5911a1f Localizable catalog.
The catalog SHA-256 and original keys are retained. The generator owns the nine
`cmux_browser_dialog_strings.xml` files; labels supplied by the host remain
host-supplied. This scoped import advances no global source pin.

`cloud-lifecycle-strings.json` imports eight Cloud pause/resume/restart/delete
labels and failure formats from that a5911a1f catalog (72 original values).
The loader accepts exactly one `%@` string argument for each failure format,
converts it to `%1$s`, and marks only those Android resources as formatted.
It rejects missing/additional/other-type placeholders. The nine generated
`cmux_cloud_lifecycle_strings.xml` files are checked without adding Cloud text to
the general migration dictionary. Other Cloud copy remains outside this import.

`cloud-onboarding-strings.json` imports eighteen onboarding, navigation, VPN
phase and progress labels from the same exact a5911a1f catalog. The progress
format preserves exactly one numbered `%1$d` and `%2$d` integer argument,
including languages that reverse their order. `cloud-onboarding-android-strings.json`
separately attributes two project-authored explanations for a generic Android
browser and Android Keystore encryption/storage. The generator checks all 180
values and owns nine `cmux_cloud_onboarding_strings.xml` files. It does not add
these strings to general English-label migration. Other VPN warnings, permission
and cleanup errors, complete Cloud localization and real TalkBack acceptance
remain separate work.

`markdown-strings.json` imports ten remote-image consent/loading/copy/open labels
from `Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Resources/Localizable.xcstrings`
at a5911a1f (90 original values, catalog SHA256
`9b9aa8bc6572b760cb8df0709948473fb717f6af15a665e4a57c961b212d5719`).
The generator owns nine `cmux_markdown_strings.xml` files. `{url}` remains exactly
one literal shell substitution, not an Android printf argument. The labels are
spliced into the unchanged shell as JSON. This does not localize all viewer or
application status/error text or establish complete RTL/accessibility parity.

`sign-in-recovery-strings.json` binds ten original native recovery keys (90 exact
values across nine locales) to the main iOS and MobileShellUI catalogs at
 a5911a1f. Per-key catalog/resource association and both whole catalog hashes are
checked; only the email format argument maps `%@` to Android `%1$s`. The common
generator writes/checks `cmux_signin_recovery_strings.xml` in all nine folders.
Source-to-value comparison and runtime scope are documented in docs/SIGN_IN.md.
