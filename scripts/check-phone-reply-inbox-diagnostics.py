#!/usr/bin/env python3
"""Execute the exact patched inbox client with inert auth/service/transport boundaries."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('patch', root / 'scripts/apply-native-push-patch.py')
    patch = importlib.util.module_from_spec(spec); spec.loader.exec_module(patch)
    originals = {path: subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show',
                 patch.REF + ':' + path]) for path in patch.PINS}
    source = patch.patched_sources(originals)['Sources/Cloud/PhoneReplyInboxClient.swift']
    client = output / 'PhoneReplyInboxClient.swift'; client.write_bytes(source)
    # The pinned full app target uses SWIFT_VERSION=5.0. Do not silently turn
    # this diagnostic check into a Swift 6 migration of upstream async owners.
    compiler = ['/usr/bin/swiftc', '-swift-version', '5', '-D', 'DEBUG']

    def run(name, command, timeout=90):
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        (output / (name + '.log')).write_bytes(result.stdout + result.stderr)
        result.check_returncode()
        return result.stdout.decode()

    # No real auth, storage, network, crypto or user defaults are loaded. The
    # unchanged decoder/request methods execute against URLProtocol responses.
    modules = {
        'CmuxCloud': '''import Foundation
public actor CmxRetryAfterGate {
    public init() {}
    public func wait() throws { try Task.checkCancellation() }
    public func extend(by seconds: TimeInterval) {}
}
public struct CmxRetryAfterPolicy {
    public init() {}
    public var defaultRateLimitSeconds: TimeInterval { 30 }
    public func seconds(from response: HTTPURLResponse, defaultSeconds: TimeInterval) -> TimeInterval? { defaultSeconds }
}
''',
        'CmuxAuthRuntime': '''import Foundation
@MainActor public final class AuthCoordinator {
    public struct Identity { public let accountID = "fixture-account" }
    public var authenticatedSessionIdentity: Identity? { Identity() }
    public var fails = false
    public init() {}
    public func currentTokens() async throws -> (accessToken: String, refreshToken: String) {
        if fails { throw URLError(.userAuthenticationRequired) }
        return ("fixture-secret-access", "fixture-secret-refresh")
    }
}
''',
        'CmuxPhonePush': '''import Foundation
public struct PhonePushEncryptedPayload: Decodable, Equatable, Sendable { public let marker: String }
''',
        'CMUXMobileCore': 'import Foundation\n',
    }
    for name, contents in modules.items():
        file = output / (name + '.swift'); file.write_text(contents)
        run('module-' + name, compiler + ['-parse-as-library', '-emit-module', '-emit-library',
            '-module-name', name, str(file), '-emit-module-path', str(output / (name + '.swiftmodule')),
            '-o', str(output / ('lib' + name + '.dylib'))])
    fixture = root / 'scripts/phone-reply-inbox-diagnostics-fixture.swift'
    binary = output / 'inbox-fixture'
    run('build', compiler + ['-I', str(output), '-L', str(output),
        *['-l' + name for name in modules], '-Xlinker', '-rpath', '-Xlinker', str(output),
        str(client), str(fixture), '-o', str(binary)])
    cases = ['auth-missing', 'token-fail', 'service-missing', 'http-401', 'http-403',
             'http-429', 'http-503', 'non-http', 'malformed-json', 'plaintext-rejected',
             'transport-timeout', 'transport-cancelled', 'empty', 'encrypted',
             'ack-empty', 'ack-success', 'ack-http-403', 'ack-transport-timeout']
    for case in cases:
        print(run(case, [str(binary), case], timeout=15).strip())
    receipt = {'upstream': patch.REF, 'patchedClientSha256': hashlib.sha256(source).hexdigest(),
        'fixtureSha256': hashlib.sha256(fixture.read_bytes()).hexdigest(), 'cases': cases,
        'scope': 'Exact patched client typechecked/executed with inert auth/service/URLProtocol/crypto DTO boundaries; not real Mac account/server retrieval or terminal reply acceptance.'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()
