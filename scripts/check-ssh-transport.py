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
    parser.add_argument("--shell-ui", action="store_true", help="Run three production Ghostty SSH shell-screen checks")
    parser.add_argument("--tmux-ui", action="store_true", help="Run the four real tmux workspace checks")
    parser.add_argument("--cmux-ui", action="store_true", help="Run six real mixed cmux-tui/tmux/shell workspace checks")
    parser.add_argument("--cmux-browser", action="store_true", help="Run two integrated real cmux-tui/Chrome browser workflow and reconnect checks")
    parser.add_argument("--ssh-browser", action="store_true", help="Run five real SSH browser stream, routing, retirement, presentation and mode checks")
    parser.add_argument("--files-ui", action="store_true", help="Run eight real SFTP transfer/browser/system-picker checks")
    parser.add_argument("--files-pickers", action="store_true", help="Run only the two real Android system-picker upload checks")
    parser.add_argument("--cmux-install", action="store_true", help="Run one live HTTPS/SFTP private installation check")
    parser.add_argument("--cmux-renderer", action="store_true", help="Run three cmux-tui provider/renderer component checks without an SSH fixture")
    parser.add_argument("--fixture", type=Path, help="Generated coordinates from ssh-tmux-fixture.py")
    args = parser.parse_args()
    if sum((args.ui, args.shell_ui, args.tmux_ui, args.cmux_ui, args.cmux_install, args.cmux_renderer, args.files_ui, args.files_pickers, args.ssh_browser, args.cmux_browser)) > 1:
        parser.error("Choose one UI suite")
    if (args.tmux_ui or args.cmux_ui or args.cmux_install or args.cmux_browser) and not args.fixture:
        parser.error("Real workspace checks require --fixture")
    count = 2 if args.cmux_browser else 5 if args.ssh_browser else 2 if args.files_pickers else 8 if args.files_ui else 1 if args.cmux_install else 6 if args.cmux_ui else 3 if args.cmux_renderer else (4 if args.tmux_ui else (3 if args.shell_ui else (4 if args.ui else 14)))
    test_class = "SshBrowserWorkspaceTest" if args.cmux_browser else "SshBrowserTest" if args.ssh_browser else "SshFilesScreenTest" if args.files_ui or args.files_pickers else "SshCmuxInstallTransportTest" if args.cmux_install else "SshWorkspacesScreenTest" if args.cmux_ui else "SshCmuxTerminalTest" if args.cmux_renderer else ("SshTmuxScreenTest" if args.tmux_ui else ("SshShellScreenTest" if args.shell_ui else ("SshComputersScreenTest" if args.ui else "SshTransportTest")))
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("This fixture runner refuses physical devices")
    root = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]

    def run(command, timeout=60):
        return subprocess.run(adb + command, capture_output=True, text=True, check=True, timeout=timeout).stdout.strip()

    if run(["shell", "getprop", "ro.kernel.qemu"]) != "1" or run(["shell", "getprop", "sys.boot_completed"]) != "1":
        parser.error("A booted emulator is required")
    fixture = None if args.cmux_renderer else json.loads((args.fixture or root / "ssh-spike/build/fixture-assets/fixture.json").read_text())
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
    port = f"tcp:{int(fixture['port'])}" if fixture else None
    run(["logcat", "-c"])
    if port:
        run(["reverse", port, port])
    try:
        selected = "io.github.docmorphic.cmuxapp." + test_class
        if args.files_pickers:
            selected = ",".join(selected + "#" + method for method in (
                "documentPickerCancelsReopensAndUploadsMultipleFilesWithoutReplacingExistingFile",
                "photoPickerCancelsReopensAndUploadsAnImageWhichPreviews"))
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", selected]
        # Only public fixture coordinates enter instrumentation arguments; the
        # server's generated private import examples remain in its build folder.
        public = {"port": fixture["port"], "user": fixture["username"],
                  "nonce": fixture["nonce"], "hostkey": fixture["hostKey"], "silentport": fixture["silentPort"]} if fixture else {}
        if args.ssh_browser or args.cmux_browser:
            public["browserport"] = fixture["browserPort"]
        # adb shell joins arguments through the device shell. Quote each value.
        import shlex
        for name, value in public.items():
            command += ["-e", "cmux_ssh_" + name, shlex.quote(str(value))]
        command += ["io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner"]
        try:
            result = run(command, timeout=240 if args.cmux_install or args.cmux_ui else 180)
        except subprocess.TimeoutExpired as failure:
            # adb's timeout does not stop instrumentation on the device. Retain
            # partial results and stop this emulator-only test before removing
            # its reverse tunnel; otherwise it can outlive the failed runner.
            output = failure.stdout or b""
            if isinstance(output, bytes):
                output = output.decode("utf-8", errors="replace")
            (args.output / "instrumentation.txt").write_text(output + "\nRUNNER TIMEOUT\n")
            run(["shell", "am", "force-stop", "io.github.docmorphic.cmuxapp.debug"])
            raise RuntimeError("SSH instrumentation timed out; partial results and logcat retained") from None
        (args.output / "instrumentation.txt").write_text(result + "\n")
        if not re.search(rf"^OK \({count} tests?\)$", result, re.M) or "FAILURES!!!" in result or f"numtests={count}" not in result:
            raise RuntimeError("Production SSH checks failed; inspect instrumentation.txt")
        if len(re.findall(r"^INSTRUMENTATION_STATUS_CODE: 0$", result, re.M)) != count:
            raise RuntimeError("A fixture check was skipped or did not finish successfully")
        print(f"{'cmux-tui renderer component' if args.cmux_renderer else 'Production SSH'} {test_class}: {count} tests passed on API {receipt['api']}")
    finally:
        # Preserve the test process's diagnostics even if instrumentation crashes
        # before reporting a JUnit result. This runner only admits emulators.
        diagnostics = subprocess.run(adb + ["logcat", "-d", "-s", "TestRunner", "AndroidRuntime", "DEBUG"],
                                     capture_output=True, text=True, timeout=30)
        (args.output / "logcat.txt").write_text(diagnostics.stdout + diagnostics.stderr)
        if port:
            run(["reverse", "--remove", port])


if __name__ == "__main__":
    main()
