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

SIGNER = "1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4"


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
    require(native.count(": PASS (") == 6, "Expected six verified native libraries")
    command("zipalign.txt", [sdk / ("zipalign" + suffix), "-c", "-P", "16", "-v", "4", apk])
    badging = command("badging.txt", [sdk / ("aapt" + suffix), "dump", "badging", apk])
    require(f"package: name='io.github.docmorphic.cmuxapp' versionCode='{args.version}' versionName='0.2.0'" in badging,
        "APK package/version does not match the requested stable build")
    require("sdkVersion:'26'" in badging and "targetSdkVersion:'36'" in badging, "Unexpected SDK contract")
    manifest = command("manifest.txt", [sdk / ("aapt" + suffix), "dump", "xmltree", apk, "AndroidManifest.xml"])
    require(re.search(r"android:allowBackup[^\n]*=\(type 0x12\)0x0\b", manifest), "Backup must be disabled")
    require("io.github.docmorphic.cmuxapp.CmuxApplication" in manifest, "Diagnostics Application missing")
    require(not re.search(r"android:debuggable[^\n]*=\(type 0x12\)0xffffffff", manifest), "APK is debuggable")
    fixtures = re.findall(r'android:name="\.([^"]+)"', (root / "app/src/debug/AndroidManifest.xml").read_text())
    require(len(fixtures) == 5 and all(name not in manifest for name in fixtures), "Debug fixture exclusion failed")
    checked = 0
    with zipfile.ZipFile(apk) as archive:
        for directory in ("raw-code", "markdown-viewer"):
            files = json.loads((root / "app/src/main/assets" / directory / "manifest.json").read_text())["files"]
            entries = files.items() if isinstance(files, dict) else ((x["asset"], x["sha256"]) for x in files)
            for name, expected in entries:
                require(hashlib.sha256(archive.read(f"assets/{directory}/{name}")).hexdigest() == expected,
                    f"Packaged viewer asset mismatch: {directory}/{name}")
                checked += 1
    require(checked == 14, "Expected fourteen pinned viewer assets")
    receipt = {"version": args.version, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "bytes": apk.stat().st_size, "signer": SIGNER, "viewer_assets": checked,
        "native_libraries": 6, "debug_fixture_activities_excluded": len(fixtures)}
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
