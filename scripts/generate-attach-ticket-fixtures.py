#!/usr/bin/env python3
"""Generate synthetic pairing fixtures using pinned upstream Swift wire declarations."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

PIN = '186cec79781256867ad4516f0802118738bd2393'
CORE = 'Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/'
INPUT = 'Packages/iOS/CmuxMobileRPC/Sources/CmuxMobileRPC/CmxAttachTicketInput.swift'
NAMES = '''CmxTransport MobileSyncProtocol CmxAttachTicketCompactCoder
CmxAttachTicketCompactCoderError CompactAttachTicket CompactAttachRoute CompactAttachEndpoint
CmxPairingRouteDisclosureMode CmxPairingQRCode CmxPairingURLScheme CmxPairingURLSchemeResolver
CmxDeviceIDCanonicalization CmxIrohPeerIdentity CmxIrohPeerIdentityError CmxIrohPathHint
CmxIrohPathHintError CmxIrohPathHintKind CmxIrohPathHintSource CmxIrohPathHintPrivacyScope
CmxIrohPathHintUse CmxIrohPathHint+AddressValidation CmxIrohNetworkProfileKey
CmxIrohNetworkProfileKeyError CmxIrohRelayOrigin CmxLoopbackHost CmxTailscalePeerAddress
MobileIOSAppNamespace MobileIOSBuildScope MobileIOSLegacyBackupScope CmxAttachRouteDisclosure
CmxIrohDialPlan CmxIrohTransportPolicy'''.split()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('app/src/test/resources/pairing/attach-tickets.json'))
    args = parser.parse_args()
    hashes = {}
    with tempfile.TemporaryDirectory(prefix='cmux-attach-golden-') as folder:
        tmp = Path(folder)
        core_sources = []
        for path in [*(CORE + name + '.swift' for name in NAMES), INPUT]:
            original = subprocess.check_output(['git', '-C', str(args.source), 'show', PIN + ':' + path])
            compiled = original
            # Keep every wire declaration byte unchanged; omit unrelated transport protocols.
            if path == CORE + 'CmxTransport.swift':
                compiled, separator, _ = original.partition(b'public protocol CmxByteTransport:')
                if not separator:
                    raise RuntimeError('Pinned transport declaration boundary is missing')
            hashes[path] = {'original_sha256': hashlib.sha256(original).hexdigest(),
                            'compiled_sha256': hashlib.sha256(compiled).hexdigest()}
            target = tmp / Path(path).name
            target.write_bytes(compiled)
            if path != INPUT:
                core_sources.append(str(target))
        subprocess.run(['xcrun', 'swiftc', '-emit-library', '-emit-module', '-module-name', 'CMUXMobileCore',
                        *core_sources, '-o', str(tmp / 'libCMUXMobileCore.dylib')], check=True, cwd=tmp)
        runner = Path(__file__).with_name('attach-ticket-fixture.swift').read_bytes()
        (tmp / 'main.swift').write_bytes(runner)
        subprocess.run(['xcrun', 'swiftc', '-I', str(tmp), '-L', str(tmp), '-lCMUXMobileCore',
                        '-Xlinker', '-rpath', '-Xlinker', str(tmp),
                        str(tmp / Path(INPUT).name), str(tmp / 'main.swift'), '-o', str(tmp / 'export')], check=True)
        fixtures = json.loads(subprocess.check_output([str(tmp / 'export')]))
    result = {'upstream': PIN, 'source_sha256': hashes,
              'runner_sha256': hashlib.sha256(runner).hexdigest(),
              'extraction': 'CmxTransport.swift prefix before public protocol CmxByteTransport:; all other sources unchanged',
              'fixtures': fixtures}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'Exported {len(fixtures)} Swift attach-ticket fixtures to {args.output}')


if __name__ == '__main__':
    main()
