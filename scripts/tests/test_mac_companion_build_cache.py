import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('mac_companion_cache', Path(__file__).resolve().parents[1] / 'mac-companion-build-cache.py')
CACHE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(CACHE)


class MacCompanionBuildCache(unittest.TestCase):
    def test_actual_patched_bytes_override_unchanged_git_index(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            source = root / 'Tracked.swift'; source.write_text('original')
            subprocess.run(['git', '-C', str(root), 'add', source.name], check=True)
            index_before = subprocess.check_output(['git', '-C', str(root), 'ls-files', '--stage'])
            CACHE.normalize_sources(root, [source.name])
            original_time = source.stat().st_mtime_ns
            source.write_text('patched')
            CACHE.normalize_sources(root, [source.name])
            self.assertNotEqual(original_time, source.stat().st_mtime_ns)
            self.assertEqual(index_before, subprocess.check_output(['git', '-C', str(root), 'ls-files', '--stage']))
            self.assertEqual('patched', source.read_text())

    def test_unchanged_content_retains_signature_after_fresh_checkout(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); source = root / 'Source.swift'; source.write_text('same bytes')
            CACHE.normalize_sources(root, [source.name]); before = source.stat().st_mtime_ns
            source.write_text('same bytes'); os.utime(source, None)
            CACHE.normalize_sources(root, [source.name])
            self.assertEqual(before, source.stat().st_mtime_ns)

    def test_added_patch_sources_are_normalized_and_deletions_stay_deleted(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); source = root / 'New.swift'; source.write_text('new patch')
            self.assertEqual(1, CACHE.normalize_sources(root, ['New.swift', 'Deleted.swift']))
            self.assertFalse((root / 'Deleted.swift').exists())
            self.assertEqual(CACHE.stable_mtime(b'new patch'), source.stat().st_mtime_ns)

    def test_symlink_timestamp_does_not_touch_target(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); target = root / 'target'; target.write_text('unchanged')
            before = target.stat().st_mtime_ns
            link = root / 'link'; link.symlink_to(target)
            CACHE.normalize_sources(root, ['link'])
            self.assertEqual(before, target.stat().st_mtime_ns)
            self.assertEqual('unchanged', target.read_text())

    def test_parent_symlink_and_escape_refused_before_any_timestamp_changes(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); source = root / 'A.swift'; source.write_text('keep')
            before = source.stat().st_mtime_ns
            (root / 'alias').symlink_to(root, target_is_directory=True)
            for invalid in ['../outside', '/outside', 'alias/A.swift']:
                with self.subTest(path=invalid), self.assertRaises(ValueError):
                    CACHE.normalize_sources(root, ['A.swift', invalid])
                self.assertEqual(before, source.stat().st_mtime_ns)

    def test_same_contract_can_restore_changed_patch_but_not_claim_exact_inputs(self):
        first = CACHE.cache_keys({'xcode': 'a', 'path': 'one'}, {'Source.swift': 'old'}, '1', '1')
        edited = CACHE.cache_keys({'xcode': 'a', 'path': 'one'}, {'Source.swift': 'new'}, '2', '1')
        self.assertEqual(first['prefix'], edited['prefix'])
        self.assertNotEqual(first['sourcePrefix'], edited['sourcePrefix'])
        self.assertTrue(first['key'].startswith(first['sourcePrefix']))

    def test_toolchain_path_architecture_upstream_and_recipe_are_isolated(self):
        contract = {'xcode': 'a', 'sdk': 'a', 'sourcePath': 'one', 'derivedPath': 'two',
                    'os': '26', 'arch': 'arm64', 'upstream': 'pin', 'workflow': 'recipe', 'cachePolicy': 'policy'}
        first = CACHE.cache_keys(contract, {}, '1', '1')
        for field in contract:
            with self.subTest(field=field):
                changed = CACHE.cache_keys({**contract, field: 'different'}, {}, '2', '1')
                self.assertNotEqual(first['prefix'], changed['prefix'])

    def test_immutable_cache_save_keys_change_for_retries_and_later_runs(self):
        keys = [CACHE.cache_keys({}, {}, run, attempt) for run, attempt in [('1', '1'), ('1', '2'), ('2', '1')]]
        self.assertEqual(3, len({value['key'] for value in keys}))
        self.assertEqual(1, len({value['sourcePrefix'] for value in keys}))
        with self.assertRaises(ValueError): CACHE.cache_keys({}, {}, '../invalid', '1')


if __name__ == '__main__':
    unittest.main()
