#!/usr/bin/env python3
"""Reproduce the pinned CLI initializer compile error and execute the corrected actual leaf."""
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
    root = Path(__file__).resolve().parents[1]; output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('native_patch', root / 'scripts/apply-native-push-patch.py')
    patch = importlib.util.module_from_spec(spec); spec.loader.exec_module(patch)
    originals = {path: subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', patch.REF + ':' + path]) for path in patch.PINS}
    patched = patch.patched_sources(originals); path = 'CLI/CMUXCLI+CommandSuggestions.swift'
    fixture = output / 'fixture.swift'
    fixture.write_text('''import Foundation
enum CmuxCommand { static let declaredCommandNames: Set<String> = ["help", "status", "workspace", "__private"] }
struct CLIError: Error { var message: String; var exitCode: Int32 }
struct CMUXCLI {}
@main struct Fixture {
    static func main() {
        precondition(CMUXCLI.topLevelCommandNames == CmuxCommand.declaredCommandNames.union(["chief"]))
        let chief = CMUXCLI().unknownCommandError("cheif")
        precondition(chief.exitCode == 2 && chief.message.contains("Did you mean 'chief'?"))
        precondition(CMUXCLI().unknownCommandError("workspce").message.contains("Did you mean 'workspace'?"))
        precondition(!CMUXCLI().unknownCommandError("unrelated-value").message.contains("Did you mean"))
        precondition(!CMUXCLI().unknownCommandError("__privat").message.contains("Did you mean"))
        print("Actual CLI suggestions: Chief inclusion, typo correction, exit status and hidden-command exclusion passed")
    }
}
''')
    def compile(source, name):
        swift = output / (name + '.swift'); swift.write_bytes(source)
        result = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', str(swift), str(fixture), '-o', str(output / name)], capture_output=True)
        (output / (name + '-build.log')).write_bytes(result.stdout + result.stderr)
        return result
    before = compile(originals[path], 'before')
    if before.returncode == 0 or b'did you mean to call it' not in before.stderr:
        raise ValueError('Pinned initializer failure was not reproduced')
    after = compile(patched[path], 'after'); after.check_returncode()
    behavior = subprocess.check_output([str(output / 'after')], timeout=10); (output / 'behavior.log').write_bytes(behavior)
    receipt = {'upstream': patch.REF, 'source': path, 'originalSha256': patch.PINS[path],
               'patchedSha256': hashlib.sha256(patched[path]).hexdigest(), 'beforeFailed': True, 'afterPassed': True,
               'scope': 'Actual leaf code compiled/executed with inert CLIError/registry/type definitions; not a linked full CLI or Mac app'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n'); print(behavior.decode().strip())


if __name__ == '__main__':
    main()
