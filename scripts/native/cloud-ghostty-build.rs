// Android-only replacement for ghostty-vt-sys/build.rs in the exported Cloud
// client workspace. The pinned Ghostty core is built once by the Python driver.
// Never let an unrecognized Cargo target silently select Zig's host target.
use std::{env, path::PathBuf};

fn main() {
    assert_eq!(env::var("TARGET").unwrap(), "aarch64-linux-android");
    let prefix = PathBuf::from(env::var("CMUX_GHOSTTY_ANDROID_PREFIX").unwrap());
    let sysroot = env::var("CMUX_ANDROID_SYSROOT").unwrap();
    let include = prefix.join("include");
    let archive = prefix.join("lib/libghostty-vt.a");
    assert!(archive.is_file());
    println!("cargo:rerun-if-env-changed=CMUX_GHOSTTY_ANDROID_PREFIX");
    println!("cargo:rerun-if-env-changed=CMUX_ANDROID_SYSROOT");
    println!("cargo:rerun-if-changed={}", archive.display());
    println!("cargo:rerun-if-changed={}", include.display());
    println!("cargo:rustc-link-search=native={}", prefix.join("lib").display());
    println!("cargo:rustc-link-lib=static=ghostty-vt");
    let bindings = bindgen::Builder::default()
        .header(include.join("ghostty/vt.h").to_str().unwrap())
        .clang_arg(format!("-I{}", include.display()))
        .clang_arg("--target=aarch64-linux-android26")
        .clang_arg(format!("--sysroot={sysroot}"))
        .allowlist_function("ghostty_.*")
        .allowlist_type("Ghostty.*")
        .allowlist_var("GHOSTTY_.*")
        .prepend_enum_name(false)
        .derive_default(true)
        .layout_tests(false)
        .generate()
        .expect("Android Ghostty bindings failed");
    bindings.write_to_file(PathBuf::from(env::var("OUT_DIR").unwrap()).join("bindings.rs")).unwrap();
}
