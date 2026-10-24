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
BROWSER_DIALOG_DATA = ROOT / 'third_party/cmux-localization/browser-dialog-strings.json'
CLOUD_LIFECYCLE_DATA = ROOT / 'third_party/cmux-localization/cloud-lifecycle-strings.json'
CLOUD_ONBOARDING_DATA = ROOT / 'third_party/cmux-localization/cloud-onboarding-strings.json'
CLOUD_ONBOARDING_ANDROID_DATA = ROOT / 'third_party/cmux-localization/cloud-onboarding-android-strings.json'
MARKDOWN_DATA = ROOT / 'third_party/cmux-localization/markdown-strings.json'
SIGNIN_RECOVERY_DATA = ROOT / 'third_party/cmux-localization/sign-in-recovery-strings.json'
SIGNIN_ERROR_DATA = ROOT / 'third_party/cmux-localization/sign-in-error-strings.json'
SIGNIN_CODE_DATA = ROOT / 'third_party/cmux-localization/sign-in-code-strings.json'
SIGNIN_PROVIDER_DATA = ROOT / 'third_party/cmux-localization/sign-in-provider-strings.json'
SIGNIN_ANDROID_DATA = ROOT / 'third_party/cmux-localization/sign-in-android-strings.json'
SIGNIN_RESTORE_DATA = ROOT / 'third_party/cmux-localization/sign-in-restore-strings.json'
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
        item = ET.SubElement(root, 'string', {'name': row['resource'], 'formatted': str(row.get('formatted', False)).lower()})
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


def load_browser_dialog_rows():
    data = json.loads(BROWSER_DIALOG_DATA.read_text())
    if (data['version'] != 1 or data['upstream'] != 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
            or data['catalog'] != 'ios/cmux/Resources/Localizable.xcstrings'
            or not re.fullmatch('[a-f0-9]{64}', data['catalogSha256'])):
        raise ValueError('Unreviewed browser dialog localization source')
    names = set()
    for row in data['strings']:
        if (not row['key'].startswith('mobile.browserStream.dialog.')
                or not re.fullmatch(r'cmux_browser_dialog_[a-z0-9_]+', row['resource'])
                or row['resource'] in names or set(row['translations']) != set(LANGUAGES)
                or any(not text or '%' in text for text in row['translations'].values())):
            raise ValueError('Invalid browser dialog localization row')
        names.add(row['resource'])
    return data['strings']



def load_cloud_lifecycle_rows(path=CLOUD_LIFECYCLE_DATA):
    data = json.loads(path.read_text())
    if (data['version'] != 1 or data['upstream'] != 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
            or data['catalog'] != 'ios/cmux/Resources/Localizable.xcstrings'
            or not re.fullmatch('[a-f0-9]{64}', data['catalogSha256'])):
        raise ValueError('Unreviewed Cloud lifecycle localization source')
    expected = {'mobile.cloud.action.' + action + suffix
        for action in ('pause', 'resume', 'restart', 'delete') for suffix in ('', 'FailedFormat')}
    if {row['key'] for row in data['strings']} != expected or len(data['strings']) != len(expected):
        raise ValueError('Incomplete Cloud lifecycle labels')
    rows = []; names = set()
    for original in data['strings']:
        row = dict(original); formatted = row['key'].endswith('FailedFormat')
        if (not re.fullmatch(r'cmux_cloud_[a-z_]+', row['resource']) or row['resource'] in names
                or set(row['translations']) != set(LANGUAGES)):
            raise ValueError('Invalid Cloud lifecycle localization row')
        translated = {}
        for language, value in row['translations'].items():
            if not isinstance(value, str) or not value:
                raise ValueError('Invalid Cloud lifecycle translation')
            if formatted:
                if value.count('%@') != 1 or '%' in value.replace('%@', ''):
                    raise ValueError('Cloud failure format must have one string argument')
                value = value.replace('%@', '%1$s')
            elif '%' in value:
                raise ValueError('Unexpected Cloud label format')
            translated[language] = value
        row.update(translations=translated, formatted=formatted)
        rows.append(row); names.add(row['resource'])
    return rows


