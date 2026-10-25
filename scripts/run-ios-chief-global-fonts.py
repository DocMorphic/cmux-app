#!/usr/bin/env python3
"""Change only the hosted reference simulator's global text category on native requests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

CATEGORIES = {'large': 'large', 'accessibilityMedium': 'accessibility-medium'}
BUNDLE = 'io.github.docmorphic.cmuxapp.reference.ios'


def atomic_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def validate_request(value, commit):
    if not isinstance(value, dict) or value.get('schema') != 1 or value.get('referenceKind') != 'chiefGlobalFontRequest':
        raise ValueError('Wrong native bridge request')
    if value.get('sourceCommit') != commit or not re.fullmatch('[0-9a-fA-F-]{36}', value.get('id', '')):
        raise ValueError('Unbound native request identity')
    if value.get('target') not in CATEGORIES:
        raise ValueError('Unknown global font category')


def apply_request(value, commit, simulator, execute, driver_sha):
    validate_request(value, commit)
    read = ['xcrun', 'simctl', 'ui', simulator, 'content_size']
    before = execute(read).strip()
    if before not in CATEGORIES.values():
        raise ValueError('Unexpected initial system category')
    command = read + [CATEGORIES[value['target']]]
    started = time.monotonic()
    execute(command)
    after = execute(read).strip()
    if after != CATEGORIES[value['target']]:
        raise ValueError('System category readback differs from request')
    return dict(schema=1, referenceKind='chiefGlobalFontResponse', id=value['id'], target=value['target'],
                sourceCommit=commit, simulator=simulator, command=command, before=before, after=after,
                status='applied', started=started, ended=time.monotonic(), driverSha256=driver_sha)


def run(args):
    if os.environ.get('GITHUB_ACTIONS') != 'true' or not os.environ.get('RUNNER_TEMP'):
        raise ValueError('This driver is limited to the disposable hosted Actions simulator')
    if not re.fullmatch('[0-9a-fA-F-]{36}', args.simulator) or not re.fullmatch('[0-9a-f]{40}', args.source_commit):
        raise ValueError('Explicit simulator and immutable source commit required')
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command or command[0] != 'xcodebuild':
        raise ValueError('Expected the explicit reference xcodebuild command')
    def execute(values):
        return subprocess.check_output(values, text=True, stderr=subprocess.PIPE, timeout=15)
    devices = json.loads(execute(['xcrun', 'simctl', 'list', 'devices', 'available', '-j']))
    selected = [d for rows in devices['devices'].values() for d in rows if d['udid'] == args.simulator]
    if len(selected) != 1 or not selected[0].get('isAvailable') or selected[0]['state'] != 'Booted':
        raise ValueError('Reference simulator must be available and booted')
    read = ['xcrun', 'simctl', 'ui', args.simulator, 'content_size']
    original = execute(read).strip()
    if original != 'large':
        raise ValueError('The global font probe starts at actual system Large')
    receipt = dict(schema=1, sourceCommit=args.source_commit, simulator=args.simulator, initialCategory=original,
                   driverSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), events=[], restored=False)
    args.events.parent.mkdir(parents=True, exist_ok=True)
    args.log.parent.mkdir(parents=True, exist_ok=True)
    atomic_json(args.events, receipt)
    container = None; seen = set(); process = None; started = time.monotonic()
    try:
        with args.log.open('wb') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            while process.poll() is None:
                if time.monotonic() - started > 28 * 60:
                    raise TimeoutError('Hosted global-font reference deadline')
                if container is None:
                    try:
                        container = Path(execute(['xcrun', 'simctl', 'get_app_container', args.simulator, BUNDLE, 'data']).strip())
                    except subprocess.CalledProcessError:
                        time.sleep(.5); continue
                bridge = container / 'Documents/ChiefGlobalFontBridge'
                request = bridge / 'request.json'
                if request.exists():
                    value = json.loads(request.read_text())
                    validate_request(value, args.source_commit)
                    if value['id'] not in seen:
                        event = apply_request(value, args.source_commit, args.simulator, execute, receipt['driverSha256'])
                        receipt['events'].append(event); atomic_json(args.events, receipt)
                        atomic_json(bridge / 'response.json', event); seen.add(value['id'])
                time.sleep(.2)
            receipt['xcodebuildExitCode'] = process.returncode
            if process.returncode != 0:
                raise RuntimeError('Reference xcodebuild failed: ' + str(process.returncode))
            if not receipt['events']:
                raise ValueError('No actual global font requests observed')
    finally:
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try: process.wait(10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL); process.wait(10)
        execute(read + [original]); receipt['restoredCategory'] = execute(read).strip()
        receipt['restored'] = receipt['restoredCategory'] == original
        atomic_json(args.events, receipt)
        if not receipt['restored']: raise ValueError('Global category restoration failed')
    print('Native global font requests:', len(receipt['events']), '; system category restored')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--simulator', required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--events', type=Path, required=True)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    run(parser.parse_args())
