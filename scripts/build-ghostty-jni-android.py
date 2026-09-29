#!/usr/bin/env python3
"""Link the Android JNI binding against an already verified Ghostty core build."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REVISION = "edefce7785c9f439966c68588db1edbd6b435203"
NDK_VERSION = "28.2.13676358"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    args = parser.parse_args()
    core, ndk = args.core.resolve(), args.ndk.resolve()
    receipt = json.loads((core / "manifest.json").read_text())
    if receipt["sourceRevision"] != REVISION or receipt["elfPageSize"] != 16384:
        raise SystemExit("Wrong Ghostty core checkpoint")
    if f"Pkg.Revision = {NDK_VERSION}" not in (ndk / "source.properties").read_text().splitlines():
        raise SystemExit("Wrong NDK version")
    for relative, expected in receipt["files"].items():
        artifact = (core / "android" / relative).resolve()
        if not artifact.is_relative_to(core / "android") or digest(artifact) != expected:
            raise SystemExit(f"Core artifact changed: {relative}")
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}[platform.system()]
    toolchain = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    source = ROOT / "ghostty/src/main/c/ghostty_jni.c"
    library = core / "jniLibs/arm64-v8a/libcmux_ghostty.so"
    library.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(toolchain / "aarch64-linux-android26-clang"), "-shared", "-fPIC", "-O2",
                    "-std=c11", "-Wall", "-Wextra", "-Werror", "-fvisibility=hidden",
                    "-I", str(core / "android/include"), str(source),
                    str(core / "android/lib/libghostty-vt.a"), "-lm", "-ldl",
                    "-Wl,--no-undefined", "-Wl,--exclude-libs,ALL", "-Wl,--gc-sections",
                    "-Wl,-z,relro,-z,now", "-Wl,-z,max-page-size=16384",
                    "-Wl,-z,common-page-size=16384", "-Wl,-soname,libcmux_ghostty.so",
                    "-o", str(library)], check=True)
    subprocess.run([str(toolchain / "llvm-strip"), "--strip-debug", str(library)], check=True)
    spec = importlib.util.spec_from_file_location("alignment", ROOT / "scripts/verify-native-alignment.py")
    alignment = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(alignment)
    print(alignment.verify(library.read_bytes(), library.name))
    notices = core / "notices/licenses/ghostty"
    notices.mkdir(parents=True, exist_ok=True)
    license_files = [core / "source/LICENSE"]
    # Preserve upstream and downloaded dependency notices, including dual licenses.
    for package in (core / "source/zig-pkg").iterdir():
        if package.is_dir():
            license_files.extend(p for p in package.iterdir()
                                 if p.is_file() and p.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")))
    for license_file in license_files:
        destination = notices / license_file.parent.name / license_file.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(license_file, destination)
    artifacts = [library, *(p for p in notices.rglob("*") if p.is_file())]
    manifest = {
        "sourceRevision": REVISION, "ndk": NDK_VERSION, "androidApi": 26,
        "abi": "arm64-v8a", "elfPageSize": 16384, "snapshotVersion": 1,
        "bindingSourceSha256": digest(source),
        "coreManifestSha256": digest(core / "manifest.json"),
        "files": {str(p.relative_to(core)): digest(p) for p in artifacts},
    }
    (core / "jni-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
