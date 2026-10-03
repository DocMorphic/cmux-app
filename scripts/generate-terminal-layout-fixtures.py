#!/usr/bin/env python3
"""Run the pinned iOS layout math to generate Android geometry reference cases."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

PIN = '0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc'
SOURCES = [f'Packages/iOS/CmuxMobileTerminalKit/Sources/CmuxMobileTerminalKit/{name}.swift'
           for name in ['TerminalGridFit', 'TerminalLetterboxGeometry', 'TerminalKeyboardViewport']]
MAIN = r'''
import Foundation
import CoreGraphics
var cases: [[String: Any]] = []
let sizes: [[Double]] = [[400,800,40,20,8,20], [400,807,50,40,8,20],
    [400,800,100,80,8,20], [400,800,100,20,8,20], [400,400,40,80,8,20],
    [800,400,100,30,8,20], [400,800,40,39,8,20]]
for size in sizes {
    let viewport = CGRect(x: 0, y: 0, width: size[0], height: size[1])
    let grid = CGSize(width: size[2] * size[4], height: size[3] * size[5])
    for mag in [1.0, 1.5, 4.0] {
        for pan in [CGPoint.zero, CGPoint(x: 100, y: 130), CGPoint(x: -20, y: 2000)] {
            let mode = TerminalGridFitMode(effectiveColumns: Int(size[2]), effectiveRows: Int(size[3]),
                measuredColumns: Int(size[0] / size[4]), measuredRows: Int(size[1] / size[5]),
                gridPointSize: grid, container: viewport.size)
            let scaledMode = mode == .scaledToFit
            let base = TerminalScaledGridLayout(gridSize: grid, viewport: viewport, magnification: mag, offset: pan)
            for operation in ["base", "pan", "zoom"] {
                var layout = base
                if operation == "pan" { layout = base.panned(by: CGPoint(x: 43, y: 57)) }
                if operation == "zoom" { layout = base.zoomed(to: 1.7, about: CGPoint(x: 170, y: 190)) }
                let rect = scaledMode ? layout.displayRect : TerminalLetterboxGeometry.renderRect(
                    renderSize: grid, in: viewport, cellHeight: size[5])
                cases.append(["size": size, "mag": mag, "pan": [pan.x, pan.y], "operation": operation,
                    "scaled": scaledMode, "expected": [
                        "rect": [rect.minX, rect.minY, rect.width, rect.height],
                        "scale": scaledMode ? layout.displayScale : 1,
                        "mag": scaledMode ? layout.magnification : 1,
                        "pan": scaledMode ? [layout.offset.x, layout.offset.y] : [0, 0]]])
            }
        }
    }
}
var keyboardCases: [[String: Any]] = []
for intrusion in [0.0, 100.0, 400.0] {
    for blank: Double? in [nil, 0, 100, 600] {
        for reveal in [0.0, 50.0, 1000.0] {
            let layout = TerminalKeyboardViewport(viewportRect: CGRect(x: 0, y: 0, width: 400, height: 800),
                intrusion: intrusion, blankBelowContent: blank.map { CGFloat($0) }, scrollTopReveal: reveal)
            keyboardCases.append(["intrusion": intrusion, "blank": blank as Any? ?? NSNull(), "reveal": reveal, "slide": layout.slide])
        }
    }
}
var scrollCases: [[String: Any]] = []
for history in [0.0, 50.0] { for position in [0.0, 25.0, 50.0] {
    for reveal in [0.0, 40.0, 200.0] { for delta in [-100.0, -10.0, 0.25, 10.0, 100.0] {
        let primary = TerminalLetterboxGeometry.scrollTopRevealResolution(currentPositionPx: (history - min(history, position)) * 20,
            currentRevealPx: reveal, deltaPixels: -delta * 20, maxPositionPx: history * 20, maxRevealPx: 120)
        let line = TerminalLetterboxGeometry.lineScrollTopRevealResolution(currentRevealPx: reveal, deltaPixels: -delta * 20, maxRevealPx: 120)
        scrollCases.append(["history": history, "position": position, "reveal": reveal, "delta": delta,
            "primary_position": history - primary.positionPx / 20, "primary_reveal": primary.revealPx,
            "line_reveal": line.revealPx, "line_remaining": -line.leftoverDeltaPixels / 20])
    } }
} }
let result: [String: Any] = ["cases": cases, "keyboard_cases": keyboardCases, "scroll_cases": scrollCases]
print(String(data: try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]), encoding: .utf8)!)
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('--output', type=Path, default=Path('app/src/test/resources/terminal-layout-0fc35d6.json.gz'))
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='cmux-layout-') as directory:
        work = Path(directory)
        files = []
        hashes = {}
        for index, source in enumerate(SOURCES):
            data = subprocess.check_output(['git', '-C', str(args.upstream), 'show', f'{PIN}:{source}'])
            hashes[source] = hashlib.sha256(data).hexdigest()
            path = work / f'Source{index}.swift'
            path.write_bytes(data)
            files.append(str(path))
        entry = work / 'main.swift'
        entry.write_text(MAIN)
        binary = work / 'reference'
        subprocess.run(['xcrun', 'swiftc', *files, str(entry), '-o', str(binary)], check=True)
        cases = json.loads(subprocess.check_output([str(binary)], text=True))
        result = json.dumps({'revision': PIN, 'source_sha256': hashes, **cases}, sort_keys=True).encode()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(gzip.compress(result, mtime=0))
        print(f'Wrote {sum(len(values) for values in cases.values())} iOS layout/keyboard/scroll cases to {args.output}')


if __name__ == '__main__':
    main()
