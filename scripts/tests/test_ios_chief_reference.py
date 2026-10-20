"""Integrity checks for reference export association; these synthetic PNGs are not UIKit evidence."""
import binascii
from contextlib import redirect_stdout
import hashlib
import importlib.util
import json
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('ios_reference_verifier', ROOT / 'scripts/verify-ios-chief-reference.py')
VERIFIER = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(VERIFIER)


def png(color, width=2, height=3):
    def chunk(kind, payload):
        return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', binascii.crc32(kind + payload) & 0xffffffff)
    header = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    pixels = (b'\x00' + bytes([color, 25, 50, 255]) * width) * height
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


class ReferenceIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.exported = Path(self.temporary.name) / 'exported'; self.exported.mkdir()
        self.output = Path(self.temporary.name) / 'canonical'
        self.manifest = json.loads((ROOT / 'third_party/cmux-chief/manifest.json').read_text())
        source = (ROOT / 'tests/ios-chief-reference/Scenes.json').read_bytes()
        self.scenes = json.loads(source)['scenes']; scene_sha = hashlib.sha256(source).hexdigest()
        self.commit = 'a' * 40
        for index, scene in enumerate(self.scenes):
            image = png(index); name = scene['id']
            (self.exported / (name + '.png')).write_bytes(image)
            value = {'scene': name, 'fixtureSha256': scene_sha, 'imageSha256': hashlib.sha256(image).hexdigest(),
                     'viewport': [2, 3], 'scale': 1, 'labels': [{'text': 'Synthetic verifier test'}], 'storeMessages': 1,
                     'provenance': {'scenesSha256': scene_sha, 'upstreamRevision': self.manifest['revision'],
                                    'packageTree': self.manifest['referencePackage']['tree'],
                                    'projectSourceCommit': self.commit, 'projectSourceDirty': False}}
            (self.exported / (name + '.json')).write_text(json.dumps(value))

    def verify(self):
        with redirect_stdout(io.StringIO()): VERIFIER.verify(self.exported, self.output, self.commit)
    def mutate(self, change):
        path = self.exported / (self.scenes[0]['id'] + '.json'); value = json.loads(path.read_text())
        change(value); path.write_text(json.dumps(value))

    def test_complete_payloads_are_canonicalized_and_missing_or_changed_png_is_rejected(self):
        self.verify()
        self.assertEqual(len(self.scenes), len(list(self.output.glob('*.png'))))
        self.assertEqual(self.manifest['revision'], json.loads((self.output / 'receipt.json').read_text())['revision'])
        target = self.exported / (self.scenes[0]['id'] + '.png'); target.write_bytes(png(250))
        with self.assertRaisesRegex(ValueError, 'Missing or changed screenshot'): self.verify()

    def test_unreviewed_source_is_rejected_before_writing_an_output(self):
        self.mutate(lambda value: value['provenance'].update(packageTree='f' * 40))
        with self.assertRaisesRegex(ValueError, 'Unreviewed UIKit'): self.verify()
        self.assertFalse(self.output.exists())

    def test_an_old_project_commit_cannot_be_accepted_for_the_current_run(self):
        self.mutate(lambda value: value['provenance'].update(projectSourceCommit='b' * 40))
        with self.assertRaisesRegex(ValueError, 'Unexpected reference project commit'): self.verify()

    def test_a_missing_scene_is_not_a_successful_partial_reference(self):
        (self.exported / (self.scenes[0]['id'] + '.json')).unlink()
        with self.assertRaisesRegex(ValueError, 'Missing/unexpected rendered scenes'): self.verify()

    def test_image_dimensions_must_match_the_actual_capture_viewport(self):
        self.mutate(lambda value: value.update(viewport=[200, 300]))
        with self.assertRaisesRegex(ValueError, 'Wrong rendered image dimensions'): self.verify()


if __name__ == '__main__': unittest.main()
