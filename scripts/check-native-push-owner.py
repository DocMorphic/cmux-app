#!/usr/bin/env python3
"""Compile the actual native owner and run its private production-helper fixture."""
import argparse
import os
from pathlib import Path
import signal
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", required=True)
    parser.add_argument("--output", type=Path, default=Path("captures/runtime/push-native-owner/production"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    binary = output / "owner-fixture"
    sources = ["push/NativePushSourceFrame.swift", "push/mac-native/NativePushSourceSink.swift",
        "push/mac-native/NativePushSourceDeliveryQueue.swift", "push/mac-native/NativePushSourceProcessSink.swift",
        "push/mac-native/NativePushOwnerDescriptorRepository.swift", "push/mac-native/NativePushSourceOwner.swift",
        "push/mac-native/NativePushCaptureRepository.swift", "push/mac-native/NativePushSourceCaptureController.swift",
        "scripts/native-push-owner-fixture.swift"]
    build = subprocess.run(["/usr/bin/swiftc", "-swift-version", "6", *[str(root / p) for p in sources], "-o", str(binary)], capture_output=True, timeout=90)
    (output / "build.log").write_bytes(build.stdout + build.stderr); build.check_returncode()
    with tempfile.TemporaryDirectory(prefix="cmux-native-owner-", dir="/tmp") as temporary:
        prepared = subprocess.run([args.node, str(root / "scripts/native-push-owner-fixture.mjs"), "prepare", temporary], capture_output=True, timeout=20)
        (output / "prepare.log").write_bytes(prepared.stdout + prepared.stderr); prepared.check_returncode()
        child = subprocess.Popen([str(binary), args.node, str(root / "scripts/cmux-push-helper.mjs"),
            str(root / "scripts/native-push-owner-fixture.mjs"), temporary], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            result = child.communicate(timeout=30)[0]
            (output / "behavior.log").write_bytes(result); print(result.decode().strip())
        finally:
            try: os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            child.wait()
        if child.returncode:
            raise subprocess.CalledProcessError(child.returncode, "native owner production fixture")


if __name__ == "__main__":
    main()
