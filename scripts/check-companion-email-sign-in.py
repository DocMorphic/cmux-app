#!/usr/bin/env python3
"""Compile the production form state machine and check the exact isolated UI wiring."""
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
    parser.add_argument('--model-only', action='store_true', help='Local CLT fallback when SwiftUI macro plugins are unavailable; CI must typecheck the UI')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('native_patch', root / 'scripts/apply-native-push-patch.py')
    patch = importlib.util.module_from_spec(spec); spec.loader.exec_module(patch)
    originals = {path: subprocess.check_output(['git', '-C', str(args.upstream), 'show', f'{patch.REF}:{path}']) for path in patch.PINS}
    patched = patch.patched_sources(originals)
    original = originals['Sources/Auth/AccountSignInView.swift'].decode()
    actual = patched['Sources/Auth/AccountSignInView.swift'].decode()
    browser_body = original.split('    var body: some View {\n', 1)[1]
    if actual.split('    private var hostedBrowserBody: some View {\n', 1)[1] != browser_body:
        raise ValueError('Existing hosted sign-in behavior changed')
    if 'Bundle.main.bundleIdentifier == "com.cmuxterm.app.debug.android.push"' not in actual:
        raise ValueError('Companion sign-in is not isolated')
    workspace = patched['Sources/AppDelegate+AccountSignInWorkspace.swift'].decode()
    host = patched['Sources/Auth/HostAccountFlow.swift'].decode()
    if workspace.count('Bundle.main.bundleIdentifier != "com.cmuxterm.app.debug.android.push"') != 2:
        raise ValueError('Workspace still starts an unsupported browser callback')
    if host.count('Bundle.main.bundleIdentifier == "com.cmuxterm.app.debug.android.push"') != 3:
        raise ValueError('Host sign-in/switch entrypoints are not isolated')
    view = root / 'push/mac-native/NativeCompanionEmailSignInView.swift'
    model = root / 'push/mac-native/NativeCompanionEmailSignInModel.swift'
    for anchor in ('auth.coordinator.sendCode(to: $0)', 'auth.coordinator.verifyCode($0)',
                   'auth.coordinator.isAuthenticated', 'auth.accountFlow.currentIdentity'):
        if anchor not in view.read_text(): raise ValueError('Auth coordinator wiring changed')
    for name in ('NativeCompanionEmailSignInModel', 'NativeCompanionEmailSignInView'):
        project = patched['cmux.xcodeproj/project.pbxproj'].decode()
        if project.count(f'{name}.swift in Sources */ =') != 1 or project.count(f'{name}.swift in Sources */,') != 1:
            raise ValueError('Companion source build membership changed')
    parsed = output / 'AccountSignInView.swift'; parsed.write_text(actual)
    parse = subprocess.run(['/usr/bin/swiftc', '-frontend', '-parse', str(parsed), str(view)], capture_output=True, timeout=30)
    (output / 'parse.log').write_bytes(parse.stdout + parse.stderr); parse.check_returncode()
    if not args.model_only:
        typed = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', '-target', 'arm64-apple-macos14.0',
            '-typecheck', str(model), str(view), str(root / 'scripts/companion-email-sign-in-ui-types.swift')],
            capture_output=True, timeout=90)
        (output / 'ui-typecheck.log').write_bytes(typed.stdout + typed.stderr); typed.check_returncode()
    binary = output / 'fixture'
    build = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', str(model),
        str(root / 'scripts/companion-email-sign-in-fixture.swift'), '-o', str(binary)], capture_output=True, timeout=90)
    (output / 'build.log').write_bytes(build.stdout + build.stderr); build.check_returncode()
    result = subprocess.run([str(binary)], capture_output=True, timeout=15)
    (output / 'behavior.log').write_bytes(result.stdout + result.stderr); result.check_returncode()
    receipt = {'upstream': patch.REF, 'patchFiles': len(patched), 'pins': len(patch.PINS), 'uiTypechecked': not args.model_only,
        'sourceHashes': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (view, model)},
        'scope': 'Production form state machine compiled and executed; isolated host UI wiring and syntax checked. UI interface-fixture typecheck only when uiTypechecked is true. No linked app, real coordinator/server, persistence, Mac/Pixel pairing or FCM acceptance.'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(result.stdout.decode().strip())

if __name__ == '__main__': main()