def load_cloud_onboarding_rows(path=CLOUD_ONBOARDING_DATA, android_path=CLOUD_ONBOARDING_ANDROID_DATA):
    data = json.loads(path.read_text()); android = json.loads(android_path.read_text())
    if (data['version'] != 1 or android['version'] != 1
            or data['upstream'] != 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
            or data['catalog'] != 'ios/cmux/Resources/Localizable.xcstrings'
            or data['catalogSha256'] != '9facaabb7334f92917f938a5f8ed3508c02b7ead5d401393e5410a49c3f74bfd'):
        raise ValueError('Unreviewed Cloud onboarding localization source')
    expected = {'mobile.cloud.onboarding.' + k for k in ('title', 'skip', 'workspace.title', 'workspace.message',
        'key.title', 'continue', 'done', 'back', 'progressLabel', 'vpn.controlFooter')}
    expected |= {'mobile.cloud.title', 'mobile.cloud.vpn.title', 'mobile.onboarding.progressFormat'}
    expected |= {'mobile.cloud.vpn.status.' + s for s in ('off', 'preparing', 'connecting', 'connected', 'disconnecting')}
    if {r['key'] for r in data['strings']} != expected or len(data['strings']) != len(expected):
        raise ValueError('Incomplete Cloud onboarding source labels')
    if {r['resource'] for r in android['strings']} != {'cmux_cloud_intro_android_vpn_message', 'cmux_cloud_intro_android_key_message'} or len(android['strings']) != 2:
        raise ValueError('Incomplete Android Cloud onboarding copy')
    rows = []; names = set()
    for original in data['strings'] + android['strings']:
        row = dict(original); formatted = row.get('key') == 'mobile.onboarding.progressFormat'
        if (not re.fullmatch(r'cmux_cloud_intro_[a-z_]+', row['resource']) or row['resource'] in names
                or set(row['translations']) != set(LANGUAGES)):
            raise ValueError('Invalid Cloud onboarding localization row')
        for value in row['translations'].values():
            if not isinstance(value, str) or not value:
                raise ValueError('Invalid Cloud onboarding translation')
            if formatted:
                if value.count('%1$d') != 1 or value.count('%2$d') != 1 or '%' in value.replace('%1$d', '').replace('%2$d', ''):
                    raise ValueError('Cloud progress format must contain two numbered integers')
            elif '%' in value: raise ValueError('Unexpected Cloud onboarding label format')
        row['formatted'] = formatted; rows.append(row); names.add(row['resource'])
    return rows


def load_markdown_rows(path=MARKDOWN_DATA):
    data = json.loads(path.read_text())
    if (data['version'] != 1 or data['upstream'] != 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
            or data['catalog'] != 'Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Resources/Localizable.xcstrings'
            or data['catalogSha256'] != '9b9aa8bc6572b760cb8df0709948473fb717f6af15a665e4a57c961b212d5719'):
        raise ValueError('Unreviewed Markdown localization source')
    expected = {'markdown.web.remoteImage' + suffix for suffix in ('Blocked', 'ConsentMessage', 'Copied', 'CopyURL',
        'HTTPSOnly', 'LoadImage', 'Loading', 'NotAllowed', 'OpenURL', 'URL')}
    if {r['key'] for r in data['strings']} != expected or len(data['strings']) != len(expected):
        raise ValueError('Incomplete Markdown image labels')
    names = set()
    for row in data['strings']:
        if (not re.fullmatch(r'cmux_markdown_[a-z_]+', row['resource']) or row['resource'] in names
                or set(row['translations']) != set(LANGUAGES)):
            raise ValueError('Invalid Markdown localization row')
        for value in row['translations'].values():
            if not isinstance(value, str) or not value or '%' in value:
                raise ValueError('Invalid Markdown translation')
            if value.count('{url}') != (1 if row['key'] == 'markdown.web.remoteImageURL' else 0):
                raise ValueError('Markdown URL label must preserve exactly its literal {url} argument')
        names.add(row['resource'])
    return data['strings']


