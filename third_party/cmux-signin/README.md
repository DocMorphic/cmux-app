# Original sign-in presentation reference

`manifest.json` records the original whole-file hashes at cmux
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`, copyright Manaflow, Inc.,
GPL-3.0-or-later. The repository LICENSE/NOTICE preserves that attribution.
`cmux-sign-in-mark.svg` is unmodified upstream artwork. Its single path and
three-stop gradient are represented by `app/src/main/res/drawable/cmux_signin_mark.xml`.

The code card follows the original separate entry states,430-point maximum card,
20-point horizontal/24-point top/20-point bottom padding,18-point stack spacing,
28-point mark,60-point code pill and fixed32-point semibold monospaced code.
Five original catalog keys across nine languages are separately bound in
`third_party/cmux-localization/sign-in-code-strings.json`.

The sign-in grid uses the existing Cloud colony/Canvas implementation:36×52,
80ms tick, toroidal B3/S23,22% seed, low-population/120-step reseeding,6-point
padding,52% cell size and source gradient/opacity rules. The shared implementation
also has Android lifecycle/reduced-motion handling; exact original sign-in motion
behavior under system accessibility settings has not been accepted.

The Android capsule paint and system typeface do not execute UIKit Liquid Glass
or establish exact source rendering. Original provider methods, entry scaffold,
restore status, transitions, window/keyboard variants and real accessibility
remain open. Scoped runtime evidence and remaining requirements: `docs/SIGN_IN.md`.
