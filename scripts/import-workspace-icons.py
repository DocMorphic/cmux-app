#!/usr/bin/env python3
"""Import pinned Lucide workspace symbols as Android vector drawables (no runtime font dependency)."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

PIN = '66d8f9fc394b8530377e5f6112f0b8908ba01280'
ROOT = Path(__file__).resolve().parents[1]
NAMES = ['chevron-down', 'chevron-right', 'folder', 'pin', 'terminal', 'hammer', 'wrench', 'globe', 'zap', 'test-tubes',
         'bug', 'file-text', 'package', 'star', 'heart', 'bookmark', 'tag', 'briefcase',
         'house', 'settings', 'users', 'cpu', 'network', 'server', 'cloud', 'lock']
SOURCE = ROOT / 'third_party/lucide/workspace-icons'
DRAWABLE = ROOT / 'app/src/main/res/drawable'
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)

def fetch(name):
    relative = 'LICENSE' if name == 'LICENSE' else f'icons/{name}.svg'
    url = f'https://raw.githubusercontent.com/lucide-icons/lucide/{PIN}/{relative}'
    return name, subprocess.check_output(['curl', '--fail', '--silent', '--show-error', '--max-time', '30', url])

def number(x):
    return f'{x:g}'

def path(element):
    kind = element.tag.rsplit('}', 1)[-1]
    a = element.attrib
    assert 'transform' not in a, 'Unimplemented SVG transform'
    if kind == 'path': return a['d']
    if kind == 'line': return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if kind in ['polyline', 'polygon']:
        points = a['points'].replace(',', ' ').split()
        return 'M' + ' L'.join(' '.join(points[i:i+2]) for i in range(0, len(points), 2)) + ('Z' if kind == 'polygon' else '')
    if kind == 'circle':
        x, y, r = (float(a[k]) for k in ['cx', 'cy', 'r'])
        return f'M{number(x-r)} {number(y)}a{number(r)} {number(r)} 0 1 0 {number(2*r)} 0a{number(r)} {number(r)} 0 1 0 {number(-2*r)} 0Z'
    if kind == 'rect':
        x, y, w, h = (float(a.get(k, 0)) for k in ['x', 'y', 'width', 'height'])
        r = min(float(a.get('rx', 0)), w/2, h/2)
        return f'M{x+r} {y}H{x+w-r}Q{x+w} {y} {x+w} {y+r}V{y+h-r}Q{x+w} {y+h} {x+w-r} {y+h}H{x+r}Q{x} {y+h} {x} {y+h-r}V{y+r}Q{x} {y} {x+r} {y}Z'
    raise ValueError(f'Unsupported SVG node: {kind}')

SOURCE.mkdir(parents=True, exist_ok=True)
hashes = {}
with ThreadPoolExecutor(max_workers=6) as pool:
    for name, data in pool.map(fetch, NAMES + ['LICENSE']):
        filename = 'LICENSE' if name == 'LICENSE' else f'{name}.svg'
        (SOURCE / filename).write_bytes(data)
        hashes[filename] = hashlib.sha256(data).hexdigest()
        if name == 'LICENSE':
            (ROOT / 'app/src/main/assets/licenses/Lucide.txt').write_bytes(data)
            continue
        svg = ET.fromstring(data)
        assert svg.attrib['viewBox'] == '0 0 24 24'
        variants = [False, True] if name in ['folder', 'pin'] else [False]
        for fill in variants:
            vector = ET.Element('vector', {f'{{{ANDROID}}}{k}': v for k,v in dict(width='24dp', height='24dp', viewportWidth='24', viewportHeight='24').items()})
            for element in svg:
                d = path(element)
                attrs = dict(pathData=d, fillColor='#FFFFFFFF' if fill and d.rstrip().lower().endswith('z') else '#00000000',
                    strokeColor='#FFFFFFFF', strokeWidth='2', strokeLineCap='round', strokeLineJoin='round')
                ET.SubElement(vector, 'path', {f'{{{ANDROID}}}{k}':v for k,v in attrs.items()})
            ET.indent(vector)
            label = name.replace('-', '_') + ('_fill' if fill else '')
            (DRAWABLE / f'ic_workspace_{label}.xml').write_text('<!-- Lucide; pinned source and ISC/MIT notices in third_party/lucide/workspace-icons. -->\n'+ET.tostring(vector, encoding='unicode')+'\n')
(SOURCE / 'PROVENANCE.json').write_text(json.dumps(dict(repository='https://github.com/lucide-icons/lucide', revision=PIN, sha256=hashes,
    changes='Converted SVG geometry to Android VectorDrawable; folder/pin fill variants fill closed paths.'), indent=2, sort_keys=True)+'\n')
print(f'Imported {len(NAMES)} symbols and complete license notices at {PIN}')
