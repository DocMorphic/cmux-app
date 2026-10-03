#!/usr/bin/env python3
"""Rebuild AndroidX graphics-path's arm64 JNI library with 16 KiB RELRO alignment.

Retains the verified official AAR's Java classes, resources and consumer rules;
replaces only JNI with an arm64 build from unchanged, pinned AndroidX sources.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "third_party/androidx-graphics-path"
NDK_VERSION = "28.2.13676358"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    receipt = json.loads((SOURCE / "manifest.json").read_text())
    for name, digest in receipt["files"].items():
        assert hashlib.sha256((SOURCE / name).read_bytes()).hexdigest() == digest, f"Source changed: {name}"
    assert f"Pkg.Revision = {NDK_VERSION}" in (args.ndk / "source.properties").read_text()
    host = {"Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}[platform.system()]
    compiler = args.ndk.resolve() / "toolchains/llvm/prebuilt" / host / "bin/aarch64-linux-android26-clang++"
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    original = output / "original.aar"
    subprocess.run(["curl", "-fsSL", "--retry", "2",
        "https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/1.1.0/graphics-path-1.1.0.aar",
        "-o", str(original)], check=True)
    assert hashlib.sha256(original.read_bytes()).hexdigest() == receipt["originalAarSha256"], "Official AAR changed"
    cpp = SOURCE / "cpp"
    native = output / "libandroidx.graphics.path.so"
    # Flags from pinned AndroidX build.gradle, plus explicit page sizes and RELRO.
    subprocess.run([str(compiler), "-shared", "-fPIC", "-O2", "-std=c++17",
        "-fno-exceptions", "-fno-unwind-tables", "-fno-asynchronous-unwind-tables", "-fno-rtti",
        "-ffast-math", "-ffp-contract=fast", "-fvisibility-inlines-hidden", "-fvisibility=hidden",
        "-fomit-frame-pointer", "-ffunction-sections", "-fdata-sections", "-nostdlib++",
        "-Wl,--hash-style=both", "-Wl,-Bsymbolic-functions", "-Wl,--gc-sections",
        "-Wl,-z,relro,-z,now", "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
        "-Wl,-soname,libandroidx.graphics.path.so", f"-Wl,--version-script={cpp / 'libandroidx.graphics.path.map'}",
        *(str(cpp / name) for name in ["Conic.cpp", "PathIterator.cpp", "pathway.cpp"]),
        "-o", str(native), "-llog", "-lm"], check=True)
    spec = importlib.util.spec_from_file_location("alignment", ROOT / "scripts/verify-native-alignment.py")
    alignment = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(alignment)
    print(alignment.verify(native.read_bytes(), native.name))
    rebuilt = output / "graphics-path-1.1.0-relro.aar"
    with zipfile.ZipFile(original) as src, zipfile.ZipFile(rebuilt, "w", compression=zipfile.ZIP_DEFLATED) as dst:
        for entry in src.infolist():
            if not entry.filename.startswith("jni/"):
                dst.writestr(entry, src.read(entry))
        dst.writestr("jni/arm64-v8a/libandroidx.graphics.path.so", native.read_bytes())
    manifest = {"sourceRevision": receipt["revision"], "version": "1.1.0", "ndk": NDK_VERSION,
        "abi": "arm64-v8a", "elfPageSize": 16384,
        "files": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [original, native, rebuilt]}}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
