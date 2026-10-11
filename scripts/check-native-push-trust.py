#!/usr/bin/env python3
"""Compile the audited native peer store and execute the actual injected trust hook."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('captures/runtime/push-native-trust/native'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('native_patch', root / 'scripts/apply-native-push-patch.py')
    patch = importlib.util.module_from_spec(spec); spec.loader.exec_module(patch)
    def source(path):
        return subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', f'{patch.REF}:{path}'])
    sources = []; hashes = {}
    for name in ['PhonePushCrypto.swift', 'PhonePushSharedStateStorage.swift', 'PhonePushPayload.swift', 'PhonePushPayloadKind.swift']:
        data = source('Packages/macOS/CmuxPhonePush/Sources/CmuxPhonePush/' + name)
        path = output / name; path.write_bytes(data); sources.append(str(path)); hashes[name] = hashlib.sha256(data).hexdigest()
    def run(name, command, timeout):
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        (output / name).write_bytes(result.stdout + result.stderr); result.check_returncode(); return result.stdout
    library = output / 'libCmuxPhonePush.dylib'
    run('module-build.log', ['/usr/bin/swiftc', '-swift-version', '6', '-emit-module', '-emit-library',
        '-module-name', 'CmuxPhonePush', *sources, '-o', str(library)], 90)
    original = {path: source(path) for path in patch.PINS}
    helpers = patch.patched_sources(original)['Sources/Cloud/PhonePushClient.swift'].decode()
    start = helpers.index('    func refreshAndroidPushTrust()')
    end = helpers.index('    private func publishAndroidPushHost()', start)
    fixture = (root / 'scripts/native-push-trust-fixture.swift').read_text().replace('    // ACTUAL_NATIVE_TRUST_HOOK', helpers[start:end])
    fixture_path = output / 'fixture.swift'; fixture_path.write_text(fixture)
    binary = output / 'trust-fixture'
    run('fixture-build.log', ['/usr/bin/swiftc', '-swift-version', '6', '-I', str(output), '-L', str(output),
        '-lCmuxPhonePush', '-Xlinker', '-rpath', '-Xlinker', str(output),
        str(root / 'push/mac-native/NativePushSourceMessages.swift'), str(root / 'push/NativePushSourceFrame.swift'),
        str(root / 'push/mac-native/NativePushSourceDeliveryQueue.swift'), str(fixture_path), '-o', str(binary)], 90)
    result = run('behavior.log', [str(binary)], 20)
    receipt = {'ref': patch.REF, 'auditedSources': hashes, 'kind': 'native-peer-trust-hook-passed',
        'scope': 'Actual upstream peer store, actual injected hook and mapper compiled/executed with in-memory public fixture pins; no real Keychain, account, full app or provider.'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(result.decode().strip())


if __name__ == '__main__':
    main()
