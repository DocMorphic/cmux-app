#!/usr/bin/env python3
"""Verify the unmodified pinned raw-code, Markdown and DOCX viewer assets on any OS."""
import hashlib
import json
from pathlib import Path
import sys


def main():
    root = Path(__file__).resolve().parents[1] / "app/src/main/assets"
    failures = []
    checked = 0
    for directory in ("raw-code", "markdown-viewer", "docx-viewer"):
        folder = root / directory
        manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
        files = manifest["files"]
        entries = files.items() if isinstance(files, dict) else ((item["asset"], item["sha256"]) for item in files)
        for name, expected in entries:
            path = folder / name
            actual = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else "missing"
            checked += 1
            if actual != expected:
                failures.append(f"{directory}/{name}: expected {expected}, got {actual}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Verified {checked} pinned viewer assets.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
