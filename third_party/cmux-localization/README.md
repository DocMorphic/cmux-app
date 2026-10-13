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
