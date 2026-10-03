import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location("alignment", Path(__file__).resolve().parents[1] / "verify-native-alignment.py")
alignment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alignment)


def elf(*segments):
    header = struct.pack("<16sHHIQQQIHHHHHH", b"\x7fELF\x02\x01" + bytes(10),
                         3, 183, 1, 0, 64, 0, 0, 64, 56, len(segments), 0, 0, 0)
    return header + b"".join(struct.pack("<IIQQQQQQ", *s) for s in segments)


def load(memory, *, address=0x20000, flags=6, page=16384, offset=0):
    return (1, flags, offset, address, 0, 0, memory, page)


def relro(memory, *, address=0x20000):
    return (0x6474e552, 4, 0, address, 0, 0, memory, 1)


class NativeAlignmentTest(unittest.TestCase):
    def passes(self, *segments):
        self.assertIn("PASS", alignment.verify(elf(*segments), "fixture"))

    def fails(self, *segments, message="RELRO end"):
        with self.assertRaisesRegex(ValueError, message):
            alignment.verify(elf(*segments), "fixture")

    def test_whole_rw_load_may_end_between_16k_boundaries(self):
        self.passes(load(0x5000), relro(0x5000))

    def test_relro_padding_beyond_whole_load_is_allowed(self):
        self.passes(load(0x4800), relro(0x5000))

    def test_partial_relro_cannot_leave_writable_suffix_on_same_16k_page(self):
        self.fails(load(0x6000), relro(0x5000))

    def test_aligned_prefix_remains_valid(self):
        self.passes(load(0x6000), relro(0x4000))

    def test_unrelated_load_cannot_exempt_a_bad_prefix(self):
        self.fails(load(0x6000), load(0x4000, address=0x40000), relro(0x5000))

    def test_ambiguous_loads_do_not_gain_exemption(self):
        self.fails(load(0x4000), load(0x6000), relro(0x5000))

    def test_non_rw_layout_remains_conservative(self):
        self.fails(load(0x4000, flags=5), relro(0x5000))

    def test_whole_relro_does_not_exempt_small_load_alignment(self):
        self.fails(load(0x5000, page=4096), relro(0x5000), message="LOAD segment")

    def test_whole_relro_does_not_exempt_offset_mismatch(self):
        self.fails(load(0x5000, offset=4096), relro(0x5000), message="LOAD segment")


if __name__ == "__main__":
    unittest.main()
