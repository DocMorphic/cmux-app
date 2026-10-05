import importlib.util
from pathlib import Path
import unittest
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

watch = load("upstream-watch")
preview = load("android-preview-decision")

class UpdatePolicyTest(unittest.TestCase):
    def test_merged_branch_counts_once_at_merge_time_and_ignores_docs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args, timestamp=1700000000):
                env = dict(os.environ, GIT_AUTHOR_DATE=f"{timestamp} +0000",
                           GIT_COMMITTER_DATE=f"{timestamp} +0000")
                return subprocess.run(["git", *args], cwd=root, env=env,
                                      text=True, capture_output=True, check=True).stdout.strip()
            git("init", "-b", "main")
            git("config", "user.name", "Fixture")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "commit.gpgsign", "false")
            (root / "app").mkdir()
            (root / "app/code.txt").write_text("base")
            git("add", "."); git("commit", "-m", "base")
            base = git("rev-parse", "HEAD")
            git("checkout", "-b", "feature")
            for i in range(6):
                (root / "app/code.txt").write_text(str(i))
                git("add", "."); git("commit", "-m", f"feature {i}")
            side = git("rev-parse", "HEAD")
            git("checkout", "main")
            git("merge", "--no-ff", "feature", "-m", "merge", timestamp=1700020000)
            (root / "README.md").write_text("docs only")
            git("add", "."); git("commit", "-m", "docs", timestamp=1700020100)
            head = git("rev-parse", "HEAD")
            times = preview.relevant_commit_times(base, head, cwd=root)
            self.assertEqual([1700020000], times)
            self.assertFalse(preview.decide(count=len(times), oldest=min(times), now=1700020100)[0])
            with self.assertRaises(ValueError):
                preview.relevant_commit_times(side, head, cwd=root)
            git("mv", "app/code.txt", "archived.txt")
            git("commit", "-am", "move out of app", timestamp=1700020200)
            self.assertEqual([1700020200], preview.relevant_commit_times(head, git("rev-parse", "HEAD"), cwd=root))

    def test_batch_by_count_or_oldest_age(self):
        self.assertFalse(preview.decide(count=4, oldest=0, now=10799)[0])
        self.assertTrue(preview.decide(count=5, oldest=0, now=1)[0])
        self.assertTrue(preview.decide(count=1, oldest=0, now=10800)[0])
    def test_no_repeat_for_unchanged_docs_or_failure(self):
        self.assertFalse(preview.decide(same_head=True, count=99)[0])
        self.assertFalse(preview.decide(count=0)[0])
        self.assertFalse(preview.decide(failed_unchanged=True, first=True)[0])
        self.assertTrue(preview.decide(force=True, failed_unchanged=True, same_head=True)[0])
    def test_first_and_changed_history_build_conservatively(self):
        self.assertTrue(preview.decide(first=True)[0])
        self.assertTrue(preview.decide(history_changed=True)[0])
    def report(self, files, **kwargs):
        return watch.build_report({"repository":"manaflow-ai/cmux", "implemented_ref":"a"*40, "reviewed_ref":"b"*40},
                                  "c"*40, {"files":files, "status":"ahead", "commits":[], "total_commits":1, **kwargs})
    def test_ui_protocol_and_rename_out_of_mobile_are_relevant(self):
        for path in ("ios/App.swift", "Packages/Shared/Wire.swift", "Sources/Mobile/Host.swift",
                     "Sources/TerminalController.swift", ".github/workflows/test-ios.yml", "workers/presence/src/index.ts"):
            self.assertTrue(self.report([{"filename":path,"status":"modified"}])["requires_review"])
        self.assertTrue(self.report([{"filename":"archive/old.swift","previous_filename":"ios/old.swift","status":"renamed"}])["requires_review"])
    def test_complete_unrelated_diff_is_quiet_but_truncation_is_not(self):
        unrelated = {"filename":"website/blog.txt","status":"modified"}
        self.assertFalse(self.report([unrelated])["requires_review"])
        report = self.report([unrelated]*300)
        self.assertTrue(report["requires_review"]); self.assertTrue(report["file_inventory_incomplete"])
        self.assertIn("full source review", watch.render(report))
    def test_diverged_history_and_commit_truncation_are_explicit(self):
        report = self.report([], status="diverged", total_commits=101)
        self.assertTrue(report["requires_review"]); self.assertTrue(report["commit_inventory_incomplete"])
    def test_issue_update_is_idempotent_and_does_not_touch_human_issues(self):
        calls = []
        issue = {"number":7,"html_url":"https://example.invalid/7","user":{"login":"github-actions[bot]"},"body":watch.MARKER+"same"}
        def request(path, method="GET", data=None):
            calls.append((path,method,data)); return [issue] if method=="GET" else {}
        watch.publish("owner/repo", issue["body"], request)
        self.assertEqual(["GET"], [item[1] for item in calls])
        watch.publish("owner/repo", watch.MARKER+"changed", request)
        self.assertEqual("PATCH", calls[-1][1]); self.assertTrue(calls[-1][0].endswith("/7"))
    def test_report_does_not_advance_implemented_reference(self):
        report = self.report([])
        self.assertEqual("a"*40, report["implemented_ref"])
        self.assertEqual("b"*40, report["reviewed_ref"])
        self.assertEqual("c"*40, report["detected_ref"])
    def test_mobile_release_scripts_are_watched(self):
        for path in ("scripts/ci/ios_upload_batch_decision.py", "scripts/mobile_attach_test.sh",
                     "scripts/ghostty_checksum.py", ".github/workflows/build-ghosttykit.yml"):
            self.assertTrue(self.report([{"filename":path,"status":"modified"}])["requires_review"])
        self.assertFalse(watch.relevant("scripts/website_deploy.py"))

    def tree(self, rows, truncated=False):
        return {"truncated":truncated, "tree":[
            {"path":path, "sha":sha, "mode":mode, "type":"commit" if mode=="160000" else "blob"}
            for path, sha, mode in rows]}

    def test_tree_diff_preserves_mode_submodule_and_moves_out_of_watched_paths(self):
        before = self.tree([("ios/old.swift", "a"*40, "100644"),
                            ("scripts/ios.sh", "b"*40, "100644"), ("ghostty", "c"*40, "160000")])
        after = self.tree([("archive/old.swift", "a"*40, "100644"),
                           ("scripts/ios.sh", "b"*40, "100755"), ("ghostty", "d"*40, "160000")])
        diff = watch.tree_diff(before, after)
        self.assertEqual({"archive/old.swift":"added", "ios/old.swift":"removed",
                          "scripts/ios.sh":"modified", "ghostty":"modified"},
                         {row["filename"]:row["status"] for row in diff})
        report = watch.build_report({"repository":"manaflow-ai/cmux", "implemented_ref":"a"*40,
                                     "reviewed_ref":"b"*40}, "c"*40,
                                    {"files":[], "commits":[], "status":"ahead"}, complete_files=diff)
        self.assertEqual(3, len(report["files"]))
        self.assertTrue(report["requires_review"])

    def inspect_fixture(self, *, truncated=False, unrelated=False, missing_files=False, duplicate=False):
        config = {"repository":"manaflow-ai/cmux", "implemented_ref":"a"*40, "reviewed_ref":"b"*40}
        commits = [{"sha":f"{i:040x}", "commit":{"message":f"change {i}"}} for i in range(101)]
        files = [{"filename":f"website/{i}.txt", "status":"modified"} for i in range(300)]
        paths = [(f"website/{i}.txt", "a"*40, "100644") for i in range(300)]
        paths.append(("website/last.txt" if unrelated else "ios/Last.swift", "a"*40, "100644"))
        calls = []
        def request(path):
            calls.append(path)
            if path.endswith("commits/main"):
                return {"sha":"c"*40}
            if "compare/" in path:
                if "page=2" in path:
                    # A later page must not replace the first page's file inventory.
                    return {"files":[], "commits":[commits[0] if duplicate else commits[-1]]}
                result = {"files":files, "commits":commits[:100], "total_commits":101, "status":"ahead"}
                if missing_files:
                    del result["files"]
                return result
            if "/git/trees/" in path:
                return self.tree([] if "b"*40 in path else paths, truncated=truncated)
            self.fail(path)
        return watch.inspect(config, request), calls

    def test_capped_comparison_finds_mobile_path_beyond_limit_and_paginates_commits(self):
        report, calls = self.inspect_fixture()
        self.assertEqual(["ios/Last.swift"], [row["path"] for row in report["files"]])
        self.assertEqual(301, report["changed_file_count"])
        self.assertEqual(101, len(report["commits"]))
        self.assertFalse(report["file_inventory_incomplete"])
        self.assertFalse(report["commit_inventory_incomplete"])
        self.assertEqual("recursive_trees", report["file_inventory_source"])
        self.assertTrue(report["requires_review"])
        self.assertEqual(1, sum(path.endswith("commits/main") for path in calls))
        self.assertTrue(all("c"*40 in path for path in calls if "compare/" in path))

    def test_truncated_tree_stays_conservative_but_complete_unrelated_tree_is_quiet(self):
        truncated, _ = self.inspect_fixture(truncated=True)
        self.assertTrue(truncated["requires_review"])
        self.assertTrue(truncated["file_inventory_incomplete"])
        self.assertIn("fallback was also truncated", watch.render(truncated))
        complete, _ = self.inspect_fixture(unrelated=True)
        self.assertFalse(complete["requires_review"])
        self.assertFalse(complete["file_inventory_incomplete"])

    def test_incomplete_response_and_duplicate_commit_fail_instead_of_reporting_clean(self):
        with self.assertRaisesRegex(ValueError, "missing file"):
            self.inspect_fixture(missing_files=True)
        with self.assertRaisesRegex(ValueError, "Duplicate commit"):
            self.inspect_fixture(duplicate=True)

    def test_invalid_tree_object_is_rejected(self):
        tree = self.tree([("ios/File.swift", "not-an-object-id", "100644")])
        with self.assertRaisesRegex(ValueError, "Invalid recursive tree"):
            watch.tree_diff(self.tree([]), tree)
    def test_draft_or_partial_upload_cannot_become_last_preview(self):
        from unittest.mock import patch
        sha = "d"*40
        release = {"draft":False,"tag_name":"android-preview-300","target_commitish":sha,
                   "body":f"<!-- cmux-preview-source: {sha} -->","assets":[]}
        with patch.object(preview, "api", return_value=[release]):
            self.assertIsNone(preview.last_preview("owner/repo"))
            release["assets"] = [{"name":name} for name in ("app-release.apk","SHA256SUMS","manifest.json")]
            self.assertEqual(sha, preview.last_preview("owner/repo"))
            release["draft"] = True
            self.assertIsNone(preview.last_preview("owner/repo"))

if __name__ == "__main__":
    unittest.main()
