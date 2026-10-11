# Custom Mac notification build

`mac-notification-companion.yml` builds the full pinned upstream app plus the
reviewed Android source/QR pairing hooks on a standard GitHub `macos-26` ARM64
runner. Local Xcode installation and a local full upstream checkout are not needed
for this build route. Trusted TLS/runtime provisioning and real Mac/Pixel/provider
acceptance still are. The running official cmux app remains unchanged.

The workflow is manual only, serializes its runs and has read-only repository
permissions. No Firebase client secret, provider credential, cmux account token,
private helper key or local configuration is passed to the runner. Upstream is
pinned to `f4b1509054949eaad5d695569ad443c4c18ed68d`; Ghostty comes through the
upstream checksum-verifying setup. The build uses the documented tagged reload
wrapper, production auth/API/Iroh routing and the `android-push` tag. It does not
launch the app. Native composition/UI/control checks run before the full build.

```sh
gh workflow run mac-notification-companion.yml --repo DocMorphic/cmux-app --ref main
```

Resolve and inspect the resulting run before downloading or retrying it. A queued
or active run is still work in progress; do not restart because an observation
times out. Inspect actual failure logs before changing inputs.

## Artifact contract

Successful runs upload `cmux-mac-notification-companion-arm64`, containing the ZIP,
manifest and SHA256SUMS. The packager verifies the exact 22-file patch in the built
checkout, isolated bundle ID `com.cmuxterm.app.debug.android.push`, ARM64 support,
production routes and deep code signatures. It removes runner-specific repository,
bundled CLI, shell integration and runner-home daemon paths from the launch environment, then signs the
relocated top bundle again. Unknown runner paths and baked account/helper paths
are refused. The manifest records both source commits, every patch-file hash,
archive hash, Xcode version and verification scope.

This is an **ad hoc signed development bundle**, not a notarized distribution
release. It is for the authorized tagged Mac/Pixel acceptance run. Ordinary local
debug testing does not require notarization. A successful build does not establish
runtime account/Keychain access, native pairing, FCM delivery or full iOS parity.
Download, verify the ZIP/hash/signature locally and inspect its exact configuration
before launching it with the private helper paths. Preserve the official app and
the user's current phone sign-in/data.

Build diagnostics are uploaded even on failure. They contain build output and
synthetic native fixture checks, with no real credentials or notifications.

## Verification status

Retry [38180269049](https://github.com/DocMorphic/cmux-app/actions/runs/38180269049)
was dispatched at `686b012d4a8aca76e7839c24cd40ca9fe2fbaabb` on main after the
repairs. Authoritative job `114591146203` was confirmed **in progress** during
checkout at 2026-10-11 22:24 UTC. Track this exact run; do not redispatch because
an observation expires. Its full build/package/recovery results remain unverified.

First full Mac run [38174243912](https://github.com/DocMorphic/cmux-app/actions/runs/38174243912)
is terminal: the full patched app **compiled successfully** on Xcode 26.6 in
73m49s, then packaging failed because the verifier expected a hyphenated bundle
ID while pinned reload.sh uses `com.cmuxterm.app.debug.android.push`. Source was
`ee66bd51ac46de138345f851457bc10d76868439` / upstream `f4b15090`. Failed logs and
build diagnostics were downloaded and inspected; no verified app artifact exists.
The verifier now requires that exact canonical identity, strips the runner-home daemon locator while retaining the isolated frontend socket, and
still refuses wrong tags, private paths, unknown runner paths and nonproduction
routes. Five policy groups pass after preserved regression failures. A future
packaging failure preserves an explicitly UNVERIFIED compiled recovery archive;
that archive requires independent inspection before use. Verified packaging,
local installation/runtime and physical FCM/Pixel acceptance remain open.

Five packaging-policy test groups pass locally: relocation without mutation,
stable/untagged bundle refusal, development endpoint refusal, baked private path
refusal and unknown runner path refusal. The manual workflow YAML parses. Local
verification used Ruby's standard YAML library because Python PyYAML is absent.
Full app compilation is established by the terminal build step and inspected log.
Signature/archive evidence still requires successful packaging and downloaded bytes.

Runner/toolchain references: [GitHub runner documentation](https://docs.github.com/en/actions/reference/runners/github-hosted-runners),
[macOS ARM64 image](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-arm64-Readme.md),
and pinned upstream `skills/cmux-dev-workflow/references/tagged-builds.md`.
