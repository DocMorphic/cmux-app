#!/usr/bin/env python3
"""Verify a downloaded stable APK before installation; retain a local receipt.

Run from the source checkout for the artifact. This verifies APK contents, not
GitHub provenance, runtime behavior, account migration or permission to publish.
Requires Android build-tools 36.0.0 and a configured Java runtime.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

SIGNER = "1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4"


def verify_debug_fixture_exclusion(source, apk_manifest):
    application = ET.fromstring(source).find("application")
    if application is None:
        raise ValueError("Debug manifest has no application")
    names = []
    for component in application:
        if component.tag not in ("activity", "activity-alias", "provider", "service", "receiver"):
            continue
        name = component.get("{http://schemas.android.com/apk/res/android}name")
        if not name or name != name.strip() or "${" in name:
            raise ValueError("Debug component has an unresolved name")
        if name.startswith("."):
            name = "io.github.docmorphic.cmuxapp" + name
        elif "." not in name:
            name = "io.github.docmorphic.cmuxapp." + name
        names.append(name)
    if not names or len(set(names)) != len(names):
        raise ValueError("Debug component inventory is empty or duplicated")
    leaked = [name for name in names if name in apk_manifest]
    if leaked:
        raise ValueError("Debug fixture exclusion failed: " + ", ".join(leaked))
    return names


def verify_packaged_viewer_assets(assets_root, archive):
    checked = 0
    for directory in ("raw-code", "markdown-viewer", "docx-viewer", "workbook-viewer"):
        files = json.loads((assets_root / directory / "manifest.json").read_text(encoding="utf-8"))["files"]
        entries = files.items() if isinstance(files, dict) else ((x["asset"], x["sha256"]) for x in files)
        for name, expected in entries:
            if hashlib.sha256(archive.read(f"assets/{directory}/{name}")).hexdigest() != expected:
                raise ValueError(f"Packaged viewer asset mismatch: {directory}/{name}")
            checked += 1
    return checked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--version", type=int, required=True, help="Expected workflow run number")
    parser.add_argument("--output", type=Path, required=True, help="Local diagnostics and receipt directory")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    args = parser.parse_args()
    if args.sdk is None:
        parser.error("Set ANDROID_HOME or pass --sdk")
    if args.version <= 0:
        parser.error("Expected version must be positive")
    root = Path(__file__).resolve().parents[1]
    apk = args.apk.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    receipt_path = args.output / "apk-verification.json"
    # Never leave a receipt from an earlier success beside a failed recheck.
    receipt_path.unlink(missing_ok=True)
    sdk = args.sdk / "build-tools/36.0.0"

    def command(name, argv):
        result = subprocess.run([str(x) for x in argv], capture_output=True, text=True, timeout=120)
        (args.output / name).write_text(result.stdout + result.stderr, encoding="utf-8")
        if result.returncode:
            raise ValueError(f"Verification command failed; inspect {args.output / name}")
        return result.stdout

    def require(condition, message):
        if not condition:
            raise ValueError(message)

    suffix = ".exe" if os.name == "nt" else ""
    signature = command("signer.txt", [sdk / ("apksigner.bat" if os.name == "nt" else "apksigner"),
        "verify", "--verbose", "--print-certs", apk])
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: (\w+)$", signature, re.M)
    require(digests == [SIGNER], "Stable APK signing certificate changed")
    native = command("native-alignment.txt", [sys.executable, root / "scripts/verify-native-alignment.py", apk])
    require(native.count(": PASS (") == 19, "Expected nineteen verified native libraries")
    command("notice-engine.txt", [sys.executable, root / "scripts/verify-notice-engine.py", apk])
    command("zipalign.txt", [sdk / ("zipalign" + suffix), "-c", "-P", "16", "-v", "4", apk])
    badging = command("badging.txt", [sdk / ("aapt" + suffix), "dump", "badging", apk])
    require(f"package: name='io.github.docmorphic.cmuxapp' versionCode='{args.version}' versionName='0.2.0'" in badging,
        "APK package/version does not match the requested stable build")
    require("sdkVersion:'26'" in badging and "targetSdkVersion:'36'" in badging, "Unexpected SDK contract")
    manifest = command("manifest.txt", [sdk / ("aapt" + suffix), "dump", "xmltree", apk, "AndroidManifest.xml"])
    require(re.search(r"android:allowBackup[^\n]*=\(type 0x12\)0x0\b", manifest), "Backup must be disabled")
    require("io.github.docmorphic.cmuxapp.CmuxApplication" in manifest, "Diagnostics Application missing")
    require(not re.search(r"android:debuggable[^\n]*=\(type 0x12\)0xffffffff", manifest), "APK is debuggable")
    fixtures = verify_debug_fixture_exclusion((root / "app/src/debug/AndroidManifest.xml").read_text(), manifest)
    with zipfile.ZipFile(apk) as archive:
        checked = verify_packaged_viewer_assets(root / "app/src/main/assets", archive)
    require(checked == 17, "Expected seventeen pinned viewer assets")
    receipt = {"version": args.version, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "bytes": apk.stat().st_size, "signer": SIGNER, "viewer_assets": checked,
        "native_libraries": 19, "debug_fixture_components_excluded": len(fixtures)}
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
