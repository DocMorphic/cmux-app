#!/usr/bin/env python3
"""Execute the whole original L10n helper for positive unread-count boundaries."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--native-capture',type=Path,required=True)
    parser.add_argument('--write-reference',action='store_true')
    args=parser.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    manifest=json.loads((ROOT/'third_party/cmux-chief/manifest.json').read_text());entry=manifest['homeStrings']['unreadHelper']
    source=subprocess.check_output(['git','-C',str(args.upstream.resolve()),'show',manifest['revision']+':'+entry['path']])
    assert hashlib.sha256(source).hexdigest()==entry['sha256']
    original=out/'OriginalL10n.swift';original.write_bytes(source)
    driver=out/'main.swift';driver.write_text('''import Foundation
let counts: [Int] = [1, 2, 17, 1234, Int(Int32.max), Int(Int32.max) + 1, 4294967296, Int.max]
let rows = counts.map { ["count": String($0), "value": L10n.unreadLabel(count: $0)] }
let bytes = try JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys])
FileHandle.standardOutput.write(bytes)
''')
    binary=out/'original-unread'
    result=subprocess.run(['swiftc','-swift-version','6',str(original),str(driver),'-o',str(binary)],capture_output=True)
    (out/'build.log').write_bytes(result.stdout+result.stderr);result.check_returncode()
    rows=json.loads(subprocess.check_output([str(binary)]));assert len(rows)==8
    native=json.loads((args.native_capture/'observations/en.json').read_text())
    for row in rows:
        if row['count'] in native['unreadLabels']:assert row['value']==native['unreadLabels'][row['count']]
    reference=dict(revision=manifest['revision'],helperSha256=entry['sha256'],rows=rows,
        scope='Whole unmodified source helper on local macOS Foundation with English main-bundle defaults. Counts1/2/17/1234 agree with original iOS run40200670788; large-count iOS execution remains separate.')
    target=ROOT/'app/src/test/resources/parity/chief-unread-format.json'
    if args.write_reference:target.write_text(json.dumps(reference,indent=2)+'\n')
    else:assert json.loads(target.read_text())==reference
    (out/'receipt.json').write_text(json.dumps(dict(reference=reference,originalSha256=hashlib.sha256(original.read_bytes()).hexdigest(),driverSha256=hashlib.sha256(driver.read_bytes()).hexdigest()),indent=2)+'\n')
    print('Checked eight original positive-count results including signed32 and signed64 limits; four agree with native iOS')


if __name__=='__main__':main()
