# AndroidX graphics-path native source

Unmodified native files from `androidx/androidx` revision
`7b1104d5e67bd061e736e8d576b539498b498be4`, under
`graphics/graphics-path/src/main/cpp`. This commit changes the native build system;
it does not change these JNI implementations. Copyright notices remain in each file.
The official 1.1.0 AAR's combined license is retained in `LICENSE.txt`.

The released 1.1.0 arm64 library has a GNU_RELRO end at a 4 KiB boundary. The initial
checker treated that alone as incompatible; the 2026-10-04
[classifier correction](../../docs/NATIVE_ALIGNMENT.md) establishes that this
whole-LOAD RELRO layout is valid. The verified rebuilt artifact is retained.
`scripts/build-graphics-path-android.py`
rebuilds the same native implementation using pinned NDK 28.2 and explicit 16 KiB
maximum/common page sizes. It repackages the hash-verified official AAR, preserving
Java classes, resources, licenses and consumer rules. Only arm64 JNI is included,
matching this app's existing supported ABI. No RELRO protection is removed.

The generated AAR and receipt live under ignored `build/graphics-path-android`.
Rebuild using `python3 scripts/build-graphics-path-android.py --ndk <NDK> --output
build/graphics-path-android` or dispatch the Android workflow with `graphics_only`.
