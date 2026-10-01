#!/usr/bin/env python3
"""Run production SSH transport against scripts/ssh-engine-fixture.py, emulator only."""
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
    parser.add_argument("--ui", action="store_true", help="Run the four production SSH host-screen checks")
    args = parser.parse_args()
    count = 4 if args.ui else 13
    test_class = "SshComputersScreenTest" if args.ui else "SshTransportTest"
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("This fixture runner refuses physical devices")
    root = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]

    def run(command, timeout=60):
        return subprocess.run(adb + command, capture_output=True, text=True, check=True, timeout=timeout).stdout.strip()

    if run(["shell", "getprop", "ro.kernel.qemu"]) != "1" or run(["shell", "getprop", "sys.boot_completed"]) != "1":
        parser.error("A booted emulator is required")
    fixture = json.loads((root / "ssh-spike/build/fixture-assets/fixture.json").read_text())
    packages = [root / "app/build/outputs/apk/debug/app-debug.apk",
                root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    args.output.mkdir(parents=True, exist_ok=True)
    receipt = {"api": run(["shell", "getprop", "ro.build.version.sdk"]),
               "sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in packages}}
    try:
        receipt["pageSize"] = run(["shell", "getconf", "PAGE_SIZE"])
    except subprocess.CalledProcessError:
        sizes = set(re.findall(r"^KernelPageSize:\s+(\d+) kB$", run(["shell", "cat", "/proc/self/smaps"]), re.M))
        if len(sizes) != 1:
            raise RuntimeError("Could not establish kernel page size")
        receipt["pageSize"] = str(int(sizes.pop()) * 1024)
    (args.output / "device.json").write_text(json.dumps(receipt, indent=2) + "\n")
    for package in packages:
        assert "Success" in run(["install", "-r", str(package)], timeout=180)
    port = f"tcp:{int(fixture['port'])}"
    run(["logcat", "-c"])
    run(["reverse", port, port])
    try:
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", "io.github.docmorphic.cmuxapp." + test_class]
        # Only public fixture coordinates enter instrumentation arguments; the
        # server's generated private import examples remain in its build folder.
        public = {"port": fixture["port"], "user": fixture["username"],
                  "nonce": fixture["nonce"], "hostkey": fixture["hostKey"], "silentport": fixture["silentPort"]}
        # adb shell joins arguments through the device shell. Quote each value.
        import shlex
        for name, value in public.items():
            command += ["-e", "cmux_ssh_" + name, shlex.quote(str(value))]
        command += ["io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner"]
        result = run(command, timeout=180)
        (args.output / "instrumentation.txt").write_text(result + "\n")
        if not re.search(rf"^OK \({count} tests\)$", result, re.M) or "FAILURES!!!" in result or f"numtests={count}" not in result:
            raise RuntimeError("Production SSH checks failed; inspect instrumentation.txt")
        if len(re.findall(r"^INSTRUMENTATION_STATUS_CODE: 0$", result, re.M)) != count:
            raise RuntimeError("A fixture check was skipped or did not finish successfully")
        print(f"Production SSH {test_class}: {count} tests passed on API {receipt['api']}")
    finally:
        # Preserve the test process's diagnostics even if instrumentation crashes
        # before reporting a JUnit result. This runner only admits emulators.
        diagnostics = subprocess.run(adb + ["logcat", "-d", "-s", "TestRunner", "AndroidRuntime", "DEBUG"],
                                     capture_output=True, text=True, timeout=30)
        (args.output / "logcat.txt").write_text(diagnostics.stdout + diagnostics.stderr)
        run(["reverse", "--remove", port])


if __name__ == "__main__":
    main()
