#!/usr/bin/env python3
"""Generate Android resources from reviewed iOS translations; migrate static Text arguments only."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'third_party/cmux-localization/strings.json'
BILLING_DATA = ROOT / 'third_party/cmux-localization/billing-strings.json'
BILLING_ANDROID_DATA = ROOT / 'third_party/cmux-localization/billing-android-strings.json'
LANGUAGES = ('en', 'ar', 'de', 'es', 'fr', 'ja', 'ko', 'zh-Hans', 'zh-Hant')
FOLDERS = {'en': 'values', 'zh-Hans': 'values-b+zh+Hans', 'zh-Hant': 'values-b+zh+Hant'}
NAME = re.compile(r'[A-Za-z_][A-Za-z_0-9]*')


def quoted_end(source, start):
    raw = source.startswith('"""', start)
    quote = '"""' if raw else source[start]
    index = start + len(quote)
    while index < len(source):
        if source.startswith(quote, index):
            return index + len(quote)
        if not raw and source[index] == '\\':
            index += 2; continue
        if source.startswith('${', index):
            index = interpolation_end(source, index + 2); continue
        index += 1
    raise ValueError('Unterminated Kotlin literal')


def comment_end(source, start):
    if source.startswith('//', start):
        end = source.find('\n', start)
        return len(source) if end < 0 else end
    depth = 1; index = start + 2
    while index < len(source):
        if source.startswith('/*', index): depth += 1; index += 2
        elif source.startswith('*/', index):
            depth -= 1; index += 2
            if not depth: return index
        else: index += 1
    raise ValueError('Unterminated Kotlin comment')


def interpolation_end(source, index):
    depth = 1
    while index < len(source):
        if source.startswith(('//', '/*'), index): index = comment_end(source, index)
        elif source[index] in ('"', "'"): index = quoted_end(source, index)
        elif source[index] == '{': depth += 1; index += 1
        elif source[index] == '}':
            depth -= 1; index += 1
            if not depth: return index
        else: index += 1
    raise ValueError('Unterminated Kotlin interpolation')


def tokens(source):
    result = []; index = 0
    while index < len(source):
        start = index
        if source[index].isspace(): index += 1; continue
        if source.startswith(('//', '/*'), index): index = comment_end(source, index); continue
        if source[index] in ('"', "'"):
            index = quoted_end(source, index)
            result.append(('literal', source[start:index], start, index)); continue
        match = NAME.match(source, index)
        if match:
            index += len(match[0]); result.append(('name', match[0], start, index))
        else:
            index += 1; result.append(('symbol', source[start:index], start, index))
    return result


def static_text_literals(source):
    """Skip comments, scripts/raw strings, templates and computed Text expressions."""
    stream = tokens(source); found = {}
    for index, token in enumerate(stream):
        if token[:2] != ('name', 'Text') or index + 2 >= len(stream) or stream[index + 1][1] != '(':
            continue
        if index > 0 and stream[index - 1][1] == '.':
            continue  # A protocol/model Text constructor is not the Compose UI function.
        cursor = index + 2; argument = cursor; depth = 0
        while cursor < len(stream):
            value = stream[cursor][1]
            if depth == 0 and value in (',', ')'):
                parts = stream[argument:cursor]
                literal = None
                if argument == index + 2 and len(parts) == 1: literal = parts[0]
                if len(parts) == 3 and parts[0][1] == 'text' and parts[1][1] == '=': literal = parts[2]
                if literal and literal[0] == 'literal' and literal[1].startswith('"') and not literal[1].startswith('"""') and '$' not in literal[1]:
                    try: decoded = json.loads(literal[1])
                    except ValueError: decoded = None
                    if decoded: found[literal[2]] = (literal[2], literal[3], decoded)
                if value == ')': break
                argument = cursor + 1
            elif value in ('(', '[', '{'): depth += 1
            elif value in (')', ']', '}'): depth -= 1
            cursor += 1
    return list(found.values())


def migrate(source, by_english):
    if not re.search(r'import androidx\.compose\.material3\.(Text|\*)\s*(?:\n|$)', source):
        return source, []
    replacements = [(start, end, by_english[value]['resource']) for start, end, value in static_text_literals(source) if value in by_english]
    for start, end, resource in reversed(sorted(replacements)):
        source = source[:start] + 'stringResource(R.string.' + resource + ')' + source[end:]
    if replacements and 'import androidx.compose.ui.res.stringResource' not in source and 'import androidx.compose.ui.res.*' not in source:
        match = re.search(r'^package [^\n]+\n', source, re.MULTILINE)
        if not match: raise ValueError('Missing Kotlin package')
        source = source[:match.end()] + '\nimport androidx.compose.ui.res.stringResource\n' + source[match.end():]
    return source, [resource for _, _, resource in replacements]