def load_signin_recovery_rows(path=SIGNIN_RECOVERY_DATA):
    data = json.loads(path.read_text())
    expected_catalogs = {'ios/cmux/Resources/Localizable.xcstrings': '9facaabb7334f92917f938a5f8ed3508c02b7ead5d401393e5410a49c3f74bfd',
        'Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/Resources/Localizable.xcstrings': 'e942c581def4193ffa841537b70774fa9b91f98688fef40e4c5001ef864183a7'}
    if data['version'] != 1 or data['upstream'] != 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009' or data['catalogs'] != expected_catalogs:
        raise ValueError('Unreviewed sign-in recovery catalogs')
    expected = ['verificationTitle', 'verificationBodyFormat', 'verificationContinue', 'verificationPending',
        'verificationResend', 'billingRecovery', 'billingRecoverySent', 'useDifferentEmail']
    keys = ['mobile.signIn.'+key for key in expected] + ['auth.error.rate_limit', 'auth.error.server_error']
    expected = dict(zip(keys, ['cmux_signin_recovery_'+name for name in
        ['title', 'body', 'continue', 'pending', 'resend', 'billing', 'accepted', 'different', 'rate_limit', 'unavailable']]))
    if {row['key'] for row in data['strings']} != set(expected) or len(data['strings']) != len(expected):
        raise ValueError('Incomplete sign-in recovery labels')
    rows = []; names = set()
    for original in data['strings']:
        row = dict(original)
        catalog = 'Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/Resources/Localizable.xcstrings' if row['key'] in {'mobile.signIn.billingRecovery', 'mobile.signIn.billingRecoverySent'} else 'ios/cmux/Resources/Localizable.xcstrings'
        if row['catalog'] != catalog or row['resource'] != expected[row['key']] or row['resource'] in names or set(row['translations']) != set(LANGUAGES):
            raise ValueError('Invalid sign-in recovery label')
        formatted = row['key'] == 'mobile.signIn.verificationBodyFormat'
        translated = {}
        for language, value in row['translations'].items():
            if not isinstance(value, str) or not value: raise ValueError('Empty sign-in recovery translation')
            if formatted:
                if value.count('%@') != 1 or '%' in value.replace('%@', ''): raise ValueError('Invalid sign-in email format')
                value = value.replace('%@', '%1$s')
            elif '%' in value: raise ValueError('Unexpected sign-in recovery format')
            translated[language] = value
        row.update(translations=translated, formatted=formatted); rows.append(row); names.add(row['resource'])
    return rows


