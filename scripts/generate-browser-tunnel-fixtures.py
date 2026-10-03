#!/usr/bin/env python3
"""Compile pinned upstream Swift definitions into browser tunnel JSON goldens."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

PIN = "204a11dfcc76280205e50406ab94270a1c152155"
ROOT = "Packages/Shared/CmuxIrxTransport/Sources/CmuxIrxTransport/"
SOURCES = [ROOT + "IrxProtocol.swift", ROOT + "Tunnel/IrxTunnelWire.swift",
           "Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/CmxLoopbackHost.swift"]
# Unused route/endpoint overloads need these type declarations. The matcher itself is unchanged.
STUBS = '''public enum CmxAttachEndpoint { case hostPort(host: String, port: Int), unused }
public struct CmxAttachRoute {
    public enum Kind { case debugLoopback, tailscale }
    public var kind: Kind
    public var endpoint: CmxAttachEndpoint
}
'''
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
    "descriptors": descriptors, "replies": replies, "listing": listing,
    "loopback": [
        "127.0.0.1", " 127.0.0.1 ", "127.0.0.2", "127.255.255.255",
        "localhost", "LocalHost", "dev.localhost", "localhost.", "dev.localhost.",
        "::1", "[::1]", "::ffff:127.0.0.1", "[::ffff:127.0.0.1]",
        "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]", "[::1%lo0]",
        "::ffff:7f00:1", "::127.0.0.1", "127.1", "127.0.1", "2130706433",
        "0x7f.0.0.1", "0177.0.0.1", "0.0.0.0", "0", "::", "127.0.0",
        "100.64.0.5", "128.0.0.1", "126.255.255.255", "10.0.0.1",
        "lawrences-mac.tail1234.ts.net", "localhost.example.com", "fd7a:115c:a1e0::1",
        "::ffff:100.64.0.5", "127.0.0.0.1", "", "128.1", "1681915909",
        "127.0.0.01", "0x7fffffff", "017777777777", "0x100000000", "4294967296",
        "127.16777215", "127.16777216", "127..1", "127.0.0.09", "+127.0.0.1",
        "localhost..", ".localhost", "[::ffff:0.8.7.6]", "::ffff:8080:1",
        "127.0.0.1.", "0:0:0:0:0:0:7f00:1", "[::1%en0]", "::gggg",
        "0.255.255.255", "1.0.0.0", "\u{00a0}localhost\u{00a0}",
        "4294967423.1", "127.4294967297", "18446744073709551616",
        "0x100000000000000007f", "99999999999999999999999999999",
        "0x", "0x.1", "127.0.0.1x", "127.0.0.1 x"
    ].map { ["host": $0, "matches": CmxLoopbackHost().matches($0)] as [String: Any] }]
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
        stubs = directory / "AttachTypes.swift"
        stubs.write_text(STUBS)
        binary = directory / "fixtures"
        subprocess.run(["xcrun", "swiftc", *sources, str(stubs), str(entry), "-o", str(binary)], check=True)
        result = json.loads(subprocess.check_output([str(binary)], text=True))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
        print(f"Wrote unchanged upstream {PIN[:7]} definitions to {args.output}")


if __name__ == "__main__":
    main()
