#!/usr/bin/env python3
"""Compile pinned upstream Swift definitions into browser tunnel JSON goldens."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

PIN = "204a11dfcc76280205e50406ab94270a1c152155"
ROOT = "Packages/Shared/CmuxIrxTransport/Sources/CmuxIrxTransport/"
SOURCES = [ROOT + "IrxProtocol.swift", ROOT + "Tunnel/IrxTunnelWire.swift"]
MAIN = r'''
import Foundation
let encoder = JSONEncoder()
encoder.outputFormatting = [.sortedKeys]
func json<T: Encodable>(_ value: T) throws -> Any {
    try JSONSerialization.jsonObject(with: encoder.encode(value))
}
let descriptors = try [
    json(IrxLaneDescriptor(lane: .tcpConnect, host: "localhost", port: 3000)),
    json(IrxLaneDescriptor(lane: .tcpConnect, host: "::1", port: 65535)),
    json(IrxLaneDescriptor(lane: .listeningPorts))
]
let replies = try IrxTunnelOpenReply.Status.allCases.map { try json(IrxTunnelOpenReply(status: $0)) }
let listing = try json(IrxListeningPortsReply(ports: [
    IrxListeningPort(port: 80, address: "127.0.0.1"),
    IrxListeningPort(port: 3000, address: "::1"),
    IrxListeningPort(port: 3000, address: "127.9.8.7")
], allowsNonLoopbackHosts: true))
let result: [String: Any] = ["source": "204a11dfcc76280205e50406ab94270a1c152155",
    "capability": IrxTunnelCapability.current.identifier,
    "descriptors": descriptors, "replies": replies, "listing": listing]
print(String(data: try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys, .prettyPrinted]), encoding: .utf8)!)
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path)
    parser.add_argument("--output", type=Path, default=Path("app/src/test/resources/browser/tunnel-204a11d.json"))
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="cmux-browser-goldens-") as temporary:
        directory = Path(temporary)
        sources = []
        for index, source in enumerate(SOURCES):
            text = subprocess.check_output(["git", "-C", str(args.upstream), "show", f"{PIN}:{source}"], text=True)
            target = directory / f"Source{index}.swift"
            target.write_text(text)
            sources.append(str(target))
        entry = directory / "main.swift"
        entry.write_text(MAIN)
        binary = directory / "fixtures"
        subprocess.run(["xcrun", "swiftc", *sources, str(entry), "-o", str(binary)], check=True)
        result = json.loads(subprocess.check_output([str(binary)], text=True))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
        print(f"Wrote unchanged upstream {PIN[:7]} definitions to {args.output}")


if __name__ == "__main__":
    main()