def load_signin_error_rows(path=SIGNIN_ERROR_DATA):
    data = json.loads(path.read_text())
    sources = {'catalog': {'path': 'ios/cmux/Resources/Localizable.xcstrings',
        'sha256': '9facaabb7334f92917f938a5f8ed3508c02b7ead5d401393e5410a49c3f74bfd'},
        'source_default': {'path': 'Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthError.swift',
        'sha256': 'd354ed3803800f3d9bb6e1d23ac45ab8f308f256d618a79176b57b704c0da790'}}
    if data['version'] != 1 or data['upstream'] != 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009' or data['sources'] != sources:
        raise ValueError('Unreviewed native sign-in error sources')
    expected = {'invalid_email', 'email_requires_verification', 'email_exists', 'invalid_code', 'code_expired',
        'rate_limit', 'wrong_password', 'user_not_found', 'passkey_failed', 'invalid_mfa', 'config', 'oauth_linked',
        'apple_config', 'browser_sign_in_failed', 'network_error', 'server_error', 'offline', 'timed_out',
        'invalid_code_short', 'invalid_callback', 'unauthorized'}
    if {row['key'] for row in data['strings']} != {'auth.error.'+name for name in expected} or len(data['strings']) != len(expected):
        raise ValueError('Incomplete native sign-in error vocabulary')
    for row in data['strings']:
        suffix = row['key'].removeprefix('auth.error.')
        if row['resource'] != 'cmux_signin_error_'+suffix or row['role'] != suffix.upper() or set(row['translations']) != set(LANGUAGES):
            raise ValueError('Unbound native sign-in error role/resource/locale')
        if row['origin'] != ('source_default' if suffix == 'offline' else 'catalog'):
            raise ValueError('Wrong native sign-in error origin')
        for value in row['translations'].values():
            if not isinstance(value, str) or not value or '%' in value: raise ValueError('Invalid native sign-in error text')
            # The pinned iOS main catalog lacks this key; preserve the shared
            # AuthError's actual English default, rather than label Mac values
            # as iOS translations or invent missing translations.
            if suffix == 'offline' and value != 'No internet connection. Connect to Wi-Fi or cellular and try again.':
                raise ValueError('Changed original offline source fallback')
    return data['strings']


def load_signin_code_rows(path=SIGNIN_CODE_DATA):
    data = json.loads(path.read_text())
    if data['version'] != 1 or data['upstream'] != 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009' or data['catalog'] != {
        'path': 'ios/cmux/Resources/Localizable.xcstrings',
        'sha256': '9facaabb7334f92917f938a5f8ed3508c02b7ead5d401393e5410a49c3f74bfd'}:
        raise ValueError('Unreviewed sign-in code catalog')
    expected = dict(zip(['title', 'checkEmail', 'sentCodeFormat', 'codePlaceholder', 'verifyCode'],
        ['brand', 'title', 'sent_to', 'placeholder', 'verify']))
    if len(data['strings']) != len(expected) or {r['key'] for r in data['strings']} != {'mobile.signIn.'+k for k in expected}:
        raise ValueError('Incomplete sign-in code labels')
    rows = []
    for original in data['strings']:
        row = dict(original); key = row['key'].removeprefix('mobile.signIn.')
        if row['resource'] != 'cmux_signin_code_'+expected[key] or set(row['translations']) != set(LANGUAGES):
            raise ValueError('Unbound sign-in code resource/locale')
        translated = {}; formatted = key == 'sentCodeFormat'
        for language, value in row['translations'].items():
            if not isinstance(value, str) or not value: raise ValueError('Empty sign-in code label')
            if formatted:
                if value.count('%@') != 1 or '%' in value.replace('%@', ''): raise ValueError('Invalid sign-in code email format')
                value = value.replace('%@', '%1$s')
            elif '%' in value: raise ValueError('Unexpected sign-in code format')
            translated[language] = value
        row.update(translations=translated, formatted=formatted); rows.append(row)
    return rows


def load_signin_provider_rows(path=SIGNIN_PROVIDER_DATA):
    data = json.loads(path.read_text())
    if data['version'] != 1 or data['upstream'] != 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009' or data['catalog'] != {
        'path': 'ios/cmux/Resources/Localizable.xcstrings',
        'sha256': '9facaabb7334f92917f938a5f8ed3508c02b7ead5d401393e5410a49c3f74bfd'}:
        raise ValueError('Unreviewed sign-in provider catalog')
    expected = {'apple': 'apple', 'google': 'google', 'github': 'github', 'emailDivider': 'divider', 'emailCode': 'email_code', 'emailPlaceholder': 'email_placeholder'}
    if len(data['strings']) != len(expected) or {r['key'] for r in data['strings']} != {'mobile.signIn.'+k for k in expected}:
        raise ValueError('Incomplete sign-in providers')
    for row in data['strings']:
        if row['resource'] != 'cmux_signin_provider_'+expected[row['key'].removeprefix('mobile.signIn.')] or set(row['translations']) != set(LANGUAGES):
            raise ValueError('Unbound sign-in provider resource/locale')
        if any(not isinstance(v, str) or not v or '%' in v for v in row['translations'].values()):
            raise ValueError('Invalid sign-in provider copy')
    return data['strings']



