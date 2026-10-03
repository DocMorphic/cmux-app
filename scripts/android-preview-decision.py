#!/usr/bin/env python3
"""Batch Android previews like cmux's internal iOS lane: 5 commits or 3 hours."""
import argparse
import json
import os
import re
import subprocess
import time

PATHS = ["app", "ghostty", "iroh", "third_party", "gradle", "gradlew", "gradlew.bat",
         "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "scripts",
         ".github/workflows/android.yml", "upstream.json"]
MARKER = re.compile(r"<!-- cmux-preview-source: ([0-9a-f]{40}) -->")


def decide(*, force=False, same_head=False, failed_unchanged=False, first=False,
           history_changed=False, count=0, oldest=None, now=None):
    if force:
        return True, "manual preview requested"
    if same_head:
        return False, "this commit already has a published preview"
    if failed_unchanged:
        return False, "last attempt failed; waiting for a relevant change or manual retry"
    if first or history_changed:
        return True, "first preview or changed history"
    if count == 0:
        return False, "no Android-relevant commits since the last preview"
    age = max(0, (time.time() if now is None else now) - oldest)
    if count >= 5 or age >= 180 * 60:
        return True, f"batch ready: {count} relevant commits, oldest {int(age / 60)} minutes"
    return False, f"batch waiting: {count}/5 relevant commits, oldest {int(age / 60)}/180 minutes"


def command(*args):
    return subprocess.run(args, text=True, capture_output=True, check=True).stdout.strip()


def api(path):
    return json.loads(command("gh", "api", path))


def relevant_commit_times(base, head, cwd=None):
    """Count main's landed changes, with merge time rather than branch age."""
    def git(*args):
        return subprocess.run(["git", *args], cwd=cwd, text=True,
                              capture_output=True, check=True).stdout.strip()

    # --boundary can include a merge's second parent even with --first-parent.
    # Check actual main ancestry instead of accepting that side-branch boundary.
    if base not in git("rev-list", "--first-parent", head).splitlines():
        raise ValueError("Last preview is not on main's first-parent history")
    rows = git("log", "--first-parent", "--format=%H:%ct", f"{base}..{head}").splitlines()
    times = []
    for row in rows:
        sha, committed = row.split(":")
        # Explicit first-parent diff also covers merge conflict resolutions and
        # renames out of app paths. Never count the merged branch's commits again.
        if git("diff", "--name-only", "--no-renames", sha + "^1", sha, "--", *PATHS):
            times.append(int(committed))
    return times


def last_preview(repository):
    for page in range(1, 11):
        releases = api(f"repos/{repository}/releases?per_page=100&page={page}")
        for release in releases:
            if release["draft"] or not re.fullmatch(r"android-preview-[0-9]+", release["tag_name"]):
                continue
            if not {"app-release.apk", "SHA256SUMS", "manifest.json"}.issubset(
                    {asset["name"] for asset in release.get("assets", [])}):
                continue
            match = MARKER.search(release.get("body") or "")
            if match and release["target_commitish"] == match[1]:
                return match[1]
        if len(releases) < 100:
            return None
    raise RuntimeError("Release inventory incomplete; refusing to guess the last preview")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--output", default=os.environ.get("GITHUB_OUTPUT"))
    args = parser.parse_args()
    repo = os.environ["GITHUB_REPOSITORY"]
    head = command("git", "rev-parse", "HEAD")
    base = last_preview(repo)
    runs = api(f"repos/{repo}/actions/workflows/android.yml/runs?branch=main&status=failure&per_page=100")["workflow_runs"]
    failed = next((run for run in runs if run["event"] == "schedule" and run["conclusion"] == "failure"), None)
    # A previous published SHA supersedes failures before it. A newer successful
    # upload is recorded by a release, never by a merely green decision-only run.
    failed_unchanged = False
    if failed and failed["head_sha"] != base:
        fail_sha = failed["head_sha"]
        if fail_sha == head:
            failed_unchanged = True
        elif re.fullmatch(r"[0-9a-f]{40}", fail_sha):
            exists = subprocess.run(["git", "cat-file", "-e", fail_sha], capture_output=True).returncode == 0
            if exists:
                failed_unchanged = not command("git", "diff", "--name-only", fail_sha, head, "--", *PATHS)
    changed = False
    times = []
    if base and base != head:
        changed = subprocess.run(["git", "merge-base", "--is-ancestor", base, head], capture_output=True).returncode != 0
        if not changed:
            try:
                times = relevant_commit_times(base, head)
            except ValueError:
                changed = True
    build, reason = decide(force=args.force, same_head=base == head, first=base is None,
                           history_changed=changed, failed_unchanged=failed_unchanged,
                           count=len(times), oldest=min(times, default=None))
    print(reason)
    if args.output:
        with open(args.output, "a") as output:
            output.write(f"should_build={str(build).lower()}\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(f"## Android preview decision\n\n{reason}\n\nSource: `{head}`\n")


if __name__ == "__main__":
    main()
