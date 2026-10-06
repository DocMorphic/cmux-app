# Keeping Android current with cmux

## Current development cadence — 2026-10-05

At the user's request, development now targets `main` and prioritizes larger
feature batches before broad testing. Scheduled APK builds are opt-in via the
repository variable `CMUX_AUTOMATIC_PREVIEWS=true`; it is currently `false`.
The batching policy below is preserved for later activation. Manual milestone
builds remain available with `publish_preview=false` for Actions artifacts only.
The upstream watcher continues independently. Production promotion remains manual.

## Upstream behavior reviewed

Rechecked the five iOS test/release policy files at upstream
`f204ade4df352cd4c214f8a21f792cfc50e7dba8` on 2026-10-02. This is a
release-policy audit only; it does not advance the app parity references:

| iOS behavior | Android counterpart |
| --- | --- |
| Relevant PRs and merge-group commits run simulator checks | Ready Android PRs run the existing build/test gate; draft feature commits do not assemble APKs |
| INTERNAL TestFlight polls main every 20 minutes | Android preview lane polls main every 20 minutes |
| Five relevant main first-parent commits or oldest merge at least 180 minutes old trigger a batch | Same count/age policy in `scripts/android-preview-decision.py`; one merged PR counts once |
| Unchanged or unrelated commits skip upload; unchanged failed candidates wait | Skip unchanged/doc-only batches and unchanged failed scheduled builds; manual retry remains available |
| Manual TestFlight dispatch can force a build; uploads are serialized | `publish_preview=true` on main forces a verified signed GitHub prerelease; preview runs are serialized |
| Production App Store upload/submission is separately dispatched | `Promote Android release` manually promotes an existing verified APK without rebuilding it |

