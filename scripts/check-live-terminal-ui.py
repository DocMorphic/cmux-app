#!/usr/bin/env python3
"""Exercise terminal sizing, composer, and optional Gboard input on an unlocked physical Pixel.

Preserves account data and existing workspaces. Installs only the built test APK.
An interrupted test leaves an ownership receipt: inspect it before any retry.
"""
import argparse
import datetime
import os
from pathlib import Path
import re
import shutil
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--build", choices=("stable", "nightly"), required=True)
    parser.add_argument("--gboard", action="store_true", help="Type through actual Gboard keys")
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"))
    parser.add_argument("--output", type=Path, default=root / "captures/runtime/live-terminal-ui" /
                        datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    apk = root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if not apk.is_file():
        parser.error("Build :app:assembleDebugAndroidTest first")
    policy = subprocess.check_output(adb + ["shell", "dumpsys", "window", "policy"], text=True, timeout=15)
    if re.findall(r"^\s*showing=(true|false)\s*$", policy, re.M) != ["false"]:
        parser.error("Unlock the physical Pixel; no test was started")
    receipt = subprocess.run(adb + ["shell", "run-as", "io.github.docmorphic.cmuxapp.debug", "test", "!", "-e",
        "files/live-ui-fixture.json"], timeout=15)
    if receipt.returncode:
        parser.error("Inspect the previous private terminal fixture receipt before retrying")
    original = subprocess.check_output(adb + ["shell", "settings", "get", "global", "stay_on_while_plugged_in"], text=True, timeout=15).strip()
    if original not in {str(i) for i in range(8)}:
        parser.error("Cannot safely preserve the phone's current sleep setting")
    args.output.mkdir(parents=True, exist_ok=False)
    try:
        subprocess.run(adb + ["shell", "settings", "put", "global", "stay_on_while_plugged_in", "7"], check=True, timeout=15)
        with (args.output / "install.txt").open("w") as log:
            subprocess.run(adb + ["install", "-r", str(apk)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=90)
        with (args.output / "runtime.txt").open("w") as log:
            subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                "io.github.docmorphic.cmuxapp.LiveNativeUiCheck", "-e", "cmux_live_ui_fixture", "true", "-e", "cmux_live_build", args.build,
                "-e", "cmux_live_gboard", str(args.gboard).lower(),
                "io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner"],
                stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        result = (args.output / "runtime.txt").read_text()
        if "OK (1 test)" not in result or "FAILURES!!!" in result:
            raise RuntimeError("Terminal UI check did not pass; inspect runtime and the private receipt before retrying")
        subprocess.run(adb + ["shell", "run-as", "io.github.docmorphic.cmuxapp.debug", "test", "!", "-e",
            "files/live-ui-fixture.json"], check=True, timeout=15)
        subprocess.run(adb + ["pull", "/sdcard/Android/data/io.github.docmorphic.cmuxapp.debug/files/live-ui-check/",
            str(args.output / "screenshots")], check=True, timeout=30)
        print("Terminal UI check passed; inspect screenshots before claiming visual acceptance.")
    finally:
        subprocess.run(adb + ["shell", "settings", "put", "global", "stay_on_while_plugged_in", original], check=True, timeout=15)
        restored = subprocess.check_output(adb + ["shell", "settings", "get", "global", "stay_on_while_plugged_in"], text=True, timeout=15).strip()
        (args.output / "sleep-restoration.txt").write_text(f"original={original}\nrestored={restored}\n")
        if restored != original:
            raise RuntimeError("Restore of the original phone sleep setting was not verified")


if __name__ == "__main__":
    main()
