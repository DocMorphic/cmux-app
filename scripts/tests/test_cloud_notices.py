import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("cloud_notices", Path(__file__).resolve().parents[1] / "collect-cloud-notices.py")
notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notices)


class CloudNoticesTest(unittest.TestCase):
    def test_transitive_runtime_and_build_dependencies_exclude_dev_only_crates(self):
        names = ["cmux-terminal-client", "normal", "build", "transitive", "dev-only"]
        def edge(name, kind=None):
            return {"pkg": name, "dep_kinds": [{"kind": kind}]}
        data = {"packages": [{"id": n, "name": n, "version": "1"} for n in names], "resolve": {"nodes": [
            {"id": names[0], "deps": [edge("normal"), edge("build", "build"), edge("dev-only", "dev")]},
            {"id": "normal", "deps": [edge("transitive")]},
            {"id": "build", "deps": [edge("transitive"), edge("dev-only", "dev")]},
            {"id": "transitive", "deps": []}, {"id": "dev-only", "deps": []}]}}
        self.assertEqual({p["name"] for p in notices.runtime_packages(data)}, set(names[:-1]))

    def test_preserve_nested_notices_and_exclude_external_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "package"; root.mkdir()
            license = root / "LICENSE"; license.write_text("own license")
            nested = root / "vendor"; nested.mkdir()
            third_party = nested / "NOTICE.txt"; third_party.write_text("third-party attribution")
            private = Path(directory) / "private"; private.write_text("not part of the package")
            (root / "LICENSE-external").symlink_to(private)
            workflow = root / ".github/workflows"; workflow.mkdir(parents=True)
            (workflow / "license.yml").write_text("not a license text")
            (nested / "license.rs").write_text("// source code, not a license file")
            self.assertEqual(notices.legal_files(root), [license, third_party])