def android_literal(value):
    if not isinstance(value, str) or any(ord(char) < 32 and char not in '\n\r\t' for char in value):
        raise ValueError('Invalid UI string')
    # Android quoting preserves whitespace and makes @/? prefixes literal;
    # XML serialization separately escapes markup, so it cannot become styles.
    escaped = value.replace('\\', '\\\\').replace('"', '\\"').replace("'", "\\'")
    escaped = escaped.replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')
    return '"' + escaped + '"'


def resource_xml(rows, language, provenance='from pinned cmux catalogs'):
    root = ET.Element('resources')
    root.append(ET.Comment(' Generated by scripts/mobile-ui-localization.py ' + provenance + '. '))
    for row in sorted(rows, key=lambda item: item['resource']):
        item = ET.SubElement(root, 'string', {'name': row['resource'], 'formatted': 'false'})
        item.text = android_literal(row['translations'][language])
    ET.indent(root, space='    ')
    return ET.tostring(root, encoding='utf-8', xml_declaration=True) + b'\n'


def load_rows(path=DATA):
    data = json.loads(path.read_text())
    if data['upstream'] != 'f4b1509054949eaad5d695569ad443c4c18ed68d' or data['version'] != 1:
        raise ValueError('Unreviewed localization source')
    rows = data['strings']; names = set(); english = set()
    for row in rows:
        if not re.fullmatch(r'cmux_ui_[a-z0-9_]+', row['resource']) or row['resource'] in names:
            raise ValueError('Duplicate/invalid resource name')
        if set(row['translations']) != set(LANGUAGES) or row['translations']['en'] in english:
            raise ValueError('Missing locale or ambiguous English label')
        if any('%' in text for text in row['translations'].values()):
            raise ValueError('Formatted strings need a separate reviewed conversion')
        names.add(row['resource']); english.add(row['translations']['en'])
    return rows


def load_billing_rows():
    data = json.loads(BILLING_DATA.read_text())
    if (data['version'] != 1 or data['upstream'] != 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
            or data['catalog'] != 'ios/cmux/Resources/Localizable.xcstrings'
            or not re.fullmatch('[a-f0-9]{64}', data['catalogSha256'])):
        raise ValueError('Unreviewed billing localization source')
    android = json.loads(BILLING_ANDROID_DATA.read_text())
    if android['version'] != 1:
        raise ValueError('Unreviewed Android billing copy')
    rows = data['strings'] + android['strings']
    names = set()
    for row in rows:
        if (not re.fullmatch(r'cmux_billing_[a-z0-9_]+', row['resource']) or row['resource'] in names
                or set(row['translations']) != set(LANGUAGES)
                or any(not text or '%' in text for text in row['translations'].values())):
            raise ValueError('Invalid billing localization row')
        names.add(row['resource'])
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--migrate', action='store_true')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    rows = load_rows(); by_english = {row['translations']['en']: row for row in rows}
    billing_rows = load_billing_rows()
    if args.migrate:
        if args.check: parser.error('--migrate and --check are exclusive')
        changes = 0
        for path in sorted((ROOT / 'app/src/main/java/io/github/docmorphic/cmuxapp').glob('*.kt')):
            source = path.read_text(); updated, replaced = migrate(source, by_english)
            if updated != source: path.write_text(updated); changes += len(replaced)
        print(f'Migrated {changes} static Compose Text arguments')
    for language in LANGUAGES:
        folder = FOLDERS.get(language, 'values-' + language)
        path = ROOT / 'app/src/main/res' / folder / 'cmux_ui_strings.xml'
        encoded = resource_xml(rows, language)
        if args.check:
            if not path.is_file() or path.read_bytes() != encoded: raise ValueError(f'Generated resources differ: {folder}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(encoded)
        billing_path = path.with_name('cmux_billing_strings.xml')
        encoded = resource_xml(billing_rows, language, 'from pinned billing labels and separately attributed Android copy')
        if args.check:
            if not billing_path.is_file() or billing_path.read_bytes() != encoded:
                raise ValueError(f'Generated billing resources differ: {folder}')
        else:
            billing_path.write_bytes(encoded)
    print(f'{len(rows)} general and {len(billing_rows)} billing labels / {len(LANGUAGES)} locales {"verified" if args.check else "generated"}; full localization remains separate')


if __name__ == '__main__':
    main()
