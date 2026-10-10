#!/usr/bin/env python3
"""Deterministic authored RTF paragraphs, fields, codepage/Unicode and pictures."""
from pathlib import Path
import struct
import zlib


def png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    rows = b"".join(b"\0" + b"\xf0\x20\x20" * 32 + b"\x20\x40\xf0" * 32 for _ in range(32))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 32, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def wmf():
    def record(function, *params):
        return struct.pack("<IH", 3 + len(params), function) + struct.pack("<" + "h" * len(params), *params)
    records = [record(0x020B, 0, 0), record(0x020C, 50, 100), record(0x041B, 40, 90, 10, 10), record(0)]
    body = b"".join(records)
    return struct.pack("<HHHIHIH", 1, 9, 0x0300, 9 + len(body) // 2, 0, max(len(r) // 2 for r in records), 0) + body


def emf():
    rectangle = struct.pack("<IIiiii", 43, 24, 10, 10, 90, 40)
    eof = struct.pack("<IIIII", 14, 20, 0, 0, 20)
    header = struct.pack("<IIiiiiiiiiIIIIHHIIIiiii", 1, 88, 0, 0, 100, 50, 0, 0, 2646, 1323,
                         0x464D4520, 0x00010000, 132, 3, 1, 0, 0, 0, 0, 100, 50, 26, 13)
    assert len(header) == 88
    return header + rectangle + eof


def main():
    folder = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/rtf"
    folder.mkdir(parents=True, exist_ok=True)
    picture = lambda kind, data: r"{\pict" + chr(92) + kind + r"\picw100\pich50\picwgoal2400\pichgoal1200 " + data.hex() + "}"
    text = (r"{\rtf1\ansi\ansicpg1252\deff0\uc1"
            r"{\fonttbl{\f0\fnil Arial;}{\f1\fnil Courier New;}}"
            r"{\colortbl;\red180\green20\blue40;\red20\green80\blue180;}"
            r"\pard\fs32\b RTF preview fixture\b0\fs24\par "
            r"R\'e9sum\'e9 \u26085?\u26412?\u35486?\par "
            r"{\b Bold} {\i italic} {\ul underlined} {\cf1 red text} {\f1 monospace}\par "
            r"\qc Centered paragraph\par\ql Literal <script> text and escaped \{braces\}.\par "
            r'{\field{\*\fldinst HYPERLINK "https://example.invalid/rtf"}{\fldrslt Safe link}}\par '
            r'{\field{\*\fldinst HYPERLINK "javascript:window.__cmuxUnsafeRtf=1"}{\fldrslt Blocked active link}}\par '
            r'{\field{\*\fldinst INCLUDEPICTURE "https://example.invalid/private.png"}{\fldrslt External image label}}\par '
            + picture("pngblip", png()) + r"\par " + picture("wmetafile8", wmf()) + r"\par " + picture("emfblip", emf()) + r"\par ")
    text += "".join(r"\pard\fs24 Paragraph " + str(i) + r" with offline reader content.\par " for i in range(1, 81))
    (folder / "rich.rtf").write_bytes((text + "RTF_FINAL_MARKER}").encode("ascii"))


if __name__ == "__main__":
    main()
