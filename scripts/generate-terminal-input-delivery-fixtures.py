#!/usr/bin/env python3
"""Generate wire goldens using the pinned, unmodified upstream Swift definitions.

Usage: python3 scripts/generate-terminal-input-delivery-fixtures.py /path/to/cmux
Requires Swift 6.4. Only the module import is removed when compiling definitions
in one temporary module. No source checkout or production files are modified.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

PIN = '204a11dfcc76280205e50406ab94270a1c152155'
SOURCES = [
    'Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/MobileTerminalInputDelivery.swift',
    'Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/MobileTerminalInputFrame.swift',
    'Packages/Shared/CmuxIrohTransport/Sources/CmuxIrohTransport/CmxIrohTerminalOutputEnvelope.swift',
    'Packages/Shared/CmuxIrohTransport/Sources/CmuxIrohTransport/CmxIrohTerminalOutputEnvelopeCodec.swift',
]
MAIN = r'''
import Foundation
let surface = UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!
let stream = UUID(uuidString: "ffeeddcc-bbaa-9988-7766-554433221100")!
let delivery = MobileTerminalInputDelivery(surfaceID: surface, streamID: stream, sequence: UInt64.max)
let text = "\u{1b}[A😀é\r"
func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
let states: [MobileTerminalInputAcknowledgement.Status] = [.applied, .duplicate, .gap, .surfaceMismatch, .terminalUnavailable, .busy, .rejected]
let acks: [[String: Any]] = states.map { state in
    let ack = MobileTerminalInputAcknowledgement(status: state, streamID: stream, sequence: UInt64.max, expected: state == .gap ? 7 : 0)
    return ["body": hex(ack.encoded()), "rpc": ack.rpcPayload,
            "envelope": hex(CmxIrohTerminalOutputEnvelopeCodec().encode(.inputAcknowledgement(ack)))]
}
let result: [String: Any] = [
    "source": "204a11dfcc76280205e50406ab94270a1c152155", "text": text,
    "surface": surface.uuidString, "stream": stream.uuidString, "sequence": String(UInt64.max),
    "identity": hex(delivery.encoded()),
    "legacy": hex(try MobileTerminalInputFrame(text: text).encoded()),
    "marked": hex(try MobileTerminalInputFrame(text: text, sequence: UInt64.max).encoded()),
    "identified": hex(try MobileTerminalInputFrame(text: text, delivery: delivery).encoded()),
    "both": hex(try MobileTerminalInputFrame(text: text, sequence: UInt64.max, delivery: delivery).encoded()),
    "acknowledgements": acks
]
let data = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys, .prettyPrinted])
print(String(data: data, encoding: .utf8)!)
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('--output', type=Path, default=Path('app/src/test/resources/terminal-input-delivery-204a11d.json'))
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='cmux-input-goldens-') as name:
        work = Path(name)
        sources = []
        for index, source in enumerate(SOURCES):
            text = subprocess.check_output(['git', '-C', str(args.upstream), 'show', f'{PIN}:{source}'], text=True)
            path = work / f'Source{index}.swift'
            path.write_text(text.replace('public import CMUXMobileCore\n', ''))
            sources.append(str(path))
        entry = work / 'main.swift'; entry.write_text(MAIN)
        binary = work / 'fixtures'
        subprocess.run(['xcrun', 'swiftc', *sources, str(entry), '-o', str(binary)], check=True)
        result = json.loads(subprocess.check_output([str(binary)], text=True))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + '\n')
        print(f'Wrote upstream {PIN[:7]} wire fixtures to {args.output}')

if __name__ == '__main__':
    main()
