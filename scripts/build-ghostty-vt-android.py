#!/usr/bin/env python3
"""Build the pinned cmux Ghostty VT core for Android without changing the app.

Requires an existing upstream Ghostty checkout, Zig 0.16.0 and NDK r28c.
Exports the pinned commit to a separate output directory; never patches the input
checkout. Native smoke checks use only synthetic terminal bytes and no network.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
REVISION = "edefce7785c9f439966c68588db1edbd6b435203"
NDK_VERSION = "28.2.13676358"
ZIG_VERSION = "0.16.0"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--zig", required=True, type=Path)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    zig, ndk, source = args.zig.resolve(), args.ndk.resolve(), args.source.resolve()
    if subprocess.check_output([str(zig), "version"], text=True).strip() != ZIG_VERSION:
        raise SystemExit(f"Requires Zig {ZIG_VERSION}")
    if f"Pkg.Revision = {NDK_VERSION}" not in (ndk / "source.properties").read_text().splitlines():
        raise SystemExit(f"Requires NDK {NDK_VERSION}")
    revision = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", f"{REVISION}^{{commit}}"], text=True).strip()
    if revision != REVISION:
        raise SystemExit("Pinned upstream revision is unavailable")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    archive = output / "source.tar"
    with archive.open("wb") as stream:
        subprocess.run(["git", "-C", str(source), "archive", "--format=tar", REVISION],
                       stdout=stream, check=True)
    checkout = output / "source"
    with tarfile.open(archive) as bundle:
        bundle.extractall(checkout, filter="data")
    archive.unlink()

    # Upstream sets only maximum page size. Android 17's RELRO validation also
    # requires the common page size; don't ship the unpatched shared library.
    build_file = checkout / "src/build/GhosttyLibVt.zig"
    before = build_file.read_text()
    anchor = "lib.link_z_max_page_size = 16384; // 16kb"
    if before.count(anchor) != 1:
        raise SystemExit("Upstream Android linker block changed; review the patch")
    build_file.write_text(before.replace(anchor,
        anchor + "\n        lib.link_z_common_page_size = 16384; // Android RELRO"))
    # Export only a synchronous adapter to Ghostty's own Unicode resolver. This
    # avoids copying the placeholder/diacritic/layout algorithms into Kotlin.
    bridge_source = ROOT / "ghostty/src/main/zig/virtual_placements.zig"
    bridge = checkout / "src/terminal/c/cmux_virtual_placements.zig"
    bridge.write_bytes(bridge_source.read_bytes())
    export_file = checkout / "src/lib_vt.zig"
    export_before = export_file.read_text()
    export_anchor = '        @export(&c.kitty_graphics_placement_render_info, .{ .name = "ghostty_kitty_graphics_placement_render_info" });'
    if export_before.count(export_anchor) != 1:
        raise SystemExit("Upstream graphics export changed; review the bridge")
    export_file.write_text(export_before.replace(export_anchor, export_anchor +
        '\n        @export(&@import("terminal/c/cmux_virtual_placements.zig").visit, .{ .name = "cmux_ghostty_virtual_placements" });'))
    env = dict(os.environ, ANDROID_NDK_HOME=str(ndk),
               PATH=str(zig.parent) + os.pathsep + os.environ.get("PATH", ""))
    prefix = output / "android"
    command = [str(zig), "build", "-Demit-lib-vt=true", "-Demit-xcframework=false",
               "-Demit-macos-app=false", "-Dtarget=aarch64-linux-android.26",
               "-Doptimize=ReleaseFast", "-j2", "--prefix", str(prefix)]
    with (output / "build.log").open("w") as log:
        subprocess.run(command, cwd=checkout, env=env, stdout=log,
                       stderr=subprocess.STDOUT, check=True)

    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}[platform.system()]
    compiler = ndk / "toolchains/llvm/prebuilt" / host / "bin/aarch64-linux-android26-clang"
    smoke_source = ROOT / "scripts/native/ghostty-vt-smoke.c"
    smoke = prefix / "bin/cmux-ghostty-vt-smoke"
    smoke.parent.mkdir(exist_ok=True)
    subprocess.run([str(compiler), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-I", str(prefix / "include"), str(smoke_source),
                    "-L", str(prefix / "lib"), "-lghostty-vt",
                    "-Wl,-z,relro,-z,now", "-Wl,-z,max-page-size=16384",
                    "-Wl,-z,common-page-size=16384", "-o", str(smoke)], check=True)
    spec = importlib.util.spec_from_file_location("alignment", ROOT / "scripts/verify-native-alignment.py")
    alignment = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(alignment)
    shared = (prefix / "lib/libghostty-vt.so").resolve()
    for artifact in [shared, smoke]:
        print(alignment.verify(artifact.read_bytes(), artifact.name))
    receipt = {
        "sourceUrl": "https://github.com/manaflow-ai/ghostty.git",
        "sourceRevision": REVISION, "zig": ZIG_VERSION, "ndk": NDK_VERSION,
        "androidApi": 26, "abi": "arm64-v8a", "elfPageSize": 16384,
        "patch": {"path": "src/build/GhosttyLibVt.zig",
                  "originalSha256": hashlib.sha256(before.encode()).hexdigest(),
                  "patchedSha256": digest(build_file)},
        "virtualPlacementBridge": {"sourceSha256": digest(bridge_source),
            "path": "src/terminal/c/cmux_virtual_placements.zig",
            "exportPath": "src/lib_vt.zig",
            "exportOriginalSha256": hashlib.sha256(export_before.encode()).hexdigest(),
            "exportPatchedSha256": digest(export_file)},
        "smokeSourceSha256": digest(smoke_source),
        "files": {str(p.relative_to(prefix)): digest(p)
                  for p in [shared, prefix / "lib/libghostty-vt.a", smoke]},
    }
    (output / "manifest.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Built {prefix}; run cmux-ghostty-vt-smoke on Android to verify runtime behavior.")


if __name__ == "__main__":
    main()
