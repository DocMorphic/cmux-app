#!/usr/bin/env python3
"""Export golden bytes using the unmodified pinned cmux Swift codec (requires swiftc)."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

PIN = '4c5272e9153eca2033c9f40ac749f0c3a5bcb291'
ROOT = 'Packages/Shared/CmuxSimulatorStreamKit/Sources/CmuxSimulatorStreamKit/'
p = argparse.ArgumentParser()
p.add_argument('--source', required=True, type=Path)
p.add_argument('--output', type=Path, default=Path('app/src/test/resources/simulator/wire.json'))
args = p.parse_args()
main = r'''
import Foundation
let samples: [(String, SimStreamMessage)] = [
 ("start", .start(.init(epoch: UInt64.max, maximumLongSidePixels: 1600, codecPreferences: [.hevc, .h264]))),
 ("hevc", .config(.init(codec: .hevc, pixelWidth: 1179, pixelHeight: 2556, displayScale: 3, orientation: .portrait,
     parameterSets: [Data([0x40, 1]), Data([0x42, 1]), Data([0x44, 1])], nalUnitHeaderLength: 4))),
 ("h264", .config(.init(codec: .h264, pixelWidth: 1920, pixelHeight: 1080, displayScale: 2, orientation: .landscapeRight,
     parameterSets: [Data([0x67, 1]), Data([0x68, 1])], nalUnitHeaderLength: 2))),
 ("frame", .frame(.init(sequence: UInt64.max - 1, flags: .init(rawValue: 0x81), presentationMicroseconds: 123456789,
     payload: Data([0, 0, 0, 3, 0xAB, 0xCD, 0xEF])))),
 ("ack", .ack(.init(sequence: UInt64.max - 1, receiptMicroseconds: 999))),
 ("input", .input(.init(sequence: 3, events: [
     .touch(phase: .began, pointerID: 255, x: 0.25, y: 0.75, timestampMicroseconds: 1000),
     .touch(phase: .moved, pointerID: 255, x: 0.3, y: 0.7, timestampMicroseconds: 1016),
     .touch(phase: .ended, pointerID: 255, x: 0.3, y: 0.7, timestampMicroseconds: 1032),
     .touch(phase: .cancelled, pointerID: 1, x: 0, y: 1, timestampMicroseconds: 1048),
     .text("héllo wörld 🚀"), .key(usage: 40, isDown: true), .key(usage: 40, isDown: false),
     .button(.home), .button(.lock), .button(.siri), .button(.sideButton), .button(.appSwitcher),
     .button(.volumeUp), .button(.volumeDown), .button(.power), .button(.swipeHome)
 ]))),
 ("keyframe", .keyframeRequest), ("stop", .stop),
 ("state", .state(.init(status: .deviceUnavailable, detail: "sim shut down 🚀")))
]
let output = Dictionary(uniqueKeysWithValues: samples.map { ($0.0, SimStreamWireCodec().encodeFramed($0.1).base64EncodedString()) })
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys]))
'''
with tempfile.TemporaryDirectory(prefix='cmux-sim-golden-') as folder:
    tmp = Path(folder)
    hashes = {}
    sources = []
    for name in ['SimStreamProtocol.swift', 'SimStreamWireCodec.swift']:
        data = subprocess.check_output(['git', '-C', str(args.source), 'show', PIN + ':' + ROOT + name])
        hashes[ROOT + name] = hashlib.sha256(data).hexdigest()
        target = tmp / name
        target.write_bytes(data)
        sources.append(str(target))
    (tmp / 'main.swift').write_text(main)
    subprocess.run(['xcrun', 'swiftc', *sources, str(tmp / 'main.swift'), '-o', str(tmp / 'export')], check=True)
    messages = json.loads(subprocess.check_output([str(tmp / 'export')]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'upstream': PIN, 'source_sha256': hashes, 'messages': messages}, indent=2) + '\n')
    print(f'Exported {len(messages)} Swift wire fixtures to {args.output}')
