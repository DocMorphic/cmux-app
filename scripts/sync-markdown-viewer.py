#!/usr/bin/env python3
"""Copy exact shared cmux Markdown assets from a local upstream checkout.

Usage: python3 scripts/sync-markdown-viewer.py /path/to/cmux
When changing PIN, also review and update licenses/Markdown.txt and NOTICE.md.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

PIN = "4c5272e9153eca2033c9f40ac749f0c3a5bcb291"
NAMES = ["shell.html", "viewer-navigation.js", "github-markdown.css", "highlight-github.css",
         "highlight-github-dark.css", "marked.min.js", "highlight.min.js", "mermaid.min.js",
         "vega.min.js", "vega-lite.min.js", "vega-embed.min.js"]

def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    assets = {name: subprocess.check_output(["git", "-C", sys.argv[1], "show",
              f"{PIN}:Resources/markdown-viewer/{name}"]) for name in NAMES}
    root = Path(__file__).resolve().parents[1] / "app/src/main/assets/markdown-viewer"
    root.mkdir(parents=True, exist_ok=True)
    for name, data in assets.items():
        (root / name).write_bytes(data)
    manifest = dict(upstream=PIN, files={name: hashlib.sha256(data).hexdigest() for name, data in assets.items()})
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Copied {len(assets)} original assets from {PIN}.")

if __name__ == "__main__":
    main()
