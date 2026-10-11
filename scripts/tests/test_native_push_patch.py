"""Installer refusal/rollback tests; real native syntax/behavior is checked separately."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("native_push_patch", ROOT / "scripts/apply-native-push-patch.py")
INSTALLER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSTALLER)


class NativePushPatchSafety(unittest.TestCase):
    def fixture(self, target):
        originals = {}
        for path in INSTALLER.PINS:
            destination = target / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            originals[path] = b"unreviewed source\n"
            destination.write_bytes(originals[path])
        unrelated = target / "unrelated.txt"
        unrelated.write_text("keep this user file")
        return originals

    def assert_unchanged(self, target, originals):
        for path, data in originals.items():
            self.assertEqual((target / path).read_bytes(), data)
        self.assertEqual((target / "unrelated.txt").read_text(), "keep this user file")
        self.assertEqual(list(target.rglob(".cmux-android-push-*")), [])

    def test_unreviewed_source_is_refused_before_any_write(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary); original = self.fixture(target)
            with self.assertRaisesRegex(ValueError, "Source mismatch"):
                INSTALLER.install(target)
            self.assert_unchanged(target, original)

    def test_existing_added_source_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary); original = self.fixture(target)
            existing = "Sources/Cloud/NativePushSourceSink.swift"
            (target / existing).write_bytes(b"user implementation\n")
            proposed = {path: b"replacement\n" for path in original}
            proposed[existing] = b"new source\n"
            with patch.object(INSTALLER, "patched_sources", return_value=proposed):
                with self.assertRaisesRegex(ValueError, "Refusing to replace"):
                    INSTALLER.install(target)
            self.assert_unchanged(target, original)
            self.assertEqual((target / existing).read_bytes(), b"user implementation\n")

    def test_interrupted_multi_file_install_restores_originals_and_removes_staging(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary); original = self.fixture(target)
            proposed = {path: b"replacement\n" for path in original}
            proposed["Sources/Cloud/NativePushSourceSink.swift"] = b"new source\n"
            replace = INSTALLER.os.replace
            calls = 0
            def interrupted(source, destination):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise OSError("simulated interrupted install")
                return replace(source, destination)
            with patch.object(INSTALLER, "patched_sources", return_value=proposed), patch.object(INSTALLER.os, "replace", side_effect=interrupted):
                with self.assertRaisesRegex(OSError, "interrupted install"):
                    INSTALLER.install(target)
            self.assert_unchanged(target, original)
            self.assertFalse((target / "Sources/Cloud/NativePushSourceSink.swift").exists())

    def test_check_mode_does_not_modify_checkout(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary); original = self.fixture(target)
            proposed = {path: b"replacement\n" for path in original}
            with patch.object(INSTALLER, "patched_sources", return_value=proposed):
                self.assertEqual(INSTALLER.install(target, check=True), proposed)
            self.assert_unchanged(target, original)
