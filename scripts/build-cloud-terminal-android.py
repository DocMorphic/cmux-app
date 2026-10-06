#!/usr/bin/env python3
"""Build the pinned upstream Cloud C ABI for Android; no APK, credentials or VM access.

Exports immutable Git revisions; input checkouts are never modified. Run on a
hosted Linux builder with the upstream Rust version, Zig 0.16.0 and NDK r28c.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
PIN = "c2715faa02c260b07012bc0b386597cfb333021d"
GHOSTTY_PIN = "324c0273815ddc383d8d2fbf8d59d590cb65dfdb"
NDK_VERSION = "28.2.13676358"
TARGET = "aarch64-linux-android"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*args, cwd=None, env=None, capture=False):
    return subprocess.run([str(arg) for arg in args], cwd=cwd, env=env, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def export(source, revision, destination, paths=()):
    if run("git", "-C", source, "rev-parse", f"{revision}^{{commit}}", capture=True).strip() != revision:
        raise RuntimeError("Pinned source revision unavailable")
    archive = destination.with_suffix(".tar")
    with archive.open("wb") as stream:
        subprocess.run(["git", "-C", str(source), "archive", revision, *paths], check=True, stdout=stream)
    with tarfile.open(archive) as bundle:
        bundle.extractall(destination, filter="data")
    archive.unlink()


def replace_once(path, before, after):
    source = path.read_text()
    if source.count(before) != 1:
        raise RuntimeError(f"Upstream patch anchor changed: {path.name}")
    path.write_text(source.replace(before, after))
    return {"originalSha256": hashlib.sha256(source.encode()).hexdigest(), "patchedSha256": digest(path)}


def patch_android_pty(path):
    original = digest(path)
    # Android is Unix but not Rust's target_os=linux. Bionic ptsname_r returns
    # errno like Linux; getdtablesize is absent, so use POSIX sysconf before fork.
    for function in ("platform_ptsname_r", "ptsname_error"):
        replace_once(path, f'#[cfg(target_os = "linux")]\nfn {function}',
                     f'#[cfg(any(target_os = "linux", target_os = "android"))]\nfn {function}')
    replace_once(path, "    let descriptor_limit = unsafe { libc::getdtablesize() };", '''    #[cfg(not(target_os = "android"))]
    let descriptor_limit = unsafe { libc::getdtablesize() };
    #[cfg(target_os = "android")]
    let descriptor_limit = RawFd::try_from(unsafe { libc::sysconf(libc::_SC_OPEN_MAX) })
        .context("failed to determine Android descriptor limit")?;''')
    # Preserve fail-closed bounded post-fork work when Android takes the portable
    # descriptor loop rather than Linux's close_range/proc implementation.
    replace_once(path, "    mark_descriptors_close_on_exec_individually(cleanup.descriptor_limit)", '''    #[cfg(target_os = "android")]
    if cleanup.descriptor_limit > 65_536 {
        return Err(io::Error::from_raw_os_error(libc::EOVERFLOW));
    }
    mark_descriptors_close_on_exec_individually(cleanup.descriptor_limit)''')
    return {"originalSha256": original, "patchedSha256": digest(path)}


def patch_android_errno(path):
    original = digest(path)
    replace_once(path, "    unsafe { *libc::__errno_location() = value };", '''    #[cfg(target_os = "android")]
    unsafe { *libc::__errno() = value };
    #[cfg(target_os = "linux")]
    unsafe { *libc::__errno_location() = value };''')
    replace_once(path, "    unsafe { *libc::__errno_location() }", '''    #[cfg(target_os = "android")]
    { unsafe { *libc::__errno() } }
    #[cfg(target_os = "linux")]
    { unsafe { *libc::__errno_location() } }''')
    return {"originalSha256": original, "patchedSha256": digest(path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "ghostty", "zig", "ndk", "output"):
        parser.add_argument(f"--{name}", required=True, type=Path)
    args = parser.parse_args()
    source, ghostty, zig, ndk, output = (getattr(args, name).resolve() for name in
                                         ("source", "ghostty", "zig", "ndk", "output"))
    if platform.system() != "Linux":
        raise SystemExit("Use the hosted Linux Cloud native workflow")
    if run(zig, "version", capture=True).strip() != "0.16.0":
        raise SystemExit("Requires Zig 0.16.0")
    if f"Pkg.Revision = {NDK_VERSION}" not in (ndk / "source.properties").read_text().splitlines():
        raise SystemExit("Wrong Android NDK")
    if GHOSTTY_PIN not in run("git", "-C", source, "ls-tree", PIN, "ghostty", capture=True):
        raise SystemExit("Ghostty pin does not match the client source")
    output.mkdir(parents=True, exist_ok=False)
    export(source, PIN, output / "source", ("cmux-tui", "LICENSE"))
    export(ghostty, GHOSTTY_PIN, output / "ghostty")
    # cmux-tui-core include_str! paths expect the original root submodule layout.
    # Reuse the same exported source; do not copy a second Ghostty tree.
    (output / "source/ghostty").symlink_to("../ghostty", target_is_directory=True)
    workspace = output / "source/cmux-tui"
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    compiler = toolchain / "bin/aarch64-linux-android26-clang"
    prefix = output / "ghostty-android"
    env = dict(os.environ, ANDROID_NDK_HOME=str(ndk),
               PATH=str(zig.parent) + os.pathsep + os.environ.get("PATH", ""))
    env.update({
        "CMUX_GHOSTTY_ANDROID_PREFIX": str(prefix), "CMUX_ANDROID_SYSROOT": str(toolchain / "sysroot"),
        "CMUX_TUI_BUILD_COMMIT": PIN + "-android-cloud",
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER": str(compiler),
        "CC_aarch64_linux_android": str(compiler),
        "CXX_aarch64_linux_android": str(toolchain / "bin/aarch64-linux-android26-clang++"),
        "AR_aarch64_linux_android": str(toolchain / "bin/llvm-ar"),
        "CARGO_PROFILE_RELEASE_LTO": "off", "CARGO_PROFILE_RELEASE_DEBUG": "0",
        "CARGO_INCREMENTAL": "0",
        "CARGO_TARGET_DIR": str(output.parent / "cloud-cargo-target"),
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS":
            "-C embed-bitcode=no -C link-arg=-Wl,--exclude-libs,ALL "
            "-C link-arg=-Wl,-z,relro,-z,now -C link-arg=-Wl,-z,max-page-size=16384 "
            "-C link-arg=-Wl,-z,common-page-size=16384",
    })
    patches = {}
    lib_build = output / "ghostty/src/build/GhosttyLibVt.zig"
    anchor = "lib.link_z_max_page_size = 16384; // 16kb"
    patches["ghostty/src/build/GhosttyLibVt.zig"] = replace_once(lib_build, anchor,
        anchor + "\n        lib.link_z_common_page_size = 16384; // Android RELRO")
    run(zig, "build", "-Demit-lib-vt=true", "-Demit-xcframework=false", "-Demit-macos-app=false",
        "-Dtarget=aarch64-linux-android.26", "-Doptimize=ReleaseFast", "-j2", "--prefix", prefix,
        cwd=output / "ghostty", env=env)
    build_rs = workspace / "crates/ghostty-vt-sys/build.rs"
    original = digest(build_rs)
    shutil.copy2(ROOT / "scripts/native/cloud-ghostty-build.rs", build_rs)
    patches["cmux-tui/crates/ghostty-vt-sys/build.rs"] = {"originalSha256": original, "patchedSha256": digest(build_rs)}
    manifest = workspace / "crates/cmux-terminal-client/Cargo.toml"
    patches["cmux-tui/crates/cmux-terminal-client/Cargo.toml"] = replace_once(manifest,
        'crate-type = ["staticlib", "rlib"]', 'crate-type = ["cdylib", "rlib"]')
    patches["cmux-tui/crates/cmux-pty/src/macos.rs"] = patch_android_pty(workspace / "crates/cmux-pty/src/macos.rs")
    for relative in ("crates/cmux-tui-core/src/workspace_registry.rs",
                     "crates/cmux-remote/src/workspace/files.rs"):
        patches[f"cmux-tui/{relative}"] = patch_android_errno(workspace / relative)
    # Use the upstream rust-toolchain.toml without a separately drifting workflow version.
    run("cargo", "build", "--locked", "--release", "--lib", "-p", "cmux-terminal-client",
        "--target", TARGET, "--jobs", "2", cwd=workspace, env=env)
    metadata = json.loads(run("cargo", "metadata", "--locked", "--format-version", "1", cwd=workspace, env=env, capture=True))
    built = Path(metadata["target_directory"]) / TARGET / "release/libcmux_terminal_client.so"
    native = output / "jniLibs/arm64-v8a/libcmux_terminal_client.so"
    native.parent.mkdir(parents=True)
    shutil.copy2(built, native)
    run(toolchain / "bin/llvm-strip", "--strip-debug", native)
    elf = run(toolchain / "bin/llvm-readelf", "-h", "-lW", "-d", native, capture=True)
    if "AArch64" not in elf or "GNU_RELRO" not in elf:
        raise RuntimeError("Wrong architecture or missing RELRO")
    spec = importlib.util.spec_from_file_location("alignment", ROOT / "scripts/verify-native-alignment.py")
    alignment = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(alignment)
    proof = alignment.verify(native.read_bytes(), native.name)
    exports = run(toolchain / "bin/llvm-nm", "-D", "--defined-only", native, capture=True)
    header = workspace / "crates/cmux-terminal-client/include/cmux_terminal_client.h"
    required = set(re.findall(r"\b(cmux_(?:terminal_client|wireguard_net)_\w+)\s*\(", header.read_text()))
    symbols = {line.split()[-1] for line in exports.splitlines() if line.split()}
    if not required or not required <= symbols or any(name.startswith("ghostty_") for name in symbols):
        raise RuntimeError(f"C ABI missing {sorted(required - symbols)} or private Ghostty symbols exported")
    bridge_source = ROOT / "app/src/main/c/cloud_terminal_jni.c"
    bridge = native.with_name("libcmux_cloud_jni.so")
    run(compiler, "-shared", "-fPIC", "-O2", "-std=c11", "-Wall", "-Wextra", "-Werror", "-fvisibility=hidden",
        "-I", header.parent, bridge_source, "-L", native.parent, "-lcmux_terminal_client",
        "-Wl,--no-undefined", "-Wl,-z,relro,-z,now", "-Wl,-z,max-page-size=16384",
        "-Wl,-z,common-page-size=16384", "-Wl,-soname,libcmux_cloud_jni.so", "-o", bridge)
    run(toolchain / "bin/llvm-strip", "--strip-debug", bridge)
    bridge_proof = alignment.verify(bridge.read_bytes(), bridge.name)
    bridge_elf = run(toolchain / "bin/llvm-readelf", "-h", "-lW", "-d", bridge, capture=True)
    (output / "elf-verification.txt").write_text(proof + "\n" + elf + "\n" + exports + "\n" + bridge_proof + "\n" + bridge_elf)
    shutil.copy2(header, output / header.name)
    shutil.copy2(workspace / "Cargo.lock", output / "Cargo.lock")
    # Retain dependency identities/licenses for the packaging review. No source upload.
    packages = [{key: package.get(key) for key in ("name", "version", "source", "license", "license_file")}
                for package in metadata["packages"]]
    (output / "dependencies.json").write_text(json.dumps(packages, indent=2) + "\n")
    notices = output / "notices/licenses/cloud-terminal"
    notices.mkdir(parents=True)
    shutil.copy2(output / "source/LICENSE", notices / "cmux-LICENSE")
    shutil.copy2(output / "ghostty/LICENSE", notices / "ghostty-LICENSE")
    artifacts = [native, bridge, output / header.name, output / "Cargo.lock", output / "dependencies.json",
                 output / "elf-verification.txt", *notices.iterdir()]
    receipt = {"sourceRevision": PIN, "ghosttyRevision": GHOSTTY_PIN, "ndk": NDK_VERSION,
               "androidApi": 26, "abi": "arm64-v8a", "elfPageSize": 16384,
               "rustc": run("rustc", "--version", cwd=workspace, capture=True).strip(),
               "zig": "0.16.0", "patches": patches, "builderSha256": digest(Path(__file__)),
               "ghosttyBuildAdapterSha256": digest(ROOT / "scripts/native/cloud-ghostty-build.rs"),
               "jniSourceSha256": digest(bridge_source),
               "scope": "C ABI library and alignment only; Android runtime, packaging and dependency notices review pending",
               "files": {str(path.relative_to(output)): digest(path) for path in artifacts}}
    (output / "manifest.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(proof)


if __name__ == "__main__":
    main()
