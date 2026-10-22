#!/usr/bin/env python3
"""Execute the pinned Foundation language matching implementation; no translator stubs generate expectations."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--vectors', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    path = 'Packages/iOS/CmuxConversation/Sources/CmuxConversationCore/ConversationTranslation.swift'
    reviewed = {}
    for reviewed_path, expected in manifest['translationFiles'].items():
        data = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + reviewed_path])
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('Unreviewed translation source: ' + reviewed_path)
        reviewed[reviewed_path] = data
    source = reviewed[path]
    digest = hashlib.sha256(source).hexdigest()
    text = source.decode()
    start = text.index('extension Locale.Language {')
    end = text.index('\n/// Client-side', start)
    args.output.mkdir(parents=True, exist_ok=True)
    script = 'import Foundation\n' + text[start:end] + '''
let tags = ["en", "en-US", "es-MX", "zh", "zh-CN", "zh-TW", "zh-Hans", "zh-Hant", "sr", "sr-Latn", "sr-Cyrl", "az", "az-Arab", "iw", "he", "in", "id", "xx"]
let languages = tags.map { tag -> [String: Any] in
 let value = Locale.Language(identifier:tag)
 return ["input":tag,"language":value.languageCode?.identifier ?? "", "script":value.script?.identifier ?? "", "minimal":value.minimalIdentifier,
 "matches":tags.filter { value.translationMatches(Locale.Language(identifier:$0)) }]
}
print(String(data:try! JSONSerialization.data(withJSONObject:languages,options:[.sortedKeys]),encoding:.utf8)!)
'''
    swift = args.output / 'TranslationLanguages.swift'
    swift.write_text(script)
    languages = json.loads(subprocess.check_output(['swift', str(swift)]))
    vectors = dict(schema=1, revision=manifest['revision'], sourceSha256=digest, languages=languages)
    encoded = json.dumps(vectors, ensure_ascii=False, sort_keys=True, indent=2) + '\n'
    (args.output / 'chief-translation-languages.json').write_text(encoded)
    if args.vectors:
        args.vectors.parent.mkdir(parents=True, exist_ok=True)
        args.vectors.write_text(encoded)
    print(json.dumps(dict(languages=len(languages), pairs=len(languages)**2, sha256=hashlib.sha256(encoded.encode()).hexdigest())))


if __name__ == '__main__':
    main()
