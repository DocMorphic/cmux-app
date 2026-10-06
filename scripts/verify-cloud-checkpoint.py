#!/usr/bin/env python3
"""Validate a Cloud checkpoint against current checked-in adapters before packaging."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "builderSha256": "scripts/build-cloud-terminal-android.py",
    "ghosttyBuildAdapterSha256": "scripts/native/cloud-ghostty-build.rs",
    "jniSourceSha256": "app/src/main/c/cloud_terminal_jni.c",
    "androidRuntimeSha256": "scripts/native/cloud-android-runtime.rs",
    "androidTlsSha256": "scripts/native/cloud-android-tls.rs",
    "noticeCollectorSha256": "scripts/collect-cloud-notices.py",
    "noticeSourcesSha256": "third_party/cloud-notices/sources.json",
}
REQUIRED = {"jniLibs/arm64-v8a/libcmux_terminal_client.so", "jniLibs/arm64-v8a/libcmux_cloud_jni.so",
            "rustls-platform-verifier-0.1.1.aar", "notices/licenses/CloudTerminal.txt",
            "notices/licenses/cloud-terminal/inventory.json", "Cargo.lock", "dependencies.json"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(root, require_notices=False):
    root = root.resolve()
    manifest = json.loads((root / "manifest.json").read_text())
    expected = {"sourceRevision": "c2715faa02c260b07012bc0b386597cfb333021d",
                "ghosttyRevision": "324c0273815ddc383d8d2fbf8d59d590cb65dfdb",
                "ndk": "28.2.13676358", "androidApi": 26, "abi": "arm64-v8a", "elfPageSize": 16384}
    if any(manifest.get(key) != value for key, value in expected.items()):
        raise ValueError("Cloud checkpoint toolchain/source mismatch")
    for key, source in SOURCES.items():
        if manifest.get(key) != digest(ROOT / source):
            raise ValueError(f"Rebuild Cloud checkpoint after changing {source}")
    files = manifest["files"]
    if not REQUIRED <= files.keys():
        raise ValueError("Cloud checkpoint is incomplete")
    for relative, expected_hash in files.items():
        path = (root / relative).resolve()
        if not path.is_relative_to(root) or not path.is_file() or digest(path) != expected_hash:
            raise ValueError(f"Cloud artifact hash/path mismatch: {relative}")
    # Do not let an unverified extra library or AAR enter Gradle's directory source set.
    for path in [*(root / "jniLibs").rglob("*"), *root.glob("*.aar")]:
        if path.is_file() and str(path.relative_to(root)) not in files:
            raise ValueError(f"Unexpected Cloud binary: {path.name}")
    inventory = root / "notices/licenses/cloud-terminal/inventory.json"
    report = json.loads(inventory.read_text())
    if digest(inventory) != manifest["notices"]["inventorySha256"]:
        raise ValueError("Cloud notice inventory mismatch")
    if require_notices and report["missingLicenseTexts"]:
        raise ValueError("Cloud dependency license texts missing: " + ", ".join(report["missingLicenseTexts"]))
    spec = importlib.util.spec_from_file_location("alignment", ROOT / "scripts/verify-native-alignment.py")
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    for relative in sorted(REQUIRED):
        if relative.endswith(".so"):
            print(module.verify((root / relative).read_bytes(), relative))
    print(f"Verified Cloud checkpoint: {len(files)} artifacts, {len(report['components'])} notice components")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--require-notices", action="store_true")
    args = parser.parse_args()
    verify(args.root, args.require_notices)
