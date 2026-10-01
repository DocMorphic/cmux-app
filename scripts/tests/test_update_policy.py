import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

watch = load("upstream-watch")
preview = load("android-preview-decision")

class UpdatePolicyTest(unittest.TestCase):
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
        for path in ("ios/App.swift", "Packages/Shared/Wire.swift", "Sources/Mobile/Host.swift", "workers/presence/src/index.ts"):
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
