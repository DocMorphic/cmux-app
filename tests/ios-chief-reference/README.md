# Pinned UIKit Chief references

This disposable XCTest host renders the actual `CmuxConversationUI` package at
the Chief audit revision. The complete package tree is pinned separately in the
Chief manifest; this is an executable reference dependency, not a claim that
every source file has received semantic review. The package is extracted from
Git without modifying it. Chief's real presentation options and unmodified
avatar are paired with an immutable offline backend.

Nine authored scenes cover incoming/outgoing runs, light/dark appearance, long
and Unicode text, quotes, accessibility type, literal markup, failed local sends,
an approval poll and emoji-only messages. XCTest attaches actual UIKit window
PNGs and visible-label geometry/font metadata. Capture excludes the system status
bar and does not establish keyboard, animation, account, transport, physical-device
or Android parity. Reference images require inspection and matched Android states.

Prepare a fresh project with:

```sh
python3 scripts/prepare-ios-chief-reference.py --upstream /path/to/upstream --output /fresh/reference-project
```

The manual `ios-chief-reference.yml` workflow supplies a full Xcode installation
and a disposable simulator on its runner. It is independent of the Mac companion
build. Its exporter verifies the exact project commit, pinned source tree,
fixture hash, complete scene set, PNG payload hashes and pixel dimensions before
emitting canonical named files. The result bundle and failed diagnostics are
retained. A green export is integrity evidence; it does not replace rendering
inspection or the full-parity completion gates.

Local preparation, `plutil` project parsing and Swift parsing can run with command
line tools. UIKit typechecking and rendering require full Xcode and an iOS runtime;
the current Mac has only the command line tools, so hosted compilation remains
mandatory. Never interpret a parser pass as a successful UIKit build.
