#!/usr/bin/env python3
"""Emulator-only real Save worker process-death check, against installed debug/test APKs.

Deliberately kills the debug app's main process. Preserves account data. Requires
no pending file saves; only generated fixture bytes/receipts are removed.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import uuid

PACKAGE = 'io.github.docmorphic.cmuxapp.debug'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
CLASS = 'io.github.docmorphic.cmuxapp.FileSaveWorkerProcessTest'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('This process-death fixture only accepts an emulator serial')
    adb = [args.adb, '-s', args.serial]

    def shell(*command):
        return subprocess.run(adb + ['shell', *command], capture_output=True, text=True, timeout=15)

    if not any(shell('getprop', key).stdout.strip() == '1' for key in ['ro.kernel.qemu', 'ro.boot.qemu']):
        parser.error('Device does not report itself as an emulator')
    if shell('run-as', PACKAGE, 'true').returncode:
        parser.error('Installed debug app is unavailable to run-as')
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = str(uuid.uuid4())
    directory = f'files/file-save-worker-{fixture}'
    (args.output / 'fixture.json').write_text(json.dumps({'fixture': fixture, 'serial': args.serial}) + '\n')

    def phase(name, method):
        print(f'Running {name} phase for fixture {fixture}', flush=True)
        result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', CLASS + '#' + method, '-e', 'fileSaveWorkerPhase', name,
            '-e', 'fileSaveWorkerFixture', fixture, RUNNER], capture_output=True, text=True, timeout=180)
        (args.output / f'{name}.txt').write_text(result.stdout + result.stderr)
        return result

    def read(name):
        result = shell('run-as', PACKAGE, 'cat', directory + '/' + name)
        if result.returncode:
            raise RuntimeError(f'Missing {name}; inspect phase logs before running again')
        value = json.loads(result.stdout)
        (args.output / name).write_text(json.dumps(value, indent=2) + '\n')
        return value

    # A timeout does not prove the device-side instrumentation ended. Do not start
    # another phase or cleanup against a possibly live timed-out run.
    phase('interrupt', 'interruptRealWorker')
    interrupted = read('interrupted.json')
    if interrupted.get('worker_phase') != 'WRITING' or not 0 < interrupted['bytes'] < 32 * 1024 * 1024:
        raise RuntimeError('Interruption did not happen during a partial real worker write')
    if str(interrupted['caller_pid']) in shell('pidof', PACKAGE).stdout.split():
        raise RuntimeError('Original worker process is still alive; do not restart the fixture')
    verified = phase('verify', 'verifyRecoveredWorker')
    receipt = read('verified.json')
    if verified.returncode or 'OK (1 test)' not in verified.stdout or receipt.get('phase') != 'COMPLETED':
        raise RuntimeError('Worker recovery was not verified; retained fixture requires inspection')
    cleaned = phase('cleanup', 'cleanFixture')
    if cleaned.returncode or 'OK (1 test)' not in cleaned.stdout:
        raise RuntimeError('Recovery passed but generated fixture cleanup did not')
    for path in [directory, f'no_backup/file-saves/{fixture}']:
        if shell('run-as', PACKAGE, 'test', '!', '-d', path).returncode:
            raise RuntimeError('Generated fixture bytes remain after cleanup')
    print(json.dumps(receipt, indent=2), flush=True)


if __name__ == '__main__':
    main()
