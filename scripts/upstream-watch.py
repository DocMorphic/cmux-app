#!/usr/bin/env python3
"""Track upstream mobile changes without treating detection as completed Android work."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MARKER = "<!-- cmux-upstream-watch:v1 -->"
PREFIXES = ("ios/", "Packages/iOS/", "Packages/Shared/", "Packages/macOS/CmuxPhonePush/",
            "Sources/", "workers/", "docs/prd/ios", "vendor/stack-auth-swift")
EXACT = {"ghostty", "ghostty.h", ".gitmodules", ".github/workflows/test-ios.yml"}


def relevant(path):
    return path in EXACT or path.startswith(PREFIXES) or path.startswith(".github/workflows/ios-")


def api(path, method="GET", data=None):
    command = ["gh", "api", "--method", method, path]
    if data is not None:
        command += ["--input", "-"]
    result = subprocess.run(command, input=None if data is None else json.dumps(data),
                            text=True, capture_output=True, check=True, timeout=30)
    return json.loads(result.stdout)


def build_report(config, head, compare):
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Expected an immutable upstream commit")
    files = compare.get("files", [])
    selected = [row for row in files if relevant(row["filename"]) or relevant(row.get("previous_filename", ""))]
    incomplete = len(files) >= 300 or compare.get("status") not in ("ahead", "identical")
    commits = compare.get("commits", [])
    return {"upstream": config["repository"], "implemented_ref": config["implemented_ref"],
            "reviewed_ref": config["reviewed_ref"], "detected_ref": head,
            "comparison_status": compare.get("status"), "total_commits": compare.get("total_commits", 0),
            "file_inventory_incomplete": incomplete,
            "commit_inventory_incomplete": compare.get("total_commits", 0) > len(commits),
            "requires_review": bool(selected) or incomplete,
            "files": [{"path": row["filename"], "previous_path": row.get("previous_filename"),
                       "status": row["status"]} for row in selected],
            "commits": [{"sha": row["sha"], "subject": row["commit"]["message"].splitlines()[0]} for row in commits]}


def render(report):
    url = "https://github.com/" + report["upstream"]
    base, head = report["reviewed_ref"], report["detected_ref"]
    lines = [MARKER, "## Upstream cmux changes awaiting Android review", "",
             "Detection does not update the implemented parity reference or port Swift code automatically.", "",
             f"- Implemented reference: `{report['implemented_ref']}`",
             f"- Audited candidate: `{base}` (not a completed parity claim)",
             f"- Latest detected commit: [{head[:12]}]({url}/commit/{head})",
             f"- [Review comparison]({url}/compare/{base}...{head}): {report['total_commits']} commits.", ""]
    if report["file_inventory_incomplete"]:
        lines += ["**The file inventory is incomplete or history diverged. A full source review is required; this report must not be used to skip work.**", ""]
    if report["requires_review"]:
        lines += ["### Porting checklist", "", "- [ ] Review mobile UI, SSH, terminal and shared-protocol changes.",
                  "- [ ] Port applicable changes and update compatibility tests.",
                  "- [ ] Verify affected behavior on Android and the Mac.",
                  "- [ ] Advance the audited/implemented references only with matching evidence.", ""]
    else:
        lines += ["No mobile-relevant file changes were found in the complete comparison.", ""]
    lines += ["### Relevant paths reported by GitHub", ""]
    for row in report["files"][:80]:
        path = row["path"].replace("`", "'").replace("\n", " ")
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
    head = api(f"repos/{repo}/commits/main")["sha"]
    comparison = api(f"repos/{repo}/compare/{config['reviewed_ref']}...{head}?per_page=100")
    report = build_report(config, head, comparison)
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
