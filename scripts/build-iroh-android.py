#!/usr/bin/env python3
"""Build cmux's pinned Iroh FFI for Pixel arm64, with fresh matching Kotlin bindings."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

PIN = "ee19f156667ca640b912108a45f8b5bb8d156fec"
NDK_VERSION = "28.2.13676358"
TARGET = "aarch64-linux-android"
API = 26


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Clean pinned manaflow-ai/iroh-ffi checkout")
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New output directory")
    args = parser.parse_args()
    source, ndk, output = (p.resolve() for p in (args.source, args.ndk, args.output))
    host = {"Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}.get(platform.system())
    if host is None:
        raise SystemExit("Run native compilation on Linux/macOS or use the GitHub checkpoint.")

    def run(*command, env=None, capture=False):
        return subprocess.run(command, cwd=source, env=env, check=True, text=True,
                              stdout=subprocess.PIPE if capture else None).stdout

    assert run("git", "rev-parse", "HEAD", capture=True).strip() == PIN, "Wrong Iroh source revision"
    assert not run("git", "status", "--porcelain", capture=True).strip(), "Iroh source has local changes"
    properties = (ndk / "source.properties").read_text()
    assert any(line.strip() == "Pkg.Revision = " + NDK_VERSION for line in properties.splitlines()), "Wrong NDK version"
    toolchain = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    compiler = toolchain / f"{TARGET}{API}-clang"
    assert compiler.is_file(), "Android compiler missing"
    output.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env.update({
        "ANDROID_NDK_HOME": str(ndk),
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER": str(compiler),
        "CC_aarch64_linux_android": str(compiler),
        "CXX_aarch64_linux_android": str(toolchain / f"{TARGET}{API}-clang++"),
        "AR_aarch64_linux_android": str(toolchain / "llvm-ar"),
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS":
            "-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384",
    })
    run("cargo", "build", "--locked", "--release", "--lib", "--target", TARGET, "--jobs", "2", env=env)
    metadata = json.loads(run("cargo", "metadata", "--locked", "--format-version", "1", "--no-deps", capture=True))
    library = Path(metadata["target_directory"]) / TARGET / "release/libiroh_ffi.so"
    native = output / "jniLibs/arm64-v8a/libiroh_ffi.so"
    native.parent.mkdir(parents=True)
    shutil.copy2(library, native)
    elf_header = run(str(toolchain / "llvm-readelf"), "-h", str(native), capture=True)
    assert "AArch64" in elf_header, "Wrong native architecture"
    segments = run(str(toolchain / "llvm-readelf"), "-lW", str(native), capture=True)
    loads = [line.split() for line in segments.splitlines() if line.strip().startswith("LOAD ")]
    assert loads and all(int(row[-1], 16) >= 16384 for row in loads), "Missing 16 KiB LOAD alignment"
    relro = [line.split() for line in segments.splitlines() if line.strip().startswith("GNU_RELRO ")]
    assert relro and all((int(row[2], 16) + int(row[5], 16)) % 16384 == 0 for row in relro), \
        "Missing 16 KiB RELRO end alignment"
    (output / "elf-verification.txt").write_text(elf_header + segments)
    # UniFFI reads metadata from the Android library; no Android code runs on the build host.
    run("cargo", "run", "--locked", "--jobs", "2", "--bin", "uniffi-bindgen", "--", "generate",
        "--language", "kotlin", "--out-dir", str(output / "kotlin"),
        "--config", str(source / "uniffi.toml"), "--library", str(native))
    kotlin = output / "kotlin/computer/iroh"
    assert (kotlin / "iroh_ffi.kt").is_file(), "Kotlin binding generation failed"
    shutil.copy2(source / "kotlin/android/src/main/kotlin/computer/iroh/IrohAndroid.kt", kotlin)
    for name in ["LICENSE-MIT", "LICENSE-APACHE", "Cargo.lock", "uniffi.toml"]:
        shutil.copy2(source / name, output / name)
    receipt = {
        "repository": "https://github.com/manaflow-ai/iroh-ffi", "revision": PIN,
        "ndk": NDK_VERSION, "androidApi": API, "abi": "arm64-v8a",
        "elfPageSize": 16384,
        "rustc": run("rustc", "--version", capture=True).strip(),
        "scope": "Native library and generated bindings; not an APK or runtime acceptance",
        "files": {str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in sorted(output.rglob("*")) if p.is_file()},
    }
    (output / "manifest.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Pinned arm64 Iroh native checkpoint: {output}")


if __name__ == "__main__":
    main()
