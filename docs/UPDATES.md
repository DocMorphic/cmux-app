# Keeping Android current with cmux

## Upstream behavior reviewed

Reviewed upstream `15aa32cfc4bcaedee2f99f0c03b68f9814eaba79` on 2026-10-01:

| iOS behavior | Android counterpart |
| --- | --- |
| Relevant PRs and merge-group commits run simulator checks | Ready Android PRs run the existing build/test gate; draft feature commits do not assemble APKs |
| INTERNAL TestFlight polls main every 20 minutes | Android preview lane polls main every 20 minutes |
| Five relevant commits or oldest relevant commit at least 180 minutes old trigger a batch | Same count/age policy in `scripts/android-preview-decision.py` |
| Unchanged or unrelated commits skip upload; unchanged failed candidates wait | Skip unchanged/doc-only batches and unchanged failed scheduled builds; manual retry remains available |
| Manual TestFlight dispatch can force a build; uploads are serialized | `publish_preview=true` on main forces a verified signed GitHub prerelease; preview runs are serialized |
| Production App Store upload/submission is separately dispatched | `Promote Android release` manually promotes an existing verified APK without rebuilding it |

Primary sources: [iOS simulator tests](https://github.com/manaflow-ai/cmux/blob/15aa32cfc4bcaedee2f99f0c03b68f9814eaba79/.github/workflows/test-ios.yml),
[INTERNAL/DEMO TestFlight](https://github.com/manaflow-ai/cmux/blob/15aa32cfc4bcaedee2f99f0c03b68f9814eaba79/.github/workflows/ios-testflight.yml),
[batch decision](https://github.com/manaflow-ai/cmux/blob/15aa32cfc4bcaedee2f99f0c03b68f9814eaba79/scripts/ci/ios_upload_batch_decision.py),
[production App Store workflow](https://github.com/manaflow-ai/cmux/blob/15aa32cfc4bcaedee2f99f0c03b68f9814eaba79/.github/workflows/ios-app-store.yml).
The separate twice-daily iOS DEMO branding/distribution is not needed for this
single Android app. GitHub APK releases are the configured Android delivery
channel; no Play Console application, credentials or Play internal track has been
configured. These workflows do not silently install an APK on the phone.

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

The inventory covers iOS UI, shared/mobile protocol code, phone push, workers,
authentication SDK, Ghostty pins and iOS build definitions. Renames out of a watched
path also count. GitHub limits comparison file inventories to 300 files. A capped
inventory, diverged history or incomplete commit list is stated explicitly; a
capped/diverged comparison always requires review. API failures fail the workflow
rather than turning into an empty, apparently clean report.

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
Once changes are merged to main, the scheduled preview decision counts relevant
commits since the **last published preview source**, not the last green poll.
Five commits or three hours from the oldest relevant commit triggers the existing
signed build. Missing/changed history builds conservatively; unavailable release
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

## Activation and operation

GitHub schedules only workflows present on the default branch. This work is on
`feature/local-mac-bridge` / draft PR #1; **the new schedules are not active until
the workflow changes reach main**. Main still contains the early preview pipeline;
copying the full new Android workflow to that old source by itself would reference
native modules/scripts it does not have. Merge the application and pipeline together
once the draft is ready. Do not claim an active watcher from a local dry run.

After that merge:

1. Run **Upstream cmux watch** once from main to seed the review issue.
2. Run **Android build** from main with `publish_preview=true` for the first
   signed preview. Later schedules batch applicable commits automatically.
3. Download preview APKs from repository Releases. Keep the existing release app
   installed so Android can upgrade it and retain its data.
4. After release acceptance, manually run **Promote Android release** with the
   verified preview tag. This creates the stable/latest designation only.

For a local read-only upstream report:

```sh
python3 scripts/upstream-watch.py --output captures/runtime/upstream-review
```

The GitHub CLI supplies authentication locally; Actions uses its scoped token.
Run policy tests with:

```sh
python3 -m unittest discover -s scripts/tests -p 'test_update_policy.py'
```

## Verification at this checkpoint

Nine policy tests pass, including batching boundaries, unchanged failures,
renames, capped/diverged comparisons, idempotent issue updates and keeping the
implemented reference unchanged. Actionlint 1.7.12 accepts all three workflows.
A live read-only comparison found **696 commits** after audited candidate
`204a11d` at detected head `15aa32c`; the API returned a capped file list and a
partial commit list. The report correctly requires a full review and does not
advance either reference. Evidence is in ignored `captures/runtime/upstream-ci/`.
No issue, preview release, production release, main merge or phone update was
performed by this verification. CI publication/promotion still needs its first
main-branch run after activation.
