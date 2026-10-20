#!/usr/bin/env python3
"""Execute pinned conversation ordering/run-plan code with Chief-only model inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--vectors", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / "third_party/cmux-chief/manifest.json").read_text())
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    sources = {}
    for path, expected in manifest["timelineFiles"].items():
        source = subprocess.check_output(["git", "-C", str(args.upstream.resolve()), "show", f'{manifest["revision"]}:{path}'])
        if hashlib.sha256(source).hexdigest() != expected:
            raise ValueError("Unreviewed conversation source: " + path)
        sources[Path(path).name] = source.decode()
    strings = manifest["timestampStrings"]
    catalog_bytes = subprocess.check_output(["git", "-C", str(args.upstream.resolve()), "show", f'{manifest["revision"]}:{strings["path"]}'])
    if hashlib.sha256(catalog_bytes).hexdigest() != strings["sha256"]:
        raise ValueError("Unreviewed conversation timestamp catalog")
    catalog = json.loads(catalog_bytes)["strings"]
    for locale, folder in {"en": "values", "ja": "values-ja"}.items():
        resources = {node.attrib["name"]: node.text for node in ET.parse(root / "app/src/main/res" / folder / "chief_timestamps.xml").getroot()}
        expected = {name: catalog[key]["localizations"][locale]["stringUnit"]["value"].replace("$@", "$s")
                    for key, name in strings["keys"].items()}
        if resources != expected:
            raise ValueError("Timestamp resources differ from source: " + locale)
    # Keep the actual algorithm bodies unchanged. The inert model supplies only
    # fields read by these bodies; it is not the iOS renderer or full store.
    model = sources["ConversationModel.swift"]
    row_id = next(line for line in model.splitlines() if "public var rowID: String {" in line)
    store = sources["ConversationStore.swift"]
    start = store.index("    func sortAndReindex() {")
    end = store.index("\n    }", start) + len("\n    }")
    fixture = (root / "scripts/chief-timeline-fixture.swift").read_text()
    fixture = fixture.replace("// PINNED_ROW_ID", row_id).replace("// PINNED_SORT", store[start:end])
    (output / "fixture.swift").write_text(fixture)
    (output / "ConversationRunPlan.swift").write_text(sources["ConversationRunPlan.swift"])
    binary = output / "fixture"
    compiled = subprocess.run(["/usr/bin/swiftc", "-swift-version", "6", str(output / "ConversationRunPlan.swift"),
        str(output / "fixture.swift"), "-o", str(binary)], capture_output=True, timeout=60)
    (output / "build.log").write_bytes(compiled.stdout + compiled.stderr); compiled.check_returncode()
    vectors = json.loads(subprocess.check_output([str(binary)], timeout=10))
    encoded = (json.dumps(vectors, indent=2, sort_keys=True) + "\n").encode()
    (output / "vectors.json").write_bytes(encoded)
    if args.vectors:
        args.vectors.parent.mkdir(parents=True, exist_ok=True); args.vectors.write_bytes(encoded)
    receipt = {"revision": manifest["revision"], "sources": manifest["timelineFiles"],
               "cases": len(vectors), "timestampCatalog": strings, "timestampResources": 6, "vectorsSha256": hashlib.sha256(encoded).hexdigest(),
               "scope": "actual pinned run plan, store sort method and rowID property; Chief-only inert model; no UIKit/live host"}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Pinned conversation timeline: {len(vectors)} cases passed through actual Swift code")


if __name__ == "__main__":
    main()
