#!/usr/bin/env python3
"""Cross-check push crypto with the pinned upstream Swift implementation.

Only fixed, public test key material is used. No Keychain operations, accounts,
network services or production app state are accessed. Requires macOS CryptoKit.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

REF = "204a11dfcc76280205e50406ab94270a1c152155"
SOURCE = "Packages/macOS/CmuxPhonePush/Sources/CmuxPhonePush/PhonePushCrypto.swift"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("captures/runtime/push-crypto"))
    parser.add_argument("--write-vectors", type=Path)
    parser.add_argument("--verify-android", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    source = subprocess.check_output(["git", "-C", str(args.upstream.resolve()), "show", f"{REF}:{SOURCE}"])
    (output / "upstream-PhonePushCrypto.swift").write_bytes(source)
    # Exclude unrelated Keychain/store classes and their package dependencies.
    # Crypto and canonical serialization above this boundary remain unmodified.
    marker = b"/// Separate application key material."
    assert source.count(marker) == 1
    (output / "PhonePushCrypto.swift").write_bytes(source.split(marker)[0])
    binary = output / "fixture"
    compiled = subprocess.run(["/usr/bin/swiftc", str(output / "PhonePushCrypto.swift"),
        str(root / "scripts/phone-push-crypto-fixture.swift"), "-o", str(binary)], capture_output=True, text=True)
    (output / "swift-build.txt").write_text(compiled.stdout + compiled.stderr)
    compiled.check_returncode()
    vectors = subprocess.check_output([str(binary)])
    assert len(json.loads(vectors)) == 2
    (output / "apple-hpke-v2.json").write_bytes(vectors)
    if args.write_vectors:
        args.write_vectors.parent.mkdir(parents=True, exist_ok=True)
        args.write_vectors.write_bytes(vectors)
    if args.verify_android:
        result = subprocess.check_output([str(binary), "--verify", str(args.verify_android.resolve())], text=True)
        (output / "apple-open-android.txt").write_text(result)
        print(result.strip())
    (output / "source.json").write_text(json.dumps({"ref": REF, "path": SOURCE,
        "sha256": hashlib.sha256(source).hexdigest(), "fixture": "fixed public test keys only"}, indent=2))
    print("Two Apple CryptoKit envelopes generated from pinned cmux crypto source")

if __name__ == "__main__":
    main()
