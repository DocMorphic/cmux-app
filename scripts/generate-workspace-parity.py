#!/usr/bin/env python3
"""Run the pinned, unmodified cmux Swift algorithms to generate Android parity cases.
Usage: python3 scripts/generate-workspace-parity.py /path/to/cmux
Requires Swift 6. Output is committed so JVM/CI tests do not require Swift.
"""
import gzip
import hashlib
import json
from pathlib import Path
import random
import subprocess
import sys
import tempfile

PIN = '4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0'
ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = Path(sys.argv[1]).resolve()
FILES = ['MobileWorkspaceListItem', 'MobileWorkspaceListItem+MoveIntent',
         'MobileWorkspaceMoveIntent', 'MobileWorkspaceMovePolicy',
         'MobileWorkspaceOrderSignature', 'MobileWorkspaceOrderMoveApplier',
         'MobileWorkspaceListMoveIntentResolver', 'MobileWorkspaceUnreadState']
REL = Path('Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel')

def w(id, group=None, pinned=False, unread=False, count=None):
    return dict(id=id, title=id, group_id=group, is_pinned=pinned, has_unread=unread, unread_count=count, window_id='window')

def g(id, anchor=None, pinned=False, collapsed=False):
    return dict(id=id, name=id, anchor_workspace_id=anchor, is_empty=anchor is None,
                is_pinned=pinned, is_collapsed=collapsed)

cases = []
def case(name, rows, groups):
    cases.append(dict(name=name, workspaces=rows, groups=groups))

case('empty', [], [])
case('empty-groups-only', [], [g('p', pinned=True), g('a'), g('b')])
case('flat', [w('a'), w('b'), w('c')], [])
case('flat-pins', [w('p', pinned=True), w('q', pinned=True), w('a'), w('b')], [])
for collapsed in [False, True]:
    case('group-boundaries-' + str(collapsed),
         [w('root'), w('anchor', 'a'), w('member', 'a', unread=True), w('tail')],
         [g('a', 'anchor', collapsed=collapsed)])
case('anchor-only', [w('root'), w('anchor', 'a'), w('tail')], [g('a', 'anchor')])
case('noncontiguous', [w('anchor', 'a'), w('one', 'a'), w('root'), w('two', 'a')], [g('a', 'anchor')])
case('empty-promoted', [w('root'), w('new', 'empty'), w('other', 'empty')], [g('empty')])
case('unknown-group', [w('a', 'missing'), w('b'), w('c')], [])
case('missing-anchor', [w('a', 'g'), w('b', 'g'), w('c')], [g('g', 'missing')])
case('empty-slots', [w('pin', pinned=True), w('pa', 'pg'), w('a', 'g'), w('b', 'g'), w('root')],
     [g('empty-pin-before', pinned=True), g('pg', 'pa', pinned=True), g('empty-pin-after', pinned=True),
      g('empty-before'), g('g', 'a'), g('empty-after')])
for collapsed in [False, True]:
    for counts in [(2, 3), (2, None), (None, 3), (0, 5), (1234, 5678)]:
        case(f'unread-{collapsed}-{counts}',
             [w('anchor', 'g', unread=True, count=counts[0]),
              w('member', 'g', unread=True, count=counts[1]), w('read', 'g')],
             [g('g', 'anchor', collapsed=collapsed)])
rng = random.Random(13417)
for i in range(24):
    groups = [g('g', 'a', rng.choice([False, True]), rng.choice([False, True])),
              g('h', 'd', rng.choice([False, True]), rng.choice([False, True])),
              g('empty', pinned=rng.choice([False, True]))]
    rows = [w('a', 'g'), w('b', 'g'), w('c', 'g'), w('d', 'h'), w('e', 'h'), w('f'), w('z')]
    for row in rows:
        row['is_pinned'] = rng.choice([False, True])
        row['has_unread'] = rng.choice([False, True])
        row['unread_count'] = (rng.choice([None, 0, 1, 3, 1000]) if row['has_unread'] else rng.choice([None, 0]))
    if i % 3 == 0:
        rng.shuffle(rows)
    rng.shuffle(groups)
    case(f'spatial-pins-{i}', rows, groups)

