#!/usr/bin/env python3
"""Package only the tagged custom app; remove runner paths and verify signatures."""
import argparse
import hashlib
import importlib.util
import json
import plistlib
import subprocess
from pathlib import Path


BUNDLE_ID = 'com.cmuxterm.app.debug.android-push'
UPSTREAM = 'f4b1509054949eaad5d695569ad443c4c18ed68d'
BUILD_PATH_KEYS = {'CMUXTERM_REPO_ROOT', 'CMUX_BUNDLED_CLI_PATH', 'CMUX_SHELL_INTEGRATION_DIR'}


def relocated_plist(info):
    if info.get('CFBundleIdentifier') != BUNDLE_ID:
        raise ValueError('Only the isolated Android push bundle may be packaged')
    environment = info.get('LSEnvironment', {})
    expected = {'CMUX_AUTH_ENVIRONMENT': 'production', 'CMUX_API_BASE_URL': 'https://cmux.com',
                'CMUX_IROH_BROKER_BASE_URL': 'https://cmux.com', 'CMUX_IROH_V2_ENVIRONMENT': 'production',
                'CMUX_IROH_V2_BASE_URL': 'https://cmux-iroh-v2.debussy.workers.dev'}
    if not isinstance(environment, dict) or any(environment.get(k) != v for k, v in expected.items()):
        raise ValueError('Tagged app does not use the verified production account/transport routes')
    forbidden = {'CMUX_AUTH_CREDENTIALS_FILE', 'CMUX_DEV_AUTH_PROFILE', 'CMUX_DEV_AUTH_REPLACE_SESSION',
                 'CMUX_ANDROID_PUSH_CONFIG', 'CMUX_ANDROID_PUSH_NODE', 'CMUX_ANDROID_PUSH_HELPER'}
    if forbidden & environment.keys():
        raise ValueError('A distributable development bundle must not bake account or private helper paths')
    environment = {k: v for k, v in environment.items() if k not in BUILD_PATH_KEYS}
    if any(isinstance(v, str) and ('/Users/runner/' in v or '/home/runner/' in v) for v in environment.values()):
        raise ValueError('Unreviewed runner path remains in the launch environment')
    return {**info, 'LSEnvironment': environment}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    app, upstream, output = args.app.resolve(), args.upstream.resolve(), args.output.resolve()
    if subprocess.check_output(['git', '-C', str(upstream), 'rev-parse', 'HEAD'], text=True).strip() != UPSTREAM:
        raise ValueError('Unreviewed upstream source')
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location('native_patch', root / 'scripts/apply-native-push-patch.py')
    patch = importlib.util.module_from_spec(spec); spec.loader.exec_module(patch)
    originals = {path: subprocess.check_output(['git', '-C', str(upstream), 'show', f'{UPSTREAM}:{path}']) for path in patch.PINS}
    expected = patch.patched_sources(originals)
    if any((upstream / path).read_bytes() != data for path, data in expected.items()):
        raise ValueError('Built checkout differs from the reviewed native patch')
    info = plistlib.loads((app / 'Contents/Info.plist').read_bytes())
    updated = relocated_plist(info)
    executable = info.get('CFBundleExecutable')
    if not isinstance(executable, str) or Path(executable).name != executable or not executable:
        raise ValueError('Invalid main executable')
    binary = app / 'Contents/MacOS' / executable
    if 'arm64' not in subprocess.check_output(['/usr/bin/lipo', '-archs', str(binary)], text=True).split():
        raise ValueError('App does not support this ARM64 Mac')
    output.mkdir(parents=True, exist_ok=True)
    staged = output / app.name
    if staged.exists():
        raise ValueError('Preserve the existing staged bundle; select a fresh output directory')
    subprocess.run(['/usr/bin/ditto', str(app), str(staged)], check=True)
    (staged / 'Contents/Info.plist').write_bytes(plistlib.dumps(updated, fmt=plistlib.FMT_BINARY))
    subprocess.run(['/usr/bin/codesign', '--force', '--sign', '-', '--timestamp=none',
                    '--preserve-metadata=entitlements,flags', str(staged)], check=True)
    subprocess.run(['/usr/bin/codesign', '--verify', '--deep', '--strict', str(staged)], check=True)
    signing = subprocess.run(['/usr/bin/codesign', '-dvv', str(staged)], capture_output=True, text=True, check=True).stderr
    if 'Signature=adhoc' not in signing or f'Identifier={BUNDLE_ID}' not in signing:
        raise ValueError('Unexpected development signing identity')
    archive = output / 'cmux-android-push-mac-arm64.zip'
    subprocess.run(['/usr/bin/ditto', '-c', '-k', '--sequesterRsrc', '--keepParent', str(staged), str(archive)], check=True)
    with archive.open('rb') as handle:
        digest = hashlib.file_digest(handle, 'sha256').hexdigest()
    project = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=Path(__file__).resolve().parents[1], text=True).strip()
    manifest = {'version': 1, 'kind': 'cmux-app-tagged-mac-development-bundle', 'projectCommit': project,
                'upstreamCommit': UPSTREAM, 'bundleID': BUNDLE_ID, 'architecture': 'arm64', 'signing': 'adhoc',
                'notarized': False, 'archive': archive.name, 'sha256': digest,
                'patchFiles': {path: hashlib.sha256(data).hexdigest() for path, data in expected.items()},
                'xcode': subprocess.check_output(['xcodebuild', '-version'], text=True).strip(),
                'scope': 'Full tagged app built; runtime account, Keychain, native pairing, Pixel and FCM acceptance unverified.'}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (output / 'SHA256SUMS').write_text(f'{digest}  {archive.name}\n')
    # Upload the archive and receipts; the redundant uncompressed bundle is not needed.
    import shutil
    shutil.rmtree(staged)
    print('Isolated ARM64 development bundle verified and archived; no account or helper secrets packaged')


if __name__ == '__main__':
    main()
