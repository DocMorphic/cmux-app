#!/usr/bin/env python3
"""Preserve license/notice files for the Android Cloud dependency closure.

Uses Cargo's target-filtered resolved graph, excluding dev-only edges. Missing
texts are reported explicitly; license expressions are not substitutes for texts.
"""
import hashlib
import json
from pathlib import Path
import shutil


def legal_files(root):
    return sorted(p for p in root.rglob("*") if p.is_file()
                  and p.name.upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE", "COPYRIGHT", "UNLICENSE"))
                  and p.resolve().is_relative_to(root.resolve())
                  and ".git" not in p.parts)


def runtime_packages(metadata):
    packages = {p["id"]: p for p in metadata["packages"]}
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    root, = [p["id"] for p in packages.values() if p["name"] == "cmux-terminal-client"]
    pending, seen = [root], set()
    while pending:
        current = pending.pop()
        if current in seen:
            continue
        seen.add(current)
        for dep in nodes[current]["deps"]:
            if any(k["kind"] != "dev" for k in dep["dep_kinds"]):
                pending.append(dep["pkg"])
    return sorted((packages[key] for key in seen), key=lambda p: (p["name"], p["version"], p["id"]))


def collect(metadata, workspace, ghostty, zig, output):
    destination = output / "notices/licenses/cloud-terminal"
    destination.mkdir(parents=True, exist_ok=True)
    records, missing = [], []

    def preserve(label, root, files, info):
        names = []
        for source in files:
            relative = source.relative_to(root)
            target = destination / label / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            names.append(str(target.relative_to(output / "notices")))
        if not names:
            missing.append(label)
        records.append(dict(info, component=label, files=names))

    by_name = {p["name"]: p for p in metadata["packages"]}
    for package in runtime_packages(metadata):
        root = Path(package["manifest_path"]).parent
        files = legal_files(root)
        license_file = package.get("license_file")
        if license_file:
            explicit = root / license_file
            if explicit.is_file() and explicit.resolve().is_relative_to(root.resolve()) and explicit not in files:
                files.append(explicit)
        fallback = None
        if not files and root.resolve().is_relative_to(workspace.resolve()):
            root = workspace.parent
            files = [root / "LICENSE"]
            fallback = "cmux repository license; Cargo expression retained separately"
        if not files and package["name"] == "rustls-platform-verifier-android":
            # Its published AAR crate omits the repository's texts. The paired Rust
            # component in this lockfile carries the same project's MIT/Apache texts.
            root = Path(by_name["rustls-platform-verifier"]["manifest_path"]).parent
            files = legal_files(root)
            fallback = "paired rustls-platform-verifier crate"
        preserve(f"cargo/{package['name']}-{package['version']}", root, files,
                 {"ecosystem": "cargo", "name": package["name"], "version": package["version"],
                  "source": package.get("source"), "license": package.get("license"), "fallback": fallback})

    preserve("ghostty", ghostty, [ghostty / "LICENSE"], {"ecosystem": "zig"})
    packages = ghostty / "zig-pkg"
    if not packages.is_dir():
        missing.append("ghostty/zig-pkg")
    else:
        for package in sorted(packages.iterdir()):
            if package.is_dir():
                preserve("zig/" + package.name, package, legal_files(package), {"ecosystem": "zig"})
    preserve("zig-toolchain", zig.parent, [zig.parent / "LICENSE"], {"ecosystem": "compiler-runtime"})
    report = {"scope": "Target dependency closure including build dependencies; excludes dev-only Cargo edges",
              "components": records, "missingLicenseTexts": missing}
    report_path = destination / "inventory.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    aggregate = output / "notices/licenses/CloudTerminal.txt"
    aggregate.write_text("cmux Cloud terminal and bundled dependency notices\n\n" +
        "\n\n".join(str(p.relative_to(destination)) + "\n\n" + p.read_text(errors="replace")
                     for p in sorted(destination.rglob("*")) if p.is_file() and p != report_path))
    return {"inventorySha256": hashlib.sha256(report_path.read_bytes()).hexdigest(),
            "componentCount": len(records), "missingLicenseTexts": missing}
