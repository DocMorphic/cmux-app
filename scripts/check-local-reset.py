#!/usr/bin/env python3
"""Verify the real local-reset UI on an explicitly selected disposable emulator.

Erases the debug app's emulator data. Never run against a physical/signed-in device.
Build/install debug and androidTest APKs before running this command.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.docmorphic.cmuxapp.debug"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()

    def adb(*command, timeout=120):
        return subprocess.check_output([args.adb, "-s", args.serial, *command], timeout=timeout, text=True)

    if not args.serial.startswith("emulator-") or adb("shell", "getprop", "ro.hardware").strip() != "ranchu":
        raise RuntimeError("Refusing to erase data outside the disposable emulator")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    evidence = ROOT / "captures" / "local-reset" / stamp
    evidence.mkdir(parents=True)
    receipt = {"passed": False, "serial": args.serial, "fingerprint": adb("shell", "getprop", "ro.build.fingerprint").strip(),
               "source": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
               "worktree_status": subprocess.check_output(["git", "status", "--short"], cwd=ROOT, text=True),
               "apk_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in (ROOT / "app/build/outputs/apk").glob("**/*.apk") if "/debug/" in p.as_posix()}}
    (evidence / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    for phase, method in [("erase", "eraseSeededDataThroughRealConfirmation"), ("verify", "freshProcessHasNoSeededDataOrKeys")]:
        output = adb("shell", "am", "instrument", "-w", "-r", "-e", "cmuxResetPhase", phase,
                     "-e", "class", f"io.github.docmorphic.cmuxapp.NativeLocalResetPlatformTest#{method}",
                     f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner")
        (evidence / f"{phase}.txt").write_text(output)
        if phase == "erase":
            if "cmuxResetCheckpoint=seeded-and-confirmation-visible" not in output:
                raise RuntimeError(f"Reset never reached the seeded confirmation; inspect {evidence}")
            if "Android did not terminate the reset process" in output:
                raise RuntimeError(f"Reset did not terminate the app; inspect {evidence}")
        elif "OK (1 test)" not in output or any(x in output for x in ("FAILURES!!!", "INSTRUMENTATION_FAILED")):
            raise RuntimeError(f"Fresh-process reset verification failed; inspect {evidence}")
    receipt["passed"] = True
    (evidence / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Real reset confirmed: account, key, files, settings and permission removed. Evidence: {evidence}")


if __name__ == "__main__":
    main()
