#!/usr/bin/env python3
"""Generate real upstream notify/dismiss payloads encrypted with public fixture keys."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

REF = "204a11dfcc76280205e50406ab94270a1c152155"
PREFIX = "Packages/macOS/CmuxPhonePush/Sources/CmuxPhonePush/"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("captures/runtime/push-messages/interop"))
    parser.add_argument("--write-vectors", type=Path)
    args = parser.parse_args()
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parents[1]
    inputs = []; metadata = []
    for name in ["PhonePushCrypto.swift", "PhonePushPayload.swift", "PhonePushPayloadKind.swift", "PhonePushRequestEnvelope.swift"]:
        source = subprocess.check_output(["git", "-C", str(args.upstream.resolve()), "show", f"{REF}:{PREFIX}{name}"])
        (output / ("upstream-" + name)).write_bytes(source)
        metadata.append({"path": PREFIX + name, "sha256": hashlib.sha256(source).hexdigest()})
        if name == "PhonePushCrypto.swift":
            marker = b"/// Separate application key material."
            assert source.count(marker) == 1
            source = source.split(marker)[0]
        if name == "PhonePushRequestEnvelope.swift":
            # Only the import is removed; the fixture supplies an inert auth
            # snapshot type for an unused method. The payload encoder is unchanged.
            marker = b"public import CmuxAuthRuntime\n"
            assert source.count(marker) == 1
            source = source.replace(marker, b"")
        path = output / name; path.write_bytes(source); inputs.append(str(path))
    binary = output / "fixture"
    build = subprocess.run(["/usr/bin/swiftc", *inputs, str(root / "scripts/phone-push-message-fixture.swift"),
                            "-o", str(binary)], capture_output=True, text=True)
    (output / "swift-build.txt").write_text(build.stdout + build.stderr); build.check_returncode()
    vectors = subprocess.check_output([str(binary)])
    assert [v["kind"] for v in json.loads(vectors)] == ["notify", "dismiss"]
    (output / "apple-push-messages.json").write_bytes(vectors)
    if args.write_vectors:
        args.write_vectors.parent.mkdir(parents=True, exist_ok=True); args.write_vectors.write_bytes(vectors)
    (output / "sources.json").write_text(json.dumps({"ref": REF, "sources": metadata,
        "fixture": "Fixed public test keys; no Keychain, account, or network access"}, indent=2))
    print("Pinned Mac encoder and Apple CryptoKit generated notify and dismiss fixtures")

if __name__ == "__main__":
    main()
