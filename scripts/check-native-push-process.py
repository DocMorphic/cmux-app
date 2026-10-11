#!/usr/bin/env python3
"""Compile and run the native supervisor with isolated private fixture children."""
import argparse
import os
from pathlib import Path
import signal
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", required=True)
    parser.add_argument("--output", type=Path, default=Path("captures/runtime/push-native-process"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    binary = output / "process-fixture"
    sources = ["push/NativePushSourceFrame.swift", "push/mac-native/NativePushSourceSink.swift",
        "push/mac-native/NativePushSourceDeliveryQueue.swift", "push/mac-native/NativePushSourceProcessSink.swift",
        "scripts/native-push-process-fixture.swift"]
    build = subprocess.run(["/usr/bin/swiftc", "-swift-version", "6", *[str(root / path) for path in sources],
        "-o", str(binary)], capture_output=True, timeout=90)
    (output / "build.log").write_bytes(build.stdout + build.stderr); build.check_returncode()
    with tempfile.TemporaryDirectory(prefix="cmux-native-process-check-") as temporary:
        directory = Path(temporary)
        child = subprocess.Popen([str(binary), args.node, str(root / "scripts/native-push-process-child.mjs"),
            str(directory), str(directory / "marker.jsonl")], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            start_new_session=True)
        try:
            result = child.communicate(timeout=25)[0]
            (output / "behavior.log").write_bytes(result)
            print(result.decode().strip())
        finally:
            # Includes a failed fixture's own orphaned helpers; never targets the
            # user's cmux app, another test group or unrelated Node processes.
            try: os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            child.wait()
        if child.returncode:
            raise subprocess.CalledProcessError(child.returncode, "native supervisor fixture")


if __name__ == "__main__":
    main()
