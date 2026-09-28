#!/usr/bin/env python3
"""Run the four interrupted handoff cases on an explicitly selected Android device."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "io.github.docmorphic.cmuxapp.debug"
CASES = [
    "NativeArtifactSyntaxTest#nativeLateColoringPreservesSelectionSearchFontViewportAndClipboard",
    "NativeArtifactTextTest#searchWrapsAndJumpsToMatchesAndLineControlsMoveActualViewport",
    "NativeArtifactTextTest#selectionAndCopyContentsPreserveNewlinesUnicodeAndExcludeLineNumbers",
    "NativeMarkdownPreviewTest#sharedRendererDisplaysTablesCodeMermaidAndVegaAndSwitchesToRaw",
]
PNG = b"\x89PNG\r\n\x1a\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit adb device serial")
    parser.add_argument("--adb", default="adb", help="adb executable path")
    parser.add_argument("--install", action="store_true", help="Install the already-built debug and test APKs")
    args = parser.parse_args()
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = ROOT / "captures" / "handoff" / stamp
    output.mkdir(parents=True)

    def adb(*command, binary=False, timeout=60):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

    def save_png(name, data):
        if not data.startswith(PNG):
            raise RuntimeError(f"{name} is not a PNG; refusing to record it as screenshot evidence")
        (output / name).write_bytes(data)

    if adb("get-state").strip() != "device":
        raise RuntimeError("Selected device is not ready/authorized")
    if args.install:
        for apk in ["app/build/outputs/apk/debug/app-debug.apk",
                    "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
            result = adb("install", "-r", str(ROOT / apk), timeout=180)
            if "Success" not in result:
                raise RuntimeError(result)
    metadata = {
        "source": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "working_tree": subprocess.check_output(["git", "status", "--short"], cwd=ROOT, text=True),
        "serial": args.serial,
        "model": adb("shell", "getprop", "ro.product.model").strip(),
        "android": adb("shell", "getprop", "ro.build.version.release").strip(),
        "api": adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "fingerprint": adb("shell", "getprop", "ro.build.fingerprint").strip(),
        "selectors": CASES,
        "scope": "Android fixtures only; no physical Mac or Windows host acceptance",
    }
    metadata["local_apks"] = {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in (ROOT / "app/build/outputs/apk").glob("**/*.apk")
        if "/debug/" in path.as_posix()
    }
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/io.github.docmorphic.cmuxapp.MainActivity")
    windows = adb("shell", "dumpsys", "window", "windows")
    (output / "preflight-windows.txt").write_text(windows, encoding="utf-8")
    save_png("preflight.png", adb("exec-out", "screencap", "-p", binary=True))
    focus = next((line for line in windows.splitlines() if "mCurrentFocus=" in line), "")
    if PACKAGE not in focus or "Application Not Responding" in focus:
        raise RuntimeError(f"App does not have foreground focus. Unlock/dismiss system dialogs first. {focus}")
    print(f"Evidence: {output}", flush=True)
    selectors = ",".join("io.github.docmorphic.cmuxapp." + case for case in CASES)
    try:
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", selectors,
                     f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner", timeout=600)
    except subprocess.TimeoutExpired as error:
        result = (error.stdout or b"").decode("utf-8", errors="replace") + "\nHOST RUNNER TIMEOUT; NOT A PASS\n"
    (output / "instrumentation.txt").write_text(result, encoding="utf-8")
    metadata["passed"] = bool(re.search(r"\bOK \(4 tests\)", result)) and not any(
        marker in result for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "HOST RUNNER TIMEOUT"))
    (output / "result.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    save_png("final.png", adb("exec-out", "screencap", "-p", binary=True))
    print(result, flush=True)
    if not metadata["passed"]:
        return 1
    for name in ("artifact-syntax.png", "artifact-text-controls.png", "markdown-rendered.png"):
        save_png(name, adb("exec-out", "run-as", PACKAGE, "cat", "files/" + name, binary=True))
    print("Four runtime cases passed. Inspect the captured PNGs before recording visual acceptance.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
