import importlib.util
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS=Path(__file__).resolve().parents[1]


@unittest.skipUnless(platform.system()=='Darwin' and shutil.which('swiftc'), 'Requires native macOS CoreFoundation and Swift')
class NativeFoundationProviderExport(unittest.TestCase):
    def test_full_export_preserves_exact_unicode_units(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);main=root/'main.swift';report=root/'provider.json';binary=root/'provider'
            main.write_text('''import Foundation
let facts = try sourceFoundationProviderFacts()
let data = try JSONSerialization.data(withJSONObject: facts, options: [.sortedKeys])
try data.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
''')
            subprocess.run(['swiftc','-swift-version','6',str(SCRIPTS/'native-chief-foundation-probe.swift'),str(main),'-o',str(binary)],
                check=True,capture_output=True,timeout=60)
            subprocess.run([str(binary),str(report)],check=True,capture_output=True,timeout=30)
            facts=json.loads(report.read_text())
            spec=importlib.util.spec_from_file_location('provider_capture_verifier',SCRIPTS/'verify-ios-chief-bundles.py')
            verifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(verifier)
            summary=verifier.validate_foundation_provider(facts)
            self.assertEqual(1112064,summary['scalarQueries'])
            self.assertEqual('a',facts['caseFold']['41'])
            self.assertEqual('ss',facts['caseFold']['df'])
            # Swift String == would incorrectly discard these canonically equal
            # but code-unit-distinct provider mappings.
            self.assertEqual('j\u030c',facts['caseFold']['1f0'])
            self.assertEqual('e\u0301',facts['canonicalDecomposition']['e9'])
            self.assertEqual(230,facts['combiningClass']['301'])
            self.assertTrue(any(first<=0x34f<=last for first,last in facts['graphemeExtendRanges']))
            self.assertTrue(any(first<=0x34f<=last for first,last in facts['letterRanges']))
            self.assertTrue(any(first<=0x200b<=last for first,last in facts['whitespaceAndNewlineRanges']))


if __name__=='__main__':unittest.main()
