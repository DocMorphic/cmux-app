# Android implementation plan

The active release checklist is [PARITY.md](PARITY.md), pinned to the official
cmux iOS and shared-source commit. It lists the iOS contract, the Android
implementation status, and a physical-device acceptance check for each area.

The build is delivered in stages: same-account native pairing and transport,
terminal rendering and input, workspace and notification behavior, browser and
task features, Android background behavior, then full Pixel 6a verification.
The current signed APK is an in-progress build until every acceptance check
passes against a supported cmux Mac version.
