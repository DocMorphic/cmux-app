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

# App adapters, pinned Iroh/Ghostty/Cloud/video engines and packaged dependencies.
# Check identities as well as alignment: a same-size substitution must not pass.
THIRD_PARTY = Path(__file__).resolve().parents[1] / "third_party"
EXPECTED_NATIVE_LIBRARIES = frozenset("lib/arm64-v8a/" + name for name in
    json.loads((THIRD_PARTY / "android-native-modules.json").read_text()) +
    json.loads((THIRD_PARTY / "geckoview/manifest.json").read_text())["arm64_libraries"])


def verify_native_inventory(archive):
    entries = [name for name in archive.namelist() if name.endswith(".so")]
    actual = set(entries)
    if len(actual) != len(entries) or actual != EXPECTED_NATIVE_LIBRARIES:
        raise ValueError("Native library inventory mismatch: missing=" +
                         repr(sorted(EXPECTED_NATIVE_LIBRARIES - actual)) + " unexpected=" +
                         repr(sorted(actual - EXPECTED_NATIVE_LIBRARIES)) +
                         (" duplicate ZIP entries" if len(actual) != len(entries) else ""))
    return len(actual)


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
    for directory in ("raw-code", "markdown-viewer", "docx-viewer", "workbook-viewer", "presentation-viewer", "rtf-viewer"):
        files = json.loads((assets_root / directory / "manifest.json").read_text(encoding="utf-8"))["files"]
        entries = list(files.items() if isinstance(files, dict) else ((x["asset"], x["sha256"]) for x in files))
        if not entries or len({name for name, _ in entries}) != len(entries):
            raise ValueError(f"Empty or duplicated viewer asset inventory: {directory}")
        for name, expected in entries:
            if hashlib.sha256(archive.read(f"assets/{directory}/{name}")).hexdigest() != expected:
                raise ValueError(f"Packaged viewer asset mismatch: {directory}/{name}")
            checked += 1
    return checked


def verify_firebase_resources(configuration, resource_dump):
    """Compare the compiled release resources with the selected client config.

    Client API keys are checked in memory and never included in diagnostics.
    Configuration is explicit; no provider key or gcloud state is discovered.
    """
    config = json.loads(configuration.read_text(encoding="utf-8"))
    if not isinstance(config, dict) or "private_key" in config:
        raise ValueError("Expected Android Firebase client configuration")
    package = "io.github.docmorphic.cmuxapp"
    clients = [c for c in config.get("client", []) if
        c.get("client_info", {}).get("android_client_info", {}).get("package_name") == package]
    if len(clients) != 1:
        raise ValueError("Expected exactly one Firebase release client")
    client = clients[0]
    keys = [k.get("current_key") for k in client.get("api_key", []) if k.get("current_key")]
    expected = {"google_app_id": client.get("client_info", {}).get("mobilesdk_app_id"),
        "gcm_defaultSenderId": config.get("project_info", {}).get("project_number"),
        "project_id": config.get("project_info", {}).get("project_id"),
        "google_api_key": keys[0] if keys else None}
    if any(not isinstance(value, str) or not value for value in expected.values()):
        raise ValueError("Incomplete Firebase release client configuration")
    headers = list(re.finditer(r"^\s*resource 0x[0-9a-fA-F]+ (\S+)[^\n]*$", resource_dump, re.M))
    found = {}
    for i, header in enumerate(headers):
        name = header.group(1).removeprefix("string/")
        if header.group(1) != "string/" + name or name not in expected:
            continue
        block = resource_dump[header.end():headers[i + 1].start() if i + 1 < len(headers) else len(resource_dump)]
        values = re.findall(r"^\s*\([^\n)]*\)\s+(.*)$", block, re.M)
        default = re.findall(r"^\s*\(\)\s+(.*)$", block, re.M)
        if name in found or len(values) != 1 or len(default) != 1:
            raise ValueError("Ambiguous compiled Firebase resource: " + name)
        try:
            found[name] = json.loads(default[0])
        except (TypeError, ValueError):
            raise ValueError("Invalid compiled Firebase resource: " + name) from None
    for name, value in expected.items():
        if found.get(name) != value:
            raise ValueError("Compiled Firebase resource mismatch: " + name)
    return {"project": expected["project_id"], "sender": expected["gcm_defaultSenderId"],
        "application": expected["google_app_id"], "package": package, "api_key_matches": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--version", type=int, required=True, help="Expected workflow run number")
    parser.add_argument("--output", type=Path, required=True, help="Local diagnostics and receipt directory")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--firebase-config", type=Path, default=os.environ.get("CMUX_FIREBASE_CONFIG"),
        help="Optional selected Google client config; verify compiled Firebase resources")
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
    with zipfile.ZipFile(apk) as archive:
        native_count = verify_native_inventory(archive)
    native = command("native-alignment.txt", [sys.executable, root / "scripts/verify-native-alignment.py", apk])
    require(native.count(": PASS (") == native_count, "Native alignment verification coverage is incomplete")
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
    receipt = {"version": args.version, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "bytes": apk.stat().st_size, "signer": SIGNER, "viewer_assets": checked,
        "native_libraries": native_count, "debug_fixture_components_excluded": len(fixtures)}
    if args.firebase_config is not None:
        resources = subprocess.run([str(sdk / ("aapt2" + suffix)), "dump", "resources", str(apk)],
            capture_output=True, text=True, timeout=120)
        if resources.returncode:
            raise ValueError("Cannot read compiled Firebase resources")
        receipt["firebase"] = verify_firebase_resources(args.firebase_config, resources.stdout)
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
