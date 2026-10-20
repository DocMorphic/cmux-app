#!/usr/bin/env python3
"""Compile pinned upstream Chief client/models against a local recording RPC stub.

Only external module imports are removed; the actual models, coder, request
builder and client methods execute. The stub has no network implementation.
Outputs are reference vectors, not Android UI or live Chief acceptance.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--vectors", type=Path, help="Write reference vectors to this explicit path")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / "third_party/cmux-chief/manifest.json").read_text())
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    for path, expected in manifest.get("behaviorFiles", {}).items():
        data = subprocess.check_output(["git", "-C", str(args.upstream.resolve()), "show", f'{manifest["revision"]}:{path}'])
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("Unreviewed Chief behavior/artwork: " + path)
        if path.endswith("/ChiefAvatar.png") and hashlib.sha256((root / "app/src/main/res/drawable-nodpi/chief_avatar.png").read_bytes()).hexdigest() != expected:
            raise ValueError("Packaged Chief avatar differs from the pinned source")
    sources = []
    for index, (path, expected) in enumerate(manifest["files"].items()):
        data = subprocess.check_output(["git", "-C", str(args.upstream.resolve()), "show", f'{manifest["revision"]}:{path}'])
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("Unreviewed Chief source: " + path)
        source = re.sub(r"^(?:public )?import (?:CmuxAgentChat|CmuxAgentQuestion|CmuxMobileRPC)\s*$", "", data.decode(), flags=re.M)
        destination = output / f"upstream-{index}.swift"
        destination.write_text("import Foundation\n" + source)
        sources.append(str(destination))
    binary = output / "chief-wire-fixture"
    build = subprocess.run(["/usr/bin/swiftc", "-swift-version", "6", *sources,
                            str(root / "scripts/chief-wire-fixture.swift"), "-o", str(binary)], capture_output=True, timeout=90)
    (output / "build.log").write_bytes(build.stdout + build.stderr); build.check_returncode()
    result = subprocess.check_output([str(binary)], timeout=15)
    (output / "swift-output.json").write_bytes(result)
    vectors = json.loads(result)
    # Preserve UInt64 as Python integers, normalize only dictionary order so
    # Swift's randomized Codable key order does not change committed vectors.
    for model in vectors["models"]:
        if model.get("decoded") is not None:
            model["decoded"] = json.dumps(json.loads(model["decoded"]), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    result = (json.dumps(vectors, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()
    (output / "vectors.json").write_bytes(result)
    if args.vectors:
        args.vectors.parent.mkdir(parents=True, exist_ok=True)
        args.vectors.write_bytes(result)
    receipt = {"source": manifest, "modelCases": len(vectors["models"]), "requests": len(vectors["requests"]), "questionCases": len(vectors["questions"]),
               "vectorsSha256": hashlib.sha256(result).hexdigest(), "scope": "actual upstream Swift models/client with recording stub; no network or UI"}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f'Upstream Chief reference: {len(vectors["models"])} model cases, {len(vectors["requests"])} actual client requests, {len(vectors["questions"])} question cases')


if __name__ == "__main__":
    main()