Primary sources: [iOS simulator tests](https://github.com/manaflow-ai/cmux/blob/f204ade4df352cd4c214f8a21f792cfc50e7dba8/.github/workflows/test-ios.yml),
[INTERNAL/DEMO TestFlight](https://github.com/manaflow-ai/cmux/blob/f204ade4df352cd4c214f8a21f792cfc50e7dba8/.github/workflows/ios-testflight.yml),
[batch decision](https://github.com/manaflow-ai/cmux/blob/f204ade4df352cd4c214f8a21f792cfc50e7dba8/scripts/ci/ios_upload_batch_decision.py),
[production App Store workflow](https://github.com/manaflow-ai/cmux/blob/f204ade4df352cd4c214f8a21f792cfc50e7dba8/.github/workflows/ios-app-store.yml).
The [official-bundle TestFlight lane](https://github.com/manaflow-ai/cmux/blob/f204ade4df352cd4c214f8a21f792cfc50e7dba8/.github/workflows/ios-appstore-upload.yml)
also polls hourly, batching ten commits or six hours; App Review remains manual.
Android currently follows the faster INTERNAL policy for its single preview lane.
The October 2 change in that official-bundle workflow adds Go setup for the Cloud
tunnel extension; its batching policy is unchanged. The other four reviewed files
are identical to the October 1 audit.

The separate twice-daily iOS DEMO branding/distribution is not needed for this
single Android app. GitHub APK releases are the configured Android delivery
channel; no Play Console application, credentials or Play internal track has been
configured. These workflows do not silently install an APK on the phone.

## In-app release notices

The official iOS app separately has a channel/version-gated What's New archive
and launch sheet. Android implements the native center, archive and launch sheet;
the [implementation notes](WHATS_NEW.md) and [web integration](WHATS_NEW_WEB.md)
track the remaining feed, cold-start and authenticated runtime acceptance. The detailed
[What's New audit](WHATS_NEW_AUDIT.md) records cache, remote retraction, web preload,
acknowledgement and presentation rules at `0fc35d6`; build automation below is not
evidence of that UI. Android notices need Android release identities rather than
copying iOS marketing-version claims.

The Android feed is now configured at the public repository's
[`distribution/android-notices.json`](../distribution/android-notices.json).
[Maintenance instructions](../distribution/README.md) explain identity, targeting,
publication and anonymous fetch limits. Feed-only changes publish metadata without
an APK build; the app fetches on notice-owner startup. They do not install an
update, enable scheduled previews, or establish physical release acceptance.

## Two different update events

### A commit lands in manaflow-ai/cmux

`.github/workflows/upstream-watch.yml` checks upstream main every 20 minutes,
without cloning or executing upstream code. It compares against the audited
candidate in `upstream.json` and produces a JSON/Markdown review inventory.
It maintains one bot-owned issue, **Upstream cmux: Android parity review queue**.
An unchanged report causes no issue write. Reports are retained as Actions
artifacts for seven days. A manual run on a feature branch generates an artifact
without publishing an issue.

GitHub Actions is currently prohibited from creating/approving PRs in this repo.
The watcher therefore uses an issue with scoped `issues: write`, leaving that
repository setting unchanged. It neither opens code PRs nor changes signing keys.

The inventory covers iOS UI, shared/mobile protocol code, all native host `Sources/`
(mobile behavior can change outside `Sources/Mobile/`), phone push, workers,
authentication SDK, Ghostty pins, iOS build definitions and mobile/release scripts.
Renames out of a watched path also count. GitHub limits comparison file inventories
to 300 files. At that limit, the watcher compares recursive Git trees at the two
immutable commit IDs, retaining file-mode, symlink and submodule changes. This
fallback represents renames as removal/addition. If GitHub also truncates a tree,
the report remains explicitly incomplete and requires further source review.
Commit subjects are paginated up to 1,000; any remaining subjects are explicitly
marked incomplete. Diverged history always requires review. API failures or missing
comparison inventories fail the workflow rather than producing an apparently clean
report. Review-area counts help route work; they do not establish feature parity.

API contracts: [comparison pagination and file limit](https://docs.github.com/en/rest/commits/commits#compare-two-commits),
[recursive tree limits](https://docs.github.com/en/rest/git/trees#get-a-tree).

**Detecting a commit is not porting it.** Swift/iOS code must be reviewed and
implemented in Kotlin/Android where applicable. Protocol changes need both
compatibility tests and host/device acceptance. The watcher never advances
`implemented_ref` or `reviewed_ref`. Update those fields only after the corresponding
source audit or verified parity work, keeping `PARITY.md` and the feature docs
consistent. `reviewed_ref` itself is an audited candidate, not a completed parity
claim.

### A commit lands in DocMorphic/cmux-app

Ready PRs retain automatic build/test behavior; draft commits accumulate without
an APK build. The new lightweight policy tests run without starting an emulator.
When `CMUX_AUTOMATIC_PREVIEWS=true`, the scheduled preview decision counts relevant
commits since the **last published preview source**, not the last green poll.
Five relevant main commits or three hours from the oldest relevant merge triggers
the existing signed build. A merged feature branch counts once, using the merge
committer time. Direct main commits count individually. Documentation-only changes
do not count. A preview source outside main’s first-parent history forces a fresh
build rather than using side-branch counts or ages. Missing/changed history builds conservatively; unavailable release
history fails without guessing. An unchanged failed candidate does not rebuild
every twenty minutes. Manual `publish_preview=true` retries it deliberately.

Each successful preview publishes an immutable `android-preview-N` GitHub
prerelease containing:

- `app-release.apk`, using the existing package and signing secret;
- `SHA256SUMS`;
- `manifest.json`, with source SHA, versionCode, APK digest and workflow run ID.

The existing Android workflow's run number remains the versionCode source, so a
separate publisher's counter cannot regress installed versions. Publication happens
only after the build's JVM/helper/assets/signature/16 KB alignment checks pass.
All preview code comes from main. Publication does not claim device or full-parity
acceptance that those checks cannot establish.

Preview assets are uploaded to a draft first and made public only after the
upload succeeds. Partial/draft uploads cannot become the next batching baseline.

Production promotion accepts an existing `android-preview-N` tag. It verifies
source/run provenance, version identity, artifact digest, the existing signing
certificate and ZIP alignment before marking the **same assets** stable/latest.
It does not rebuild, install on a phone, upload to Play, or merge the development PR.

## Verify a downloaded development artifact

Before installing a signed Actions APK, check out the source commit reported by
that successful run, download its `cmux-app-stable-signed-apk` artifact, and run:

```sh
python3 scripts/verify-signed-apk.py /path/to/app-release.apk --version RUN_NUMBER --output captures/runtime/signed-verification
```

Set `ANDROID_HOME` (or pass `--sdk`) and configure Java. Android build-tools
36.0.0 must be available. The command checks the stable certificate, package and
version, SDK contract, all nineteen native libraries' LOAD/RELRO alignment,
16 KB ZIP alignment, disabled backup/debugging, diagnostics Application, exclusion
of every Activity in the current debug manifest, and all fourteen viewer hashes
inside the APK. The expected SDK,
native-library and viewer inventory are explicit checks and must be reviewed when
those release contracts change. It publishes
`apk-verification.json` only after every check passes, retaining individual tool
outputs beside it. A failed recheck removes an older receipt from that directory.

The Android build workflow uses this same command before uploading its signed
artifact; it also checks the debug APK's native segments. This adds packaged asset
and release-manifest checks to the existing build gate. APK contents alone do not
prove source provenance, runtime correctness or account migration. Resolve the
source/run identity from GitHub separately, then perform the applicable device
acceptance. None of these checks publishes or promotes a release.

Local acceptance (2026-10-03): the reusable verifier passed on signed builds 434
and 441. Passing 434 while expecting 441 was rejected and removed a deliberately
stale receipt. Actionlint accepted the workflow change, and all ten policy tests
passed. Build 441 itself used the prior workflow gates; its downloaded artifact
was independently checked with the new command. No redundant APK build was
triggered solely for this gate refactor.

## Activation and operation

The application and workflows are now on default branch `main`; PR #1 is merged.
The first manually dispatched [watch run](https://github.com/DocMorphic/cmux-app/actions/runs/37343967042)
completed successfully on 2026-10-05 and created the bot-owned
[review queue](https://github.com/DocMorphic/cmux-app/issues/2). The 20-minute
schedule is configured on main; dispatch times remain subject to GitHub scheduling.
That first run used the older capped inventory; the subsequent checkpoint below
records the full-tree fallback. Detection never automatically ports or merges code.

During feature-first development:

1. Keep `CMUX_AUTOMATIC_PREVIEWS=false` to avoid scheduled APK builds.
2. At an integration milestone, manually run **Android build** on main with
   `publish_preview=false` for signed Actions artifacts only.
3. Verify the artifact and install it as an upgrade, retaining existing app data.
4. Preview publication (`publish_preview=true`), enabling automatic previews,
   and production promotion remain separate decisions. No production promotion
   has been authorized by the current main-branch development request.

For a local read-only upstream report:

```sh
python3 scripts/upstream-watch.py --output captures/runtime/upstream-review
```

The GitHub CLI supplies authentication locally; Actions uses its scoped token.
Run policy tests with:

```sh
python3 -m unittest discover -s scripts/tests -p 'test_update_policy.py'
```

## Full inventory checkpoint — 2026-10-05

Sixteen policy tests pass, including a mobile change beyond the 300-file cap,
immutable-head commit pagination, moves out of watched directories, executable
mode/submodule changes, truncated trees, missing inventories, duplicate commits
and idempotent issue updates. No emulator or APK build is involved.

The live read-only report at `1012a019a9b0a61c471fc7aba68361f3b1f6bd1b`
found 3,930 changed paths against the unchanged audited candidate `204a11d`, with
1,060 relevant paths and complete file coverage from recursive trees. Of 1,114
commits, 1,000 subjects are retained; the remaining subjects are explicitly marked
incomplete. This is a detection inventory, not a source audit or porting result.
Neither parity reference was advanced. Local evidence is retained in ignored
`captures/runtime/upstream-watch-main/`. Signed APK 606 remains the verified
download; automatic previews remain disabled.

The upgraded [main workflow run](https://github.com/DocMorphic/cmux-app/actions/runs/37345099154)
then succeeded at source `0c1c4c3`, published the same full-tree inventory to issue
#2, and uploaded its JSON/Markdown artifact. Its 3,930 total / 1,060 relevant paths
match the local report. This verifies detection-to-review publication on GitHub;
porting those changes and selecting a later Android build remain separate work.

## Historical verification before main integration

Ten policy tests pass, including a real Git history with a six-commit feature
branch counted as one newly landed merge, an unrelated documentation commit, a
rename out of the app directory and a side-branch baseline. They also cover
batching boundaries, unchanged failures,
renames, capped/diverged comparisons, idempotent issue updates and keeping the
implemented reference unchanged. Actionlint 1.7.12 accepts all three workflows.
The October 2 live read-only comparison found **738 commits** after audited candidate
`204a11d` at detected head `f204ade`; the API returned a capped file list and a
partial commit list. The report correctly requires a full review and does not
advance either reference. Evidence is in ignored `captures/runtime/upstream-ci/recheck-2026-10-02/`.
No emulator or APK build was needed for this release-policy check.
No issue, preview release, production release, main merge or phone update was
performed by this verification. CI publication/promotion still needs its first
main-branch run after activation.

A later read-only check during build 369 verification detected
`644fd5eb60874322d7f5a8005607d0c75ea39b48`, **871 commits** after the same audited
candidate. Both API inventories remain incomplete, and the report again requires
review. Neither reference was advanced and no bot issue was published. This is
detection evidence, not a new audit of those commits or their release policy.
The report is retained in ignored `captures/releases/59f279c/upstream-review/`.
