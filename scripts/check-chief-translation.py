#!/usr/bin/env python3
"""Execute the pinned Foundation language matching implementation; no translator stubs generate expectations."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--vectors', type=Path)
    parser.add_argument('--presentation-vectors', type=Path)
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
    catalog_pin = manifest['translationStrings']
    catalog_data = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + catalog_pin['path']])
    if hashlib.sha256(catalog_data).hexdigest() != catalog_pin['sha256']:
        raise ValueError('Unreviewed translation catalog')
    catalog = json.loads(catalog_data)['strings']
    localized_values = 0
    for language, folder in [('en', 'values'), ('ja', 'values-ja')]:
        expected = {}
        for key, name in catalog_pin['keys'].items():
            value = catalog[key]['localizations'][language]
            unit = value.get('stringUnit') or value['variations']['device'][catalog_pin['deviceVariation']]['stringUnit']
            expected[name] = unit['value'].replace('%@', '%1$s')
        actual = {element.attrib['name']: element.text.replace("\\'", "'") for element in
                  ET.parse(root / 'app/src/main/res' / folder / 'chief_translation.xml').getroot()}
        android_keys = {'chief_translation_open_settings', 'chief_translation_download_explanation'}
        if {key: value for key, value in actual.items() if key not in android_keys} != expected:
            raise ValueError('Translation labels differ: ' + language)
        if not android_keys <= actual.keys():
            raise ValueError('Missing Android model-admission labels: ' + language)
        localized_values += len(expected)
    digest = hashlib.sha256(source).hexdigest()
    text = source.decode()
    start = text.index('extension Locale.Language {')
    end = text.index('\n/// Client-side', start)
    args.output.mkdir(parents=True, exist_ok=True)
    rows = reviewed['Packages/iOS/CmuxConversation/Sources/CmuxConversationUI/ConversationRows.swift'].decode()
    projection_start = rows.index('        var message = original')
    projection = rows[projection_start:rows.index('        if message.isScheduled', projection_start)]
    if 'message: message,' not in rows[projection_start:]:
        raise ValueError('The row no longer carries the projected message')
    copy_statement = 'UIPasteboard.general.string = message.text'
    for name in ['MessageActionOverlay.swift', 'ConversationAccessibility.swift']:
        body = reviewed['Packages/iOS/CmuxConversation/Sources/CmuxConversationUI/' + name].decode()
        if body.count(copy_statement) != 1 or 'let message = model.message' not in body:
            raise ValueError('Review rendered-model Copy: ' + name)
    presentation_script = '''import Foundation
struct Poll { var question: String }
struct Message { var text: String; var mentions: [String]; var textRuns: [String]; var poll: Poll? }
struct Presentation { var text: String }
@MainActor final class Pasteboard { var string: String? }
@MainActor enum UIPasteboard { static let general = Pasteboard() }
@MainActor func sample(_ id: String, _ supplied: String?) -> [String:Any] {
 let original = Message(text:"es: Hola, mundo", mentions:["mention"], textRuns:["format"], poll:Poll(question:"Keep the poll question"))
 let translation = supplied.map { Presentation(text:$0) }
''' + projection + '\n' + copy_statement + '''
 let menu = UIPasteboard.general.string!
''' + copy_statement + '''
 return ["id":id,"original":original.text,"supplied":supplied ?? NSNull(),"messageText":message.text,
 "menuCopy":menu,"accessibilityCopy":UIPasteboard.general.string!,"pollQuestion":message.poll!.question,
 "mentions":message.mentions,"textRuns":message.textRuns]
}
let cases = [sample("none",nil),sample("translated","Hello, world"),sample("show-original","es: Hola, mundo"),sample("not-downloaded","es: Hola, mundo")]
print(String(data:try! JSONSerialization.data(withJSONObject:cases,options:[.sortedKeys]),encoding:.utf8)!)
'''
    presentation_path = args.output / 'TranslationPresentation.swift'
    presentation_path.write_text(presentation_script)
    presentation_cases = json.loads(subprocess.check_output(['swift', '-swift-version', '6', str(presentation_path)]))
    presentation_vectors = dict(schema=1, revision=manifest['revision'], projectionSha256=hashlib.sha256(projection.encode()).hexdigest(),
                                scope='Executed pinned row substitution and Copy statements with an inert pasteboard; not UIKit or translation inference', cases=presentation_cases)
    presentation_encoded = json.dumps(presentation_vectors, ensure_ascii=False, sort_keys=True, indent=2) + '\n'
    (args.output / 'chief-translation-presentation.json').write_text(presentation_encoded)
    if args.presentation_vectors:
        args.presentation_vectors.write_text(presentation_encoded)
    elif (root / 'app/src/test/resources/parity/chief-translation-presentation.json').read_text() != presentation_encoded:
        raise ValueError('Rendered message/Copy vectors differ')
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
    print(json.dumps(dict(languages=len(languages), pairs=len(languages)**2, localizedValues=localized_values,
                         reviewedFiles=len(reviewed), sha256=hashlib.sha256(encoded.encode()).hexdigest())))


if __name__ == '__main__':
    main()
