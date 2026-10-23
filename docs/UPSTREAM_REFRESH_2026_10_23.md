# Upstream inventory at 79d982f9 — 2026-10-23

The existing upstream-watch run [40021054162](https://github.com/DocMorphic/cmux-app/actions/runs/40021054162)
detected `79d982f93f298c5087afa79655ef7b242665125c`. Its complete recursive-tree
inventory has6,803 changed paths relative to the implemented pin; the commit
inventory is incomplete. This does not advance either global source pin.

A separate complete tracked-leaf comparison from audit candidate
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009` finds201 changed paths:
22,141 candidate entries versus22,210 detected entries. The latest GitHub tree
is not truncated. This is a file-identity inventory; most changed paths still
need review against their applicable Android/Mac/backend behavior.

Exactly two paths under `Packages/iOS/` change in that candidate-to-detected
comparison. The latest contents were downloaded at the exact detected revision
and their Git blob identities verified before diffing:

| File | Change | Android implication |
| --- | --- | --- |
| `CmuxMobileShell/MobileHideComputersVerifier.swift` | DEBUG-only evidence output uses first caches URL or temporaryDirectory instead of indexing element0. | No production hide-computers change in this delta; it does not close existing hide-computers acceptance gates. |
| `CmuxMobileShell/MobileSSHCmuxTUIProvider.swift` | npm archive cache uses first caches URL or temporaryDirectory instead of indexing element0. Download/integrity/cache/activation logic otherwise unchanged. | `NativeSshRuntime` supplies `Context.cacheDir` directly to `SshCmuxArchive`, which creates the directory and verifies old/new archives. No corresponding Foundation URL-array index exists to replace. Full install/SSH parity remains open. |

All22 files in the original Feed reader reference manifest have identical Git
blob identities at the audit candidate and detected revision. This supports
continuing that component reference; it is not whole-app source acceptance.

Exact complete inventories, downloaded whole Swift files and diffs, Git blob/
SHA-256 bindings and reviewed Android source hashes are retained under
`captures/recovery-20261023/`:

- `upstream-watch-40021054162/report.json`
- `upstream-tree-79d982f9.json`
- `upstream-candidate-to-79d982f9-inventory.json`
- `upstream-mobile-79d982f9-review.json` and `upstream-mobile-79d982f9/`
- `feed-reader-latest-source-comparison.json`

Implemented remains `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`; reviewed remains
`204a11dfcc76280205e50406ab94270a1c152155`. No Android app source, signed package,
phone, Mac, account, pairing or signing state changed for this review.
