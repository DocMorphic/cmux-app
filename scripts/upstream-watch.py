#!/usr/bin/env python3
"""Track upstream mobile changes without treating detection as completed Android work."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from collections import Counter

ROOT = Path(__file__).resolve().parents[1]
MARKER = "<!-- cmux-upstream-watch:v1 -->"
PREFIXES = ("ios/", "Packages/iOS/", "Packages/Shared/", "Packages/macOS/CmuxPhonePush/",
            "Sources/", "workers/", "docs/prd/ios", "vendor/stack-auth-swift")
EXACT = {"ghostty", "ghostty.h", ".gitmodules", ".github/workflows/test-ios.yml",
         ".github/workflows/build-ghosttykit.yml"}


def mobile_script(path):
    return path.startswith("scripts/") and any(word in path.lower() for word in ("ios", "iphone", "mobile", "ghostty"))


def relevant(path):
    return path in EXACT or path.startswith(PREFIXES) or path.startswith(".github/workflows/ios-") or mobile_script(path)


def api(path, method="GET", data=None):
    command = ["gh", "api", "--method", method, path]
    if data is not None:
        command += ["--input", "-"]
    result = subprocess.run(command, input=None if data is None else json.dumps(data),
                            text=True, capture_output=True, check=True, timeout=30)
    return json.loads(result.stdout)


def tree_files(tree):
    """Keep file modes, symlinks and submodule revisions; directory nodes are not changed files."""
    if tree.get("truncated") is not False:
        return None
    result = {}
    for row in tree["tree"]:
        if row["type"] == "tree":
            continue
        path, sha, mode = row["path"], row["sha"], row["mode"]
        if not isinstance(path, str) or not path or path in result or row["type"] not in ("blob", "commit"):
            raise ValueError("Invalid recursive tree entry")
        if not re.fullmatch(r"[0-9a-f]{40}", sha) or mode not in ("100644", "100755", "120000", "160000"):
            raise ValueError("Invalid recursive tree object")
        result[path] = (sha, mode, row["type"])
    return result


def tree_diff(before, after):
    old, new = tree_files(before), tree_files(after)
    if old is None or new is None:
        return None
    return [{"filename": path, "status": "added" if path not in old else "removed" if path not in new else "modified",
             "before_sha": old[path][0] if path in old else None,
             "after_sha": new[path][0] if path in new else None}
            for path in sorted(old.keys() | new.keys()) if old.get(path) != new.get(path)]


def review_area(path):
    if path.startswith(("Packages/macOS/CmuxPhonePush/", "workers/")):
        return "Push and workers"
    if path.startswith("vendor/stack-auth-swift"):
        return "Authentication SDK"
    if path in ("ghostty", "ghostty.h", ".gitmodules"):
        return "Terminal dependencies"
    if path.startswith((".github/", "scripts/")):
        return "Build and release policy"
    if path.startswith("Sources/"):
        return "Mac host"
    if path.startswith("Packages/Shared/"):
        return "Shared protocol and models"
    return "iOS UI and mobile behavior"


def build_report(config, head, compare, complete_files=None, tree_fallback_truncated=False):
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Expected an immutable upstream commit")
    files = compare.get("files", []) if complete_files is None else complete_files
    selected = [row for row in files if relevant(row["filename"]) or relevant(row.get("previous_filename", ""))]
    incomplete = complete_files is None and len(files) >= 300
    history_review = compare.get("status") not in ("ahead", "identical")
    commits = compare.get("commits", [])
    rows = [{"path": row["filename"], "previous_path": row.get("previous_filename"), "status": row["status"],
             "area": review_area(row["filename"] if relevant(row["filename"]) else row["previous_filename"]),
             "before_sha": row.get("before_sha"), "after_sha": row.get("after_sha")} for row in selected]
    return {"upstream": config["repository"], "implemented_ref": config["implemented_ref"],
            "reviewed_ref": config["reviewed_ref"], "detected_ref": head,
            "comparison_status": compare.get("status"), "total_commits": compare.get("total_commits", 0),
            "file_inventory_source": "recursive_trees" if complete_files is not None else "compare",
            "changed_file_count": len(files), "file_inventory_incomplete": incomplete,
            "tree_fallback_truncated": tree_fallback_truncated, "history_requires_review": history_review,
            "commit_inventory_incomplete": compare.get("total_commits", 0) > len(commits),
            "requires_review": bool(selected) or incomplete or history_review,
            "areas": dict(sorted(Counter(row["area"] for row in rows).items())), "files": rows,
            "commits": [{"sha": row["sha"], "subject": row["commit"]["message"].splitlines()[0]} for row in commits]}


def inspect(config, request=api):
    repo, base = config["repository"], config["reviewed_ref"]
    head = request(f"repos/{repo}/commits/main")["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Expected an immutable upstream commit")
    comparison_path = f"repos/{repo}/compare/{base}...{head}"
    comparison = request(comparison_path + "?per_page=100")
    if not isinstance(comparison.get("files"), list) or not isinstance(comparison.get("commits"), list):
        raise ValueError("Comparison response is missing file or commit inventory")
    # Commit pages never extend GitHub's separate 300-file inventory cap.
    commits = list(comparison.get("commits", []))
    seen = {row["sha"] for row in commits}
    for page in range(2, 11):
        if len(commits) >= comparison.get("total_commits", 0):
            break
        rows = request(comparison_path + f"?per_page=100&page={page}").get("commits", [])
        if not rows:
            break
        for row in rows:
            if row["sha"] in seen:
                raise ValueError("Duplicate commit in comparison pagination")
            seen.add(row["sha"])
            commits.append(row)
    comparison = {**comparison, "commits": commits}
    complete_files = None
    if len(comparison.get("files", [])) >= 300:
        before = request(f"repos/{repo}/git/trees/{base}?recursive=1")
        after = request(f"repos/{repo}/git/trees/{head}?recursive=1")
        complete_files = tree_diff(before, after)
    return build_report(config, head, comparison, complete_files,
                        tree_fallback_truncated=len(comparison.get("files", [])) >= 300 and complete_files is None)


def render(report):
    url = "https://github.com/" + report["upstream"]
    base, head = report["reviewed_ref"], report["detected_ref"]
    lines = [MARKER, "## Upstream cmux changes awaiting Android review", "",
             "Detection does not update the implemented parity reference or port Swift code automatically.", "",
             f"- Implemented reference: `{report['implemented_ref']}`",
             f"- Audited candidate: `{base}` (not a completed parity claim)",
             f"- Latest detected commit: [{head[:12]}]({url}/commit/{head})",
             f"- [Review comparison]({url}/compare/{base}...{head}): {report['total_commits']} commits.", ""]
    lines += [f"- Changed-file inventory: {report['changed_file_count']} paths via {report['file_inventory_source']}.", ""]
    if report["commit_inventory_incomplete"]:
        lines += [f"Commit subjects are incomplete ({len(report['commits'])} of {report['total_commits']}); review the immutable comparison for remaining commits.", ""]
    if report["file_inventory_incomplete"] or report["history_requires_review"]:
        lines += ["**The file inventory is incomplete or history diverged. A full source review is required; this report must not be used to skip work.**", ""]
    if report["requires_review"]:
        lines += ["### Porting checklist", "", "- [ ] Review mobile UI, SSH, terminal and shared-protocol changes.",
                  "- [ ] Port applicable changes and update compatibility tests.",
                  "- [ ] Verify affected behavior on Android and the Mac.",
                  "- [ ] Advance the audited/implemented references only with matching evidence.", ""]
    else:
        lines += ["No mobile-relevant file changes were found in the complete comparison.", ""]
    if report["areas"]:
        lines += ["### Review areas", "", "Path-based routing hints; these counts are not completed feature reviews.", ""]
        lines += [f"- {area}: {count} changed paths" for area, count in report["areas"].items()]
        lines.append("")
    if report["tree_fallback_truncated"]:
        lines += ["The recursive-tree fallback was also truncated; obtain the complete trees before closing this review.", ""]
    if report["file_inventory_source"] == "recursive_trees":
        lines += ["Full-tree comparison reports renames as removal/addition, including moves out of watched directories.", ""]
    lines += ["### Relevant paths reported by GitHub", ""]
    for row in report["files"][:80]:
        path = row["path"].replace("`", "'").replace("\n", " ").replace("\r", " ")[:512]
        lines.append(f"- `{path}` ({row['status']})")
    if len(report["files"]) > 80:
        lines.append(f"- {len(report['files']) - 80} more paths in the workflow's JSON artifact.")
    lines += ["", "This issue is maintained by the upstream watcher. Keep investigation notes in comments; its body is replaced on changed upstream evidence."]
    return "\n".join(lines) + "\n"


def publish(repository, body, request=api):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid destination repository")
    # Only the Actions bot's exact marker is managed. Other issues are untouched.
    for page in range(1, 21):
        issues = request(f"repos/{repository}/issues?state=open&per_page=100&page={page}")
        for issue in issues:
            if "pull_request" not in issue and issue.get("user", {}).get("login") == "github-actions[bot]" and (issue.get("body") or "").startswith(MARKER):
                if issue["body"] != body:
                    request(f"repos/{repository}/issues/{issue['number']}", "PATCH", {"body": body})
                return issue["html_url"]
        if len(issues) < 100:
            return request(f"repos/{repository}/issues", "POST", {"title": "Upstream cmux: Android parity review queue", "body": body})["html_url"]
    raise RuntimeError("Issue inventory limit reached; refusing to create a possible duplicate")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    config = json.loads((ROOT / "upstream.json").read_text())
    for field in ("reviewed_ref", "implemented_ref"):
        if not re.fullmatch(r"[0-9a-f]{40}", config[field]):
            raise ValueError("Invalid upstream reference: " + field)
    repo = config["repository"]
    if repo != "manaflow-ai/cmux":
        raise ValueError("Unexpected upstream repository")
    report = inspect(config)
    head = report["detected_ref"]
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    body = render(report)
    (args.output / "report.md").write_text(body)
    if args.publish:
        if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_ACTIONS") != "true":
            raise RuntimeError("Publishing is restricted to the main Actions workflow")
        print(publish(os.environ["GITHUB_REPOSITORY"], body))
    else:
        print(f"Detected {head}; review required: {report['requires_review']}; report: {args.output}")


if __name__ == "__main__":
    main()
