#!/usr/bin/env python3
"""Check Apple CryptoKit frame vectors and actual private-pipe durable receipts."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", default="node")
    parser.add_argument("--output", type=Path, default=Path("captures/runtime/push-native-source/interop"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sources = [root / "push/NativePushSourceFrame.swift", root / "scripts/native-push-source-fixture.swift"]
    binary = output / "fixture"
    build = subprocess.run(["/usr/bin/swiftc", *map(str, sources), "-o", str(binary)], capture_output=True, timeout=90)
    (output / "swift-build.log").write_bytes(build.stdout + build.stderr)
    build.check_returncode()
    vectors = subprocess.check_output([str(binary)], timeout=10)
    (output / "swift-frames.json").write_bytes(vectors)
    expected = (root / "push/test-support/native-source-frames.json").read_bytes()
    assert json.loads(vectors) == json.loads(expected), "CryptoKit vectors changed; inspect original bytes before updating"
    stream = subprocess.run([args.node, str(root / "scripts/native-push-source-stream-check.mjs"), str(binary)],
                            capture_output=True, timeout=40)
    (output / "stream-check.log").write_bytes(stream.stdout + stream.stderr)
    stream.check_returncode()
    receipt = {"kind": "synthetic-interop-passed", "sources": [
        {"path": str(p.relative_to(root)), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources],
        "vectorsSHA256": hashlib.sha256(vectors).hexdigest(),
        "scope": "Public fixture key; real CryptoKit, private child pipes, encrypted journal and receipts. No live Mac/Firebase/Pixel."}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(stream.stdout.decode().strip())


if __name__ == "__main__":
    main()
