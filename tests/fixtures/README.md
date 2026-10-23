# Original sign-in code input reference

`signin-code-input-reference.json` contains514 outputs from the whole unchanged
original Swift `SignInCodeInputPolicy` and `SignInCodeInputChangeAction` at cmux
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`. It records both exact source SHA256s,
compiler, generator/adapter hashes and initial/stable action names. Copyright
Manaflow, Inc.; GPL-3.0-or-later, as preserved by this repository's LICENSE/NOTICE.

Generate with `python3 scripts/generate-signin-code-reference.py --upstream /path/to/cmux`.
Add `--check` to compile/reexecute and compare the saved outputs and source/adapter
provenance. `NativeSignInCodeTest` verifies the fixture/generator identity and all
normalization/completion outputs. The Gradle Test inputs include both files.

Cases include ASCII/Latin1 scalars, Unicode, paste separators, truncation and seeded
mixed strings. The oracle covers pure normalization/completion declarations only.
It does not execute SwiftUI event scheduling, focus, busy controls, full coordinator
flows, UIKit, authentication or real-device behavior. Android runtime evidence and
remaining parity gates are recorded separately in `docs/SIGN_IN.md`.