def load_signin_android_rows(path=SIGNIN_ANDROID_DATA):
    data = json.loads(path.read_text())
    expected = {'options', 'setup', 'mac_setup', 'helper', 'licenses'}
    if data['version'] != 1 or len(data['strings']) != len(expected) or {r['resource'] for r in data['strings']} != {'cmux_signin_android_'+k for k in expected}:
        raise ValueError('Incomplete Android sign-in options')
    for row in data['strings']:
        if set(row['translations']) != set(LANGUAGES) or any(not isinstance(v, str) or not v or '%' in v for v in row['translations'].values()):
            raise ValueError('Invalid Android sign-in option copy')
    return data['strings']


def load_signin_restore_rows(path=SIGNIN_RESTORE_DATA):
    data=json.loads(path.read_text())
    expected={'mobile.signIn.restoring':'title','mobile.signIn.restoringMessage':'message',
        'mobile.signIn.restoreTimeoutTitle':'timeout_title','mobile.signIn.restoreTimeoutMessage':'timeout_message','mobile.common.retry':'retry'}
    if data['version']!=1 or data['upstream']!='a5911a1f92f3eeb11c4d7f3e2ef89204718c5009' or data['catalog']!={
        'path':'ios/cmux/Resources/Localizable.xcstrings','sha256':'9facaabb7334f92917f938a5f8ed3508c02b7ead5d401393e5410a49c3f74bfd'}:
        raise ValueError('Unreviewed restore catalog')
    rows=data['strings']
    if len(rows)!=5 or {r['key'] for r in rows}!=set(expected): raise ValueError('Incomplete restore status labels')
    for row in rows:
        if row['resource']!='cmux_signin_restore_'+expected[row['key']] or set(row['translations'])!=set(LANGUAGES):raise ValueError('Unbound restore status copy')
        if any(not isinstance(v,str) or not v or '%' in v for v in row['translations'].values()):raise ValueError('Invalid restore status label')
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--migrate', action='store_true')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    rows = load_rows(); by_english = {row['translations']['en']: row for row in rows}
    billing_rows = load_billing_rows()
    browser_dialog_rows = load_browser_dialog_rows()
    cloud_lifecycle_rows = load_cloud_lifecycle_rows()
    cloud_onboarding_rows = load_cloud_onboarding_rows()
    markdown_rows = load_markdown_rows()
    signin_rows = load_signin_recovery_rows()
    signin_error_rows = load_signin_error_rows()
    signin_code_rows = load_signin_code_rows()
    signin_provider_rows = load_signin_provider_rows()
    signin_android_rows = load_signin_android_rows()
    signin_restore_rows = load_signin_restore_rows()
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
        dialog_path = path.with_name('cmux_browser_dialog_strings.xml')
        encoded = resource_xml(browser_dialog_rows, language, 'from pinned browser dialog labels')
        if args.check:
            if not dialog_path.is_file() or dialog_path.read_bytes() != encoded:
                raise ValueError(f'Generated browser dialog resources differ: {folder}')
        else:
            dialog_path.write_bytes(encoded)
        cloud_path = path.with_name('cmux_cloud_lifecycle_strings.xml')
        encoded = resource_xml(cloud_lifecycle_rows, language, 'from pinned Cloud lifecycle labels; one source string argument maps to %1$s')
        if args.check:
            if not cloud_path.is_file() or cloud_path.read_bytes() != encoded:
                raise ValueError(f'Generated Cloud lifecycle resources differ: {folder}')
        else:
            cloud_path.write_bytes(encoded)
        onboarding_path = path.with_name('cmux_cloud_onboarding_strings.xml')
        encoded = resource_xml(cloud_onboarding_rows, language, 'from pinned Cloud onboarding labels and separately attributed Android browser/Keystore copy')
        if args.check:
            if not onboarding_path.is_file() or onboarding_path.read_bytes() != encoded:
                raise ValueError(f'Generated Cloud onboarding resources differ: {folder}')
        else:
            onboarding_path.write_bytes(encoded)
        markdown_path = path.with_name('cmux_markdown_strings.xml')
        encoded = resource_xml(markdown_rows, language, 'from pinned Markdown image labels; {url} remains a shell substitution')
        if args.check:
            if not markdown_path.is_file() or markdown_path.read_bytes() != encoded:
                raise ValueError(f'Generated Markdown resources differ: {folder}')
        else:
            markdown_path.write_bytes(encoded)
        signin_path = path.with_name('cmux_signin_recovery_strings.xml')
        encoded = resource_xml(signin_rows, language, 'from pinned original native sign-in recovery catalogs; %@ maps to %1$s')
        if args.check:
            if not signin_path.is_file() or signin_path.read_bytes() != encoded: raise ValueError(f'Generated sign-in recovery resources differ: {folder}')
        else:
            signin_path.write_bytes(encoded)
        error_path = path.with_name('cmux_signin_error_strings.xml')
        encoded = resource_xml(signin_error_rows, language, 'from pinned original sign-in error vocabulary; offline retains its attributed English source default')
        if args.check:
            if not error_path.is_file() or error_path.read_bytes() != encoded: raise ValueError(f'Generated native sign-in error resources differ: {folder}')
        else:
            error_path.write_bytes(encoded)
        code_path = path.with_name('cmux_signin_code_strings.xml')
        encoded = resource_xml(signin_code_rows, language, 'from pinned original code entry catalog; %@ maps to %1$s')
        if args.check:
            if not code_path.is_file() or code_path.read_bytes() != encoded: raise ValueError(f'Generated sign-in code resources differ: {folder}')
        else:
            code_path.write_bytes(encoded)
        provider_path = path.with_name('cmux_signin_provider_strings.xml')
        encoded = resource_xml(signin_provider_rows, language, 'from pinned original native provider labels and email divider')
        if args.check:
            if not provider_path.is_file() or provider_path.read_bytes() != encoded: raise ValueError(f'Generated sign-in provider resources differ: {folder}')
        else:
            provider_path.write_bytes(encoded)
        android_path = path.with_name('cmux_signin_android_strings.xml')
        encoded = resource_xml(signin_android_rows, language, 'project-authored Android-only sign-in options, not upstream translations')
        if args.check:
            if not android_path.is_file() or android_path.read_bytes() != encoded: raise ValueError(f'Generated Android sign-in resources differ: {folder}')
        else:
            android_path.write_bytes(encoded)
        restore_path = path.with_name('cmux_signin_restore_strings.xml')
        encoded = resource_xml(signin_restore_rows, language, 'from pinned original session restore status catalog')
        if args.check:
            if not restore_path.is_file() or restore_path.read_bytes() != encoded: raise ValueError(f'Generated sign-in restore resources differ: {folder}')
        else:
            restore_path.write_bytes(encoded)
    print(f'{len(rows)} general, {len(billing_rows)} billing, {len(browser_dialog_rows)} browser dialog, {len(cloud_lifecycle_rows)} Cloud lifecycle, {len(cloud_onboarding_rows)} Cloud onboarding, {len(markdown_rows)} Markdown, {len(signin_rows)} sign-in recovery, {len(signin_code_rows)} sign-in code, {len(signin_provider_rows)} sign-in provider and {len(signin_error_rows)} sign-in error roles / {len(LANGUAGES)} locales {"verified" if args.check else "generated"}; full localization remains separate')


if __name__ == '__main__':
    main()
