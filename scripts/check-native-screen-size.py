#!/usr/bin/env python3
"""Check NativeScreen DEX methods after :app:dexBuilderDebug (no APK required)."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
# ART Compiler::IsPathologicalCase rejects >= UINT16_MAX / 4 for either value.
# https://android.googlesource.com/platform/art/+/master/compiler/compiler.cc
ART_LIMIT = 65535 // 4


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dexdump", help="SDK build-tools dexdump executable")
    parser.add_argument("--archives", type=Path, default=ROOT / "app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out")
    parser.add_argument("--output", type=Path, help="Optional JSON measurement receipt")
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    executable = args.dexdump or shutil.which("dexdump") or (
        str(Path(sdk) / "build-tools/36.0.0" / ("dexdump.exe" if os.name == "nt" else "dexdump")) if sdk else None
    )
    if not executable:
        parser.error("Pass --dexdump or set ANDROID_HOME to the Android SDK")
    paths = sorted((args.archives / "io/github/docmorphic/cmuxapp").glob("NativeScreen*.dex"))
    if not paths:
        parser.error("No NativeScreen DEX archives; run :app:dexBuilderDebug first")
    methods = []
    # Read the official SDK dump incrementally: local-variable tables can be large.
    # Bound argv length for Windows checkouts as well as macOS.
    for offset in range(0, len(paths), 32):
        with subprocess.Popen([executable, *map(str, paths[offset:offset + 32])], stdout=subprocess.PIPE, text=True) as process:
            owner = name = registers = None
            for line in process.stdout:
                label, separator, value = line.strip().partition(":")
                if not separator:
                    continue
                label, value = label.strip(), value.strip()
                if label == "Class descriptor":
                    owner = value.strip("'")
                elif label == "name":
                    name, registers = value.strip("'"), None
                elif label == "registers":
                    registers = int(value)
                elif label == "insns size":
                    if owner is None or name is None or registers is None:
                        raise RuntimeError("Unrecognized dexdump method layout")
                    methods.append(dict(owner=owner, name=name, registers=registers, code_units=int(value.split()[0])))
            if process.wait() != 0:
                raise RuntimeError("dexdump failed")
    if not any(row["owner"] == "Lio/github/docmorphic/cmuxapp/NativeScreenKt;" and row["name"] == "NativeScreen" for row in methods):
        raise RuntimeError("NativeScreen entry method was not measured")
    methods.sort(key=lambda row: row["code_units"], reverse=True)
    rejected = [row for row in methods if row["code_units"] >= ART_LIMIT or row["registers"] >= ART_LIMIT]
    receipt = dict(limit_exclusive=ART_LIMIT, measured_methods=len(methods), rejected=rejected, methods=methods)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(limit_exclusive=ART_LIMIT, measured_methods=len(methods), rejected=rejected, largest=methods[:5]), indent=2))
    raise SystemExit(1 if rejected else 0)


if __name__ == "__main__":
    main()
