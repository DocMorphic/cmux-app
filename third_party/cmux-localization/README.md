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
