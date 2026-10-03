#!/usr/bin/env python3
"""Copy the exact Highlightr assets pinned by cmux's iOS Package.resolved."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

CMUX_PIN = '4c5272e9153eca2033c9f40ac749f0c3a5bcb291'
PIN = '05e7fcc63b33925cd0c1faaa205cdd5681e7bbef'
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/raw-code'
FILES = ['src/assets/highlighter/highlight.min.js', 'src/assets/styles/xcode-dark.min.css', 'src/assets/styles/xcode.min.css']
ASSETS.mkdir(parents=True, exist_ok=True)

def read(path):
    return subprocess.check_output(['git', '-C', sys.argv[1], 'show', f'{PIN}:{path}'])

entries = []
for path in FILES:
    data = read(path)
    name = Path(path).name
    (ASSETS / name).write_bytes(data)
    entries.append(dict(path=path, asset=name, sha256=hashlib.sha256(data).hexdigest()))
(ASSETS / 'manifest.json').write_text(json.dumps(dict(cmux=CMUX_PIN, highlightr=PIN, highlightrVersion='2.3.0', highlightJsVersion='11.11.1', files=entries), indent=2)+'\n')
license_text = f'''Raw-code highlighting assets

Highlightr 2.3.0 ({PIN}), the version pinned by cmux iOS at {CMUX_PIN}.
The complete highlight.js 11.11.1 bundle and the Xcode styles are copied without
modification. There are 192 bundled languages, including Haskell (also used for
PureScript by cmux). Source: https://github.com/raspu/Highlightr

Highlightr license:

'''+read('LICENSE').decode()+'\n\nhighlight.js license:\n\n'+read('src/assets/highlighter/LICENSE').decode()
(ROOT / 'app/src/main/assets/licenses/RawCode.txt').write_text(license_text)
print(f'Copied {len(entries)} pinned raw-code assets')
