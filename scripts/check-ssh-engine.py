#!/usr/bin/env python3
"""Run the opt-in SSH engine experiment on an already booted emulator only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("This generated-server experiment is restricted to emulators.")
    root = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]

    def run(arguments, timeout=60):
        return subprocess.run(adb + arguments, check=True, capture_output=True,
                              text=True, timeout=timeout).stdout.strip()

    if run(["shell", "getprop", "ro.kernel.qemu"]) != "1":
        parser.error("Device does not report an emulator; refusing to install.")
    if run(["shell", "getprop", "sys.boot_completed"]) != "1":
        parser.error("Wait for the emulator to finish booting.")
    fixture = json.loads((root / "ssh-spike/build/fixture-assets/fixture.json").read_text())
    port = fixture["port"]
    if not isinstance(port, int) or not 1 <= port <= 65535:
        parser.error("Invalid fixture port")
    packages = [root / "ssh-spike/build/outputs/apk/debug/ssh-spike-debug.apk",
                root / "ssh-spike/build/outputs/apk/androidTest/debug/ssh-spike-debug-androidTest.apk"]
    args.output.mkdir(parents=True, exist_ok=True)
    try:
        page_size = run(["shell", "getconf", "PAGE_SIZE"])
        page_source = "getconf"
    except subprocess.CalledProcessError:
        # API 26 has no getconf binary. Read only the page-size fields from
        # the cat process's own mappings; do not save addresses or paths.
        sizes = set(re.findall(r"^KernelPageSize:\s+(\d+) kB$",
                               run(["shell", "cat", "/proc/self/smaps"]), re.M))
        if len(sizes) != 1:
            raise RuntimeError("Could not establish emulator kernel page size")
        page_size = str(int(sizes.pop()) * 1024)
        page_source = "proc/self/smaps KernelPageSize"
    receipt = {
        "serial": args.serial,
        "api": run(["shell", "getprop", "ro.build.version.sdk"]),
        "pageSize": page_size,
        "pageSizeSource": page_source,
        "sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in packages},
    }
    (args.output / "device.json").write_text(json.dumps(receipt, indent=2) + "\n")
    for package in packages:
        result = run(["install", "-r", str(package)], timeout=180)
        if "Success" not in result:
            raise RuntimeError("Test package installation failed: " + result)
    forwarding = f"tcp:{port}"
    run(["reverse", forwarding, forwarding])
    try:
        result = run(["shell", "am", "instrument", "-w", "-r",
                      "io.github.docmorphic.cmuxapp.sshspike.test/androidx.test.runner.AndroidJUnitRunner"],
                     timeout=180)
        (args.output / "instrumentation.txt").write_text(result + "\n")
        if not re.search(r"^OK \(5 tests\)$", result, re.M) or "FAILURES!!!" in result:
            raise RuntimeError("SSH runtime checks failed; inspect instrumentation.txt")
        print(f"SSH engine: 5 tests passed on API {receipt['api']}, {receipt['pageSize']}-byte pages")
    finally:
        run(["reverse", "--remove", forwarding])


if __name__ == "__main__":
    main()
