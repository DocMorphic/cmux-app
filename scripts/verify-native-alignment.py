#!/usr/bin/env python3
"""Check 16 KiB LOAD and RELRO alignment in every arm64/x86_64 ELF in an APK/AAR.

ZIP alignment is a separate check: zipalign -c -P 16 -v 4 app.apk.
See https://developer.android.com/guide/practices/page-sizes#elf-alignment
"""
import argparse
from pathlib import Path
import struct
import zipfile


def verify(data, label):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{label}: expected a little-endian ELF64 library")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    if header[2] not in (183, 62):
        raise ValueError(f"{label}: unsupported ELF architecture {header[2]}")
    offset, entry_size, count = header[5], header[9], header[10]
    if entry_size < 56 or not count or offset + entry_size * count > len(data):
        raise ValueError(f"{label}: invalid program headers")
    loads = relros = 0
    for i in range(count):
        kind, flags, start, address, _, size, memory, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset + i * entry_size)
        if kind == 1:
            loads += 1
            if alignment < 16384 or alignment & (alignment - 1) or (address - start) % 16384:
                raise ValueError(f"{label}: LOAD segment is not 16 KiB aligned")
        elif kind == 0x6474e552:
            relros += 1
            if (address + memory) % 16384:
                raise ValueError(f"{label}: RELRO end 0x{address + memory:x} is not 16 KiB aligned")
    if not loads:
        raise ValueError(f"{label}: missing LOAD segments")
    return f"{label}: PASS ({loads} LOAD, {relros} RELRO)"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", type=Path, nargs="+")
    args = parser.parse_args()
    errors = []
    for path in args.files:
        if path.suffix in (".apk", ".aar"):
            with zipfile.ZipFile(path) as archive:
                entries = [(n, archive.read(n)) for n in archive.namelist() if n.endswith(".so")]
        else:
            entries = [(path.name, path.read_bytes())]
        if not entries:
            errors.append(f"{path}: no native libraries found")
        for name, data in entries:
            try:
                print(verify(data, f"{path.name}/{name}"))
            except ValueError as error:
                errors.append(str(error))
    if errors:
        raise SystemExit("\n".join(errors))


if __name__ == "__main__":
    main()
