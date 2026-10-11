#!/usr/bin/env python3
"""Patch pinned Mac source, parse native hooks, and execute the actual payload mapper."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--node", default="node")
    parser.add_argument("--output", type=Path, default=Path("captures/runtime/push-native-hooks/check"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location("native_patch", root / "scripts/apply-native-push-patch.py")
    patch = importlib.util.module_from_spec(spec); spec.loader.exec_module(patch)
    def source(path):
        return subprocess.check_output(["git", "-C", str(args.upstream.resolve()), "show", f"{patch.REF}:{path}"])
    with tempfile.TemporaryDirectory(prefix="cmux-native-hook-check-") as temporary:
        target = Path(temporary)
        for path in patch.PINS:
            destination = target / path; destination.parent.mkdir(parents=True, exist_ok=True); destination.write_bytes(source(path))
        patched = patch.install(target)
        for path, data in patched.items():
            destination = output / "patched" / path; destination.parent.mkdir(parents=True, exist_ok=True); destination.write_bytes(data)
        swift = [str(target / path) for path in patched if path.endswith(".swift")]
        parse = subprocess.run(["/usr/bin/swiftc", "-frontend", "-parse", *swift], capture_output=True, timeout=30)
        (output / "swift-parse.log").write_bytes(parse.stdout + parse.stderr); parse.check_returncode()
        wiring = subprocess.run(["plutil", "-lint", str(target / "cmux.xcodeproj/project.pbxproj")], capture_output=True, timeout=10)
        (output / "project-parse.log").write_bytes(wiring.stdout + wiring.stderr); wiring.check_returncode()
        launch = subprocess.run(["/bin/bash", "-n", str(target / "scripts/reload.sh")], capture_output=True, timeout=10)
        (output / "launch-parse.log").write_bytes(launch.stdout + launch.stderr); launch.check_returncode()
    inputs = []
    payload_hashes = {}
    for name in ["PhonePushPayload.swift", "PhonePushPayloadKind.swift"]:
        path = output / name
        data = source("Packages/macOS/CmuxPhonePush/Sources/CmuxPhonePush/" + name)
        path.write_bytes(data); inputs.append(str(path)); payload_hashes[name] = hashlib.sha256(data).hexdigest()
    # Typecheck and execute the actual auth projection against inert storage,
    # without loading tokens, bootstrapping a user account or making requests.
    prefix = "Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/"
    tokens = source(prefix + "AuthCoordinator+Tokens.swift").decode()
    start = tokens.index("public struct AuthenticatedSessionIdentity:")
    end = tokens.index("public extension AuthCoordinator", start)
    identity_file = output / "AuthenticatedSessionIdentity.swift"
    identity_file.write_text("import Foundation\n" + tokens[start:end])
    scope_file = output / "AuthenticatedTeamScope.swift"
    scope_file.write_bytes(source(prefix + "AuthenticatedTeamScope.swift"))
    projection_file = output / "patched" / (prefix + "AuthCoordinator+TeamScopes.swift")
    auth_binary = output / "auth-fixture"
    auth_build = subprocess.run(["/usr/bin/swiftc", "-swift-version", "6", str(identity_file), str(scope_file),
        str(projection_file), str(root / "scripts/native-push-auth-scope-fixture.swift"), "-o", str(auth_binary)],
        capture_output=True, timeout=90)
    (output / "auth-build.log").write_bytes(auth_build.stdout + auth_build.stderr); auth_build.check_returncode()
    auth_result = subprocess.check_output([str(auth_binary)], timeout=10)
    (output / "auth-behavior.log").write_bytes(auth_result)
    binary = output / "fixture"
    build = subprocess.run(["/usr/bin/swiftc", "-swift-version", "6", *inputs,
        str(root / "push/mac-native/NativePushSourceMessages.swift"), str(root / "scripts/native-push-hooks-fixture.swift"),
        "-o", str(binary)], capture_output=True, timeout=90)
    (output / "swift-build.log").write_bytes(build.stdout + build.stderr); build.check_returncode()
    cases = subprocess.check_output([str(binary)], timeout=10); (output / "cases.json").write_bytes(cases)
    check = subprocess.run([args.node, str(root / "scripts/native-push-hooks-check.mjs"), str(output / "cases.json")],
        capture_output=True, timeout=30)
    (output / "behavior.log").write_bytes(check.stdout + check.stderr); check.check_returncode()
    queue_binary = output / "queue-fixture"
    queue_build = subprocess.run(["/usr/bin/swiftc", "-swift-version", "6",
        str(root / "push/NativePushSourceFrame.swift"), str(root / "push/mac-native/NativePushSourceDeliveryQueue.swift"),
        str(root / "scripts/native-push-delivery-queue-fixture.swift"), "-o", str(queue_binary)],
        capture_output=True, timeout=90)
    (output / "queue-build.log").write_bytes(queue_build.stdout + queue_build.stderr); queue_build.check_returncode()
    queue_cases = output / "queue-cases.json"
    queue_cases.write_bytes(subprocess.check_output([str(queue_binary)], timeout=10))
    queue_check = subprocess.run([args.node, str(root / "scripts/native-push-delivery-queue-check.mjs"),
        str(queue_cases), str(queue_binary)], capture_output=True, timeout=30)
    (output / "queue-behavior.log").write_bytes(queue_check.stdout + queue_check.stderr); queue_check.check_returncode()
    receipt = {"ref": patch.REF, "pinnedSources": patch.PINS, "payloadSources": payload_hashes,
        "patchedSources": {path: hashlib.sha256(data).hexdigest() for path, data in patched.items()},
        "kind": "native-mapping-and-patch-check-passed",
        "scope": "Edited Swift/project/tagged-launch syntax; actual upstream payload mapper/auth projection and native receipt queue compiled/executed; actual Swift queue/private-pipe journal interop. Not a whole app build, process supervisor or live account/provider."}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(auth_result.decode().strip())
    print(check.stdout.decode().strip())
    print(queue_check.stdout.decode().strip())


if __name__ == "__main__":
    main()
