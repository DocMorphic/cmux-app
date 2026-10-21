#!/usr/bin/env python3
"""Compile/execute the actual blocking lane and patched host key-exchange handler with inert external boundaries."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess


def declaration(source, signature):
    start = source.index(signature); opening = source.index('{', start)
    depth = 1; end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}'); end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('patch', root / 'scripts/apply-native-push-patch.py')
    patch = importlib.util.module_from_spec(spec); spec.loader.exec_module(patch)
    reference_pins = {
        'Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/MobilePhonePushKeyExchange.swift': 'aa25be746f0fc0c9f2f9fd43e0033893ba46ffb1af5bbecd48b9c3c5fac19452',
        'Sources/Mobile/MobileHostRPC.swift': '850467826906c7b32d5d8a9daa5bcaafe005bd9b2a1022db92946417a7ee807b',
        'Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator+Tokens.swift': '5dbaf5875603f6ce4c27e56685954403c5c7ef2c0443346fe8a587d7ab92824a',
        'Sources/Mobile/MobileHostService.swift': '981e2a4c8d963337904793066b6cca4539627873b0d76ce2bcc51655cd7ded14',
    }
    def source(path):
        raw = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', patch.REF + ':' + path])
        expected = reference_pins.get(path, patch.PINS.get(path))
        if expected and hashlib.sha256(raw).hexdigest() != expected: raise ValueError('Unreviewed key-exchange reference: ' + path)
        return raw
    def run(name, command, timeout=90):
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        (output / (name + '.log')).write_bytes(result.stdout + result.stderr)
        result.check_returncode(); return result.stdout.decode()
    patched = patch.patched_sources({path: source(path) for path in patch.PINS})
    handler_path = 'Sources/Mobile/MobileHostService+PhonePushKeyExchange.swift'
    handler = output / 'PatchedKeyExchange.swift'; handler.write_bytes(patched[handler_path])
    wire_path = 'Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/MobilePhonePushKeyExchange.swift'
    wire = output / 'Wire.swift'; wire.write_bytes(source(wire_path))
    rpc_path = 'Sources/Mobile/MobileHostRPC.swift'
    rpc = output / 'RPC.swift'; rpc.write_bytes(source(rpc_path))
    auth_path = 'Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator+Tokens.swift'
    tokens = source(auth_path).decode()
    identity = output / 'Identity.swift'
    identity.write_text('import Foundation\n' + tokens[tokens.index('public struct AuthenticatedSessionIdentity:'):tokens.index('public extension AuthCoordinator {', tokens.index('public struct AuthenticatedSessionIdentity:'))])
    host_path = 'Sources/Mobile/MobileHostService.swift'
    host = output / 'Host.swift'
    host_source = patched[host_path].decode()
    host.write_text('import Foundation\n@MainActor final class MobileHostService {\nprivate var auth: AuthCoordinator?\ninit(auth: AuthCoordinator) { self.auth = auth }\nfunc fixtureReplaceAuth(_ value: AuthCoordinator) { auth = value }\n' +
        '\n'.join(declaration(host_source, signature) for signature in [
            'func phonePushKeyExchangeSession()', 'func isPhonePushKeyExchangeSessionCurrent(',
            'func currentAuthenticatedLocalUserID()']) + '\n}\n')
    cloud = output / 'Cloud.swift'; cloud.write_text('import Foundation\n')
    compiler = ['/usr/bin/swiftc', '-swift-version', '6']
    for module, file in [('CMUXMobileCore', wire), ('CmuxCloud', cloud),
                         ('CmuxPhonePush', root / 'scripts/native-push-keychain-module-fixture.swift')]:
        run('module-' + module, compiler + ['-parse-as-library', '-emit-module', '-emit-library', '-module-name', module,
            str(file), '-emit-module-path', str(output / (module + '.swiftmodule')), '-o', str(output / ('lib' + module + '.dylib'))])
    lane = root / 'push/mac-native/NativePushKeychainReadLane.swift'
    access = root / 'push/mac-native/NativePushKeyMaterialAccess.swift'
    lane_binary = output / 'lane-fixture'
    run('lane-build', compiler + [str(lane), str(root / 'scripts/native-push-keychain-lane-fixture.swift'), '-o', str(lane_binary)])
    print(run('lane-behavior', [str(lane_binary)], timeout=10).strip())
    binary = output / 'key-exchange-fixture'
    run('handler-build', compiler + ['-I', str(output), '-L', str(output), '-lCMUXMobileCore', '-lCmuxCloud', '-lCmuxPhonePush',
        '-Xlinker', '-rpath', '-Xlinker', str(output), str(lane), str(access), str(handler), str(rpc), str(identity), str(host),
        str(root / 'scripts/native-push-key-exchange-fixture.swift'), '-o', str(binary)])
    cases = ['valid', 'signed-out', 'malformed', 'account-change', 'same-account-reentry', 'coordinator-replaced', 'bootstrap-owner-replaced', 'bootstrap-cancelled', 'cancelled', 'deadline', 'keychain-error']
    for case in cases: print(run('handler-' + case, [str(binary), case], timeout=10).strip())
    receipt = {'revision': patch.REF, 'handlerSha256': hashlib.sha256(handler.read_bytes()).hexdigest(), 'cases': cases,
        'referenceSources': {path: hashlib.sha256(source(path)).hexdigest() for path in [handler_path, wire_path, rpc_path, auth_path, host_path]},
        'implementationSources': {str(file.relative_to(root)): hashlib.sha256(file.read_bytes()).hexdigest() for file in [lane, access]},
        'scope': 'Actual Swift 6 lane and actual patched host handler; actual wire/RPC/identity declarations and host-owned bootstrap/session helpers. Inert Keychain/auth/peer-store dependencies. No real Keychain access, access-control changes, identity rotation, live Mac/phone or full app acceptance.'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__': main()