STUBS = '''
import Foundation
extension String { var rawValue: String { self } }
public struct MobileWorkspacePreview: Equatable, Sendable {
    public typealias ID = String
    public var id: String
    public var groupID: String?
    public var isPinned: Bool
    public var hasUnread: Bool
    public var unreadCount: Int?
}
public struct MobileWorkspaceGroupPreview: Equatable, Sendable {
    public typealias ID = String
    public var id: String
    public var anchorWorkspaceID: String?
    public var isEmpty: Bool
    public var isPinned: Bool
    public var isCollapsed: Bool
    public var liveAnchorWorkspaceID: String? { isEmpty ? nil : anchorWorkspaceID }
}
'''
MAIN = '''
import Foundation
func optional<T>(_ value: T?) -> Any { value as Any? ?? NSNull() }
func intentJSON(_ intent: MobileWorkspaceMoveIntent?) -> Any {
    guard let intent else { return NSNull() }
    return ["group_id": optional(intent.groupID), "before_workspace_id": optional(intent.beforeWorkspaceID), "move_group": intent.movesGroup]
}
func orderJSON(_ rows: [MobileWorkspacePreview]) -> [[String: Any]] {
    rows.map { ["id": $0.id, "group_id": optional($0.groupID), "is_pinned": $0.isPinned] }
}
let data = FileHandle.standardInput.readDataToEndOfFile()
var cases = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
for index in cases.indices {
    let workspaces = (cases[index]["workspaces"] as! [[String: Any]]).map {
        MobileWorkspacePreview(id: $0["id"] as! String, groupID: $0["group_id"] as? String,
          isPinned: $0["is_pinned"] as! Bool, hasUnread: $0["has_unread"] as! Bool, unreadCount: $0["unread_count"] as? Int)
    }
    let groups = (cases[index]["groups"] as! [[String: Any]]).map {
        MobileWorkspaceGroupPreview(id: $0["id"] as! String, anchorWorkspaceID: $0["anchor_workspace_id"] as? String,
          isEmpty: $0["is_empty"] as! Bool, isPinned: $0["is_pinned"] as! Bool, isCollapsed: $0["is_collapsed"] as! Bool)
    }
    let items = MobileWorkspaceListItem.items(workspaces: workspaces, groups: groups)
    cases[index]["items"] = items.map { item -> [String: Any] in
        switch item {
        case .workspace(let w, let indented): return ["kind": "workspace", "id": w.id, "indented": indented, "has_unread": w.unreadState.isUnread, "unread_count": optional(w.unreadState.count)]
        case .groupHeader(let g, let unread): return ["kind": "group", "id": g.id, "has_unread": unread.isUnread, "unread_count": optional(unread.count),
            "anchor": optional(g.liveAnchorWorkspaceID)]
        case .groupFooter(let id): return ["kind": "footer", "id": id]
        }
    }
    var drops: [[String: Any]] = []
    for from in items.indices {
        for to in -1...items.count + 1 {
            let intent = items.moveIntent(workspaces: workspaces, groups: groups, sourceOffsets: IndexSet(integer: from), destination: to)
            var drop: [String: Any] = ["from": from, "to": to, "intent": intentJSON(intent)]
            if let intent {
                let moved: String
                switch items[from] {
                case .workspace(let w, _): moved = w.id
                case .groupHeader(let g, _): moved = g.liveAnchorWorkspaceID!
                case .groupFooter: fatalError("Footers cannot move")
                }
                drop["moved"] = moved
                drop["order"] = orderJSON(workspaces.applyingWorkspaceMoveIntent(intent, movedWorkspaceID: moved, groups: groups))
            }
            drops.append(drop)
        }
    }
    cases[index]["drops"] = drops
    let policy = MobileWorkspaceMovePolicy(workspaces: workspaces, groups: groups)
    var proposals: [[String: Any]] = []
    for moved in workspaces.map(\\.id) + ["unknown"] {
        for group in [nil] + groups.map({ Optional($0.id) }) + ["unknown"] {
            for before in [nil] + workspaces.map({ Optional($0.id) }) + ["unknown"] {
                for moveGroup in [false, true] {
                    let proposed = MobileWorkspaceMoveIntent(groupID: group, beforeWorkspaceID: before, movesGroup: moveGroup)
                    let normalized = policy.normalizedIntent(proposed, movedWorkspaceID: moved)
                    var entry: [String: Any] = ["moved": moved, "proposed": intentJSON(proposed), "intent": intentJSON(normalized)]
                    if let normalized { entry["order"] = orderJSON(workspaces.applyingWorkspaceMoveIntent(normalized, movedWorkspaceID: moved, groups: groups)) }
                    proposals.append(entry)
                }
            }
        }
    }
    cases[index]["proposals"] = proposals
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: cases, options: [.sortedKeys]))
'''

with tempfile.TemporaryDirectory(prefix='cmux-swift-parity-') as temporary:
    temp = Path(temporary)
    hashes = {}
    paths = []
    for name in FILES:
        relative = str(REL / (name + '.swift'))
        data = subprocess.check_output(['git', '-C', str(UPSTREAM), 'show', f'{PIN}:{relative}'])
        hashes[relative] = hashlib.sha256(data).hexdigest()
        file = temp / (name + '.swift')
        file.write_bytes(data)
        paths.append(str(file))
    (temp / 'Types.swift').write_text(STUBS)
    (temp / 'main.swift').write_text(MAIN)
    executable = temp / 'oracle'
    subprocess.run(['swiftc', '-swift-version', '6', '-o', str(executable), str(temp/'Types.swift'), *paths, str(temp/'main.swift')], check=True)
    output = subprocess.check_output([str(executable)], input=json.dumps(cases).encode())
    result = dict(upstream=PIN, source_sha256=hashes, cases=json.loads(output))
    destination = ROOT / 'app/src/test/resources/workspaces/ios-moves.json.gz'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(gzip.compress((json.dumps(result, separators=(',', ':'), sort_keys=True) + '\n').encode(), mtime=0))
    print(f'Generated {len(cases)} fixtures in {destination} ({destination.stat().st_size} bytes)')
