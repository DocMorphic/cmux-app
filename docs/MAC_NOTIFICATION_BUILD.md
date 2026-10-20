# Custom Mac notification build

## Chief-capable host candidate — 2026-10-20

The build recipe now targets exact upstream
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`, matching the scoped Android Chief
reference. The current recipe guards 13 exact installer inputs and produces
30 exact patched/added files, including the CLI correction below. The four unchanged boundaries include
the push producer, key exchange, team-authority projection and account workspace
entrypoints. The changed auth, AppDelegate, Commands, project, catalog and reload
boundaries retain the existing isolated-owner hooks. This is a scoped Mac build
integration rebase, not global source-review or mobile parity acceptance.

Local checks pass for Swift/project/shell parsing, the actual nested SwiftUI
Commands property, actual upstream payload/auth projection and native receipt /
private-pipe behavior, pairing UI/socket/QR cleanup and owner/capture lifecycle.
The email-code model executes against the newer source policy; the local CLT
fallback records `uiTypechecked: false`. The hosted workflow still requires its
full SwiftUI interface-fixture typecheck before the full tagged build. Five
installer checks and eight cache checks pass; a new invariant keeps the workflow
checkout and packager on the installer's guarded revision. The workflow selects
the upstream-pinned Xcode SDK family (26) before preparing dependencies.

First full run [39493457582](https://github.com/DocMorphic/cmux-app/actions/runs/39493457582)
at project source `2c6ed4b93c60d425efc17a6d3c97c59ec5900f3d` failed in the CLI
target: `CMUXCLI+CommandSuggestions.swift` left its `topLevelCommandNames` Set
initializer as an uncalled closure. The compiler asks for `()`. The installer
now pins this thirteenth source and invokes that closure. The new mandatory
`check-mac-cli-suggestions.py` reproduces the original compile failure, compiles
the actual corrected leaf and executes Chief inclusion, typo suggestions, error
status and hidden-command exclusion with inert CLI/registry definitions. This
is a focused correction; full linking and runtime acceptance remain open.

That first run's dependency and required integration checks passed, including the
hosted form UI typecheck. The patched catalog
also passes the actual source checker across all nine locales, zero parity
errors. The terminal failure permits a new build after this checked correction;
do not redispatch an active follow-up. Logs and red/green leaf receipts are in
`captures/recovery-20261020/mac-chief-rebase/`.

Follow-up [39498469545](https://github.com/DocMorphic/cmux-app/actions/runs/39498469545)
at `d8f268b7cdd41f9fb0223892a90d5392a82b8326` **succeeded**, including required
integration and the full tagged app build (86m15s). Artifact `12304818847` has
outer SHA-256 `e025f9970acfc19de9b8a9b0d72ac3b29698021ede95434dc850cab1b4d27d0f`;
the inner app archive is
`ae81998c386684a2d4d800d3e0d69cafe025cd9a8d303e6215a92fca73917f4c`.
Independent local verification regenerated all 13 original pins and 30 patched
files from the immutable project commit. All 30 relative archive symlinks,
ARM64, isolated bundle ID, production routes and strict deep ad-hoc signatures
verify. The artifact reports Xcode 26.6, build 17F113. Do not redispatch this
completed build.

The older custom app quit gracefully; its bundle is preserved. A private backup
of the tagged preferences and three session/scrollback entries was made before
the new app launched through the filtered private-owner launcher. Current bundle:
`captures/runtime/mac-companion-39498469545-app/cmux DEV android-push.app`.
Both original workspace IDs remain; the newer source added Home, so the count
is now three. The private helper configuration and sender descriptor are
unchanged. An integrity-checked offline snapshot of the checkpointed registry
contains one active registration, zero retired; this is storage retention, not
live helper acceptance.

The computer-use tool reports the Mac is locked and manual unlock was requested.
The first `mobile.host.status` query timed out after ten seconds, and the private
helper owner socket is absent. Account restoration, current Chief capabilities,
pairing-menu behavior, live helper startup, terminal and Pixel/FCM acceptance
remain unverified. Do not infer their state from successful launch or retained
files. The official app was not stopped or replaced. Verification, backups and
pending runtime receipts are under `captures/recovery-20261021/mac-chief-39498469545/`.
Chief capability is also gated by the host's `chief.enabled` setting; compiling
the candidate alone does not establish an active Chief host. The global
implemented/reviewed mobile pins in `upstream.json` remain unchanged. Ignored
comparison and compiled-check receipts are under
`captures/recovery-20261020/mac-chief-rebase/` and `captures/source-audit-20261020/`.

## Corrected code form delivered — 2026-10-13

Run **38433167598** succeeded at `06b0e294`, with full compilation taking
**75m30s**. Artifact **11836158994** was downloaded and independently verified:
archive SHA-256 `771e9295b8e216b1c41244d0cf0a817fc8f981168363c21952034379805ab444`,
all 27 patch hashes regenerated from the immutable build commit, 12 safe relative
symlinks, ARM64, production routing, isolated bundle ID and strict deep ad-hoc
signatures. Later source edits cannot contaminate artifact comparison.

The previous custom build quit normally and the corrected bundle launched through
the private-owner launcher. The official app was not stopped or replaced. Actual
UI opened the native form and successfully requested a fresh code for the owner's
Gmail account; it now shows **six-character** code entry and an enabled Sign In
action with no error. The owner is asked to enter the code themselves. Successful
code exchange, persistence, listener enablement and Pixel/FCM remain unverified.
No code or credential was emitted in the post-request inspection.

Current bundle: ignored `captures/runtime/mac-companion-38433167598-local-app/`.
Archive/receipt/verifier are retained under the matching runtime names. This bundle
predates the paused-heartbeat fix; follow-up run **38437521385** at `758d3688`
has passed its integration/cache preparation steps and is now in full compilation.
It will also establish whether the bounded compiler cache can be saved. No new
Android signed preview is required for these Mac-only fixes; 659 remains current.

## Compiler reuse for subsequent builds — 2026-10-13

Run 38417815664 spent **79m47s** in the full app build and only **25s** in
dependency setup. The workflow now optionally restores Xcode compiler results,
Debug products and SourcePackages. Reuse is scoped to the exact upstream commit,
Xcode/SDK, macOS/architecture, absolute source/build paths and workflow/cache
recipe. Separate source keys record the actual reviewed patch hashes. Run-specific
save keys permit later successful builds to replace an older cache through prefix
lookup, following [GitHub's cache matching contract](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching).

The pinned upstream `reload-build.yml` normalizes tracked-source timestamps for
incremental builds. Our adaptation hashes **working-tree contents**, including
the added native sources: upstream's index blob IDs would miss changes made by
our patch installer. Unchanged inputs retain their signatures; edited inputs
receive different signatures. Deleted inputs remain deleted and symlink targets
are not modified. Eight focused checks pass, including the unchanged-index /
changed-patch regression, cache isolation and fresh-checkout reuse.

Native integration checks, full compilation, reviewed source checks and signature /
configuration verification still run. Only a successfully packaged/uploaded build
can save compiler state, bounded to **4 GiB**; indexes/logs and non-Debug products
are excluded. Restore errors fall back to a clean build. A failed build that
actually restored a cache preserves its failure log and retries cold once, within
the existing job timeout. No account/helper credentials enter this manual workflow.

This applies to future dispatches. Existing run 38433167598 remains on its original
recipe and is not restarted. Hosted cache restore/recompile/cold-retry behavior,
cache size and actual time savings remain unverified until a subsequent build.
No additional full Mac or Android build is dispatched just to populate a cache.

## Mac email-code validation correction — 2026-10-13

The owner clarified that input was accepted but Sign In showed an error; a focus
failure is not established. Source review found a real validator bug: the custom
form accepted only six ASCII digits, while upstream iOS
`SignInCodeInputPolicy` accepts ASCII letters/digits, uppercases, strips formatting
and clamps to six characters. The custom model now matches that policy and the
form says six-character code. The coordinator still composes the normalized
prefix with its own opaque nonce and performs the existing verified sign-in flow.

The Swift fixture now compiles the actual pinned iOS policy/action files and
compares ten representative numeric, mixed-case, formatted, oversized and Unicode
inputs. Its successful verification path uses `ab12cd` and observes `AB12CD` at
the injected coordinator seam. Validation/retry/cancellation/busy/late-result
checks pass locally. This establishes normalization and form state, not a real
code exchange. It was not a phone/Android code change; signed build 659 remains
current. The already running Mac artifact predates this fix and needs rebuilding.

The attempted full local UI check still reports an unaccepted Xcode license,
despite `xcodebuild -version` identifying Xcode 27.0; version reporting is not
license acceptance. The hosted build keeps its mandatory UI check. No raw code,
nonce or credential was recorded. Logs are ignored under
`captures/runtime/companion-email-code-fix-20261013`.

## Updated Mac companion verified and launched — 2026-10-13

Hosted run 38417815664 succeeded at source `3247ee9a`, upstream `f4b15090`.
Full app compilation took **79m47s**. Artifact 11829858319 was downloaded;
archive SHA-256 is `2ae5d42766a1693f4af57700500dff739b6c148c7bc9919a40c843d4ee5863e2`.
All **27 reviewed patch-file hashes** match. Archive paths/12 relative symlinks,
ARM64, deep ad-hoc signatures, isolated bundle ID and production routes verify.

The old custom app quit normally and the new one launched through the explicit
private-owner launcher. The official app was not replaced or stopped. Actual UI
now displays the native email-code form, and a real code request successfully
advanced to its code-entry stage without a browser callback. The owner then
reported being unable to enter the code; input focus/error/window diagnosis is
pending, with the exact symptom requested. This is not successful sign-in or
native push acceptance. Subsequent UI inspection currently returns a ScreenCaptureKit
invalid-parameter error after the user changed the app state.

The current local bundle is under ignored
`captures/runtime/mac-companion-38417815664-local-app/`. Verification/manifest,
source/ZIP receipt and checker are under the corresponding ignored verified
folder. Native account/key-store/helper listener, Pixel pairing and real FCM still
remain open. No private code, token or provider credential was recorded in this
checkpoint. Signed Android build 659 is separately verified and published.

## Isolated Mac email-code sign-in implementation — 2026-10-13

The custom Android-push Mac app now exposes the existing production
`AuthCoordinator.sendCode(to:)` / `verifyCode(_:)` flow instead of requesting a
browser callback rejected by cmux.com. The new form uses the composition's same
coordinator and account projection; it does not read another app's credentials,
register an official callback scheme or change server trust. Validation excludes
the development `42` shortcut. It handles one request at a time, resend/change
email, retry, auth busy/restoring state, cancellation and late UI completion;
verification succeeds only once the coordinator reports authenticated state.

Both new and reused sign-in workspaces suppress their automatic browser start
for the exact isolated bundle. Host account sign-in and switch-account UI also
route there. Other builds retain the original hosted view/body and account paths.
The custom form's account, Mobile and workspace presentations use the same auth
owner. Browser-only/socket sign-in URLs are not made valid by this change.

The installer now checks **11 upstream pins / 27 patched files**, including the
new state/model and view's Xcode source membership. The production form state
machine compiles/executes locally in Swift 6; validation, duplicate actions, send
and verify retry, busy auth, cancellation, late completion and non-authenticated
verification cases pass. The original hosted view body remains byte-identical.
Full native hooks parse/project checks and compiled auth/source/receipt/private
pipe checks are run with the changed installer.

Local full UI typechecking is currently blocked by the CLT SDK's missing
`SwiftUIMacros` plugin. Selecting the installed Xcode tools instead reports an
unaccepted Apple license; the owner was asked to finish Xcode setup. The explicit
local `--model-only` check records `uiTypechecked: false`. CI uses the default
mandatory SwiftUI interface-fixture typecheck before a full tagged app build;
no UI typecheck, linked app or actual account acceptance is claimed yet. Logs and
receipts are ignored under `captures/runtime/companion-email-sign-in-20261013`.
The existing local Mac artifact predates this change and remains signed out.
Real code sign-in, storage restart, native listener/helper enrollment and Pixel
FCM acceptance still remain required. No Android build or emulator was started.


## Production custom-build sign-in failure — 2026-10-13

Real browser/desktop inspection reproduced the isolated app remaining signed out
after choosing the owner's existing Gmail cmux account. The website returned to
its homepage while the app waited for its callback. This is independent of the
visible development-build banner.

The pinned upstream `web/app/lib/native-callback.ts` only allows `cmux`,
`cmux-nightly` and `cmux-rc` on production requests. Tagged `cmux-dev-*` callbacks
are accepted only on localhost with explicit local configuration. The custom app
requests `cmux-dev-android-push`; its production auth routing alone therefore does
not make browser sign-in usable. Earlier build/launch records did not prove that
runtime flow. Do not register an official app scheme or copy another app's tokens
to work around it.

Next: expose the existing `AuthCoordinator.sendCode(to:)` / `verifyCode(_:)`
email-code flow in the isolated custom app, using its existing production client,
auth storage, account/team observation and nonce/transition checks. Normal browser
sign-in in other builds must retain its existing behavior. Build and exercise the
custom UI/real account before claiming Mac push enrollment or delivery. The current
verified Mac artifact predates this fix; it remains signed out. No Pixel is
currently connected. Full Xcode is now installed and its first-launch check passes
with explicit `DEVELOPER_DIR`; global `xcode-select` still points to CLT.


## Android companion admission — 2026-10-12

Android source now explicitly supports this build's `android-push` tag and exact
bundle namespace. Discovery alone does not authorize it: authenticated status and
push/reply pins must match `mac:com.cmuxterm.app.debug.android.push`, and the host
version must meet the 0.65.0 companion baseline plus any higher protocol floor.
See [PRIVATE_COMPANION_PROFILE.md](PRIVATE_COMPANION_PROFILE.md) for passing local
coverage and open broker/key-store/listener/Pixel/FCM gates. Signed 652 predates this
profile, so its private-build rejection is expected until an updated milestone is
verified. This Android change does not require rebuilding the existing Mac artifact.

## Verified artifact and first local launch — 2026-10-12

Retry [38180269049](https://github.com/DocMorphic/cmux-app/actions/runs/38180269049)
completed successfully at 23:34:38 UTC. Full compilation took **67m42s**; packaging
and verified artifact upload also passed. Source is
`686b012d4a8aca76e7839c24cd40ca9fe2fbaabb`, upstream `f4b1509054949eaad5d695569ad443c4c18ed68d`.
Artifact `cmux-mac-notification-companion-arm64` (ID `11719137216`) was downloaded.
Inner archive SHA-256:
`3d99fb87347709a9c785f63fd3a0d2bc4d9f6fd13a896439a978e95644d29bee`.
The manifest/receipt/archive agree; all **22** locally regenerated patch hashes
match the manifest. Archive paths and 12 relative symlinks were checked before
extraction. Local inspection verifies the isolated bundle ID, ARM64, production
routes, absence of baked runner/private paths, and deep strict ad-hoc signatures.
Version is **0.65.0**, minimum macOS **14.0**, built with Xcode **26.6 / 17F113**.
The signed app declares no entitlements; actual native Keychain support is still a
runtime gate. Auth's reviewed bundle-specific file fallback handles ad-hoc token
storage; this does not prove native push/transport key storage.

The extracted `cmux DEV android-push.app` launched through the new
`scripts/launch-mac-notification-companion.swift` with the three selected private
owner paths. The launcher admits only the isolated production bundle, keeps
ordinary user environment rather than agent credentials, and refuses a second
running tagged instance. No process is stopped. An explicit unknown debug auth
profile uses the pinned resolver's documented fail-closed behavior, so no default
`~/.secrets` files or ambient test credentials are selected for auto-login.
The launch helper passes Swift 6 typechecking.

Actual Mac UI inspection shows the isolated app, its own new terminal workspace
and **Sign In**. Its production sign-in pane was opened and reports Waiting for
sign-in in the separate auth window. Token service, fallback directory and session
snapshot repository are scoped by bundle ID; no official app credentials or
workspace snapshot were copied. The owner is asked to sign in with the same cmux
account/team as the Pixel and reconnect the phone if available. Account/native
key publication, helper startup/enrollment and actual FCM delivery remain open.

Evidence: ignored `captures/runtime/mac-companion-38180269049-verified/`,
`mac-companion-38180269049-local-app/`, launch/typecheck logs and the current native
UI observation. This development app remains unnotarized; verified build/launch
is not whole Mac/Pixel or full iOS parity acceptance.

`mac-notification-companion.yml` builds the full pinned upstream app plus the
reviewed Android source/QR pairing hooks on a standard GitHub `macos-26` ARM64
runner. Local Xcode installation and a local full upstream checkout are not needed
for this build route. Trusted TLS/runtime provisioning and real Mac/Pixel/provider
acceptance still are. The running official cmux app remains unchanged.

The workflow is manual only, serializes its runs and has read-only repository
permissions. No Firebase client secret, provider credential, cmux account token,
private helper key or local configuration is passed to the runner. Upstream is
pinned to `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`; Ghostty comes through the
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
