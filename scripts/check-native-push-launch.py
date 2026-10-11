#!/usr/bin/env python3
"""Execute the actual patched tagged-launch environment without starting an app."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location('native_patch', root / 'scripts/apply-native-push-patch.py')
    patch = importlib.util.module_from_spec(spec); spec.loader.exec_module(patch)
    def source(path):
        return subprocess.check_output(['git', '-C', str(args.upstream), 'show', f'{patch.REF}:{path}'])
    original = {path: source(path) for path in patch.PINS}
    reload_path = 'scripts/reload.sh'
    original.setdefault(reload_path, source(reload_path))
    reload = patch.patched_sources(original).get(reload_path, original[reload_path]).decode()
    start = reload.index('  TAG_LAUNCH_ENV=(\n')
    end = reload.index('  if [[ -n "$AUTH_CREDENTIALS_FILE" ]]', start)
    fragment = reload[start:end]
    # Inert fixture values for existing upstream launch inputs; subprocess gets
    # no inherited auth or owner state. Only the selected three paths are emitted.
    variables = set(re.findall(r'\$\{?([A-Z][A-Z0-9_]*)', fragment))
    prelude = '\n'.join(f'{name}=""' for name in sorted(variables) if not name.startswith('CMUX_ANDROID_PUSH_'))
    check = "import os,json; print(json.dumps({k:os.environ.get(k) for k in ['CMUX_ANDROID_PUSH_CONFIG','CMUX_ANDROID_PUSH_NODE','CMUX_ANDROID_PUSH_HELPER']}))"
    script = 'set -eu\n' + prelude + '\nPROD_AUTH=1\n' + fragment + '\n/usr/bin/env -i "${TAG_LAUNCH_ENV[@]}" /usr/bin/python3 -c ' + repr(check) + '\n'
    valid = {'CMUX_ANDROID_PUSH_CONFIG': '/private/fixture/config.json', 'CMUX_ANDROID_PUSH_NODE': '/private/fixture/node',
             'CMUX_ANDROID_PUSH_HELPER': '/private/fixture/helper.mjs'}
    def run(owner):
        return subprocess.run(['/bin/bash', '-c', script], env={'PATH': '/usr/bin:/bin', **owner}, capture_output=True, text=True, timeout=5)
    result = run(valid)
    assert result.returncode == 0, 'Complete owner configuration must reach tagged launch'
    assert json.loads(result.stdout) == valid, 'Tagged env -i launch drops explicitly selected native push owner paths'
    disabled = run({})
    assert disabled.returncode == 0 and all(v is None for v in json.loads(disabled.stdout).values())
    for name in valid:
        partial = {k: v for k, v in valid.items() if k != name}
        assert run(partial).returncode != 0, 'Partial owner configuration must refuse launch'
        assert run({**valid, name: 'relative/path'}).returncode != 0, 'Relative owner paths must refuse launch'
    print('8 tagged-launch environment cases passed; no app/helper launched')


if __name__ == '__main__':
    main()
