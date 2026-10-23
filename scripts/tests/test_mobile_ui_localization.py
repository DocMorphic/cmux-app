import copy
import json
import tempfile
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location('mobile_ui_localization', Path(__file__).resolve().parents[1] / 'mobile-ui-localization.py')
UI = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(UI)
HEADER = 'package io.github.docmorphic.cmuxapp\n\nimport androidx.compose.material3.*\n'
ROWS = {'Cancel': {'resource': 'cmux_ui_cancel'}, 'Done': {'resource': 'cmux_ui_done'}}


class MobileUiLocalization(unittest.TestCase):
    def test_comments_embedded_programs_and_protocol_payloads_are_not_ui(self):
        source = HEADER + '''
// Text("Cancel")
/* nested /* Text("Done") */ comment */
val script = """<script>Text("Cancel")</script>"""
val payload = "Text(\\\"Cancel\\\")"
val command = TerminalInputOperation.Text("Cancel")
Text("Cancel")
'''
        changed, calls = UI.migrate(source, ROWS)
        self.assertEqual(['cmux_ui_cancel'], calls)
        for protected in ['// Text("Cancel")', '/* Text("Done") */', '<script>Text("Cancel")</script>',
                          'TerminalInputOperation.Text("Cancel")', '"Text(\\\"Cancel\\\")"']:
            self.assertIn(protected, changed)

    def test_template_expressions_raw_templates_and_nested_quotes_are_skipped(self):
        source = HEADER + '''
val description = "Result ${function("Text(\\\"Cancel\\\")")}"
val program = """${function("Done")} Text("Cancel")"""
Text("${workspace.title}")
Text("Cancel" + workspace.title)
Text("Done".uppercase())
'''
        self.assertEqual((source, []), UI.migrate(source, ROWS))

    def test_first_and_named_text_arguments_are_supported_in_any_argument_order(self):
        source = HEADER + '''
Text("Cancel", modifier = Modifier.semantics { contentDescription = "Cancel" })
Text(text = "Done", style = style)
Text(modifier = Modifier.padding(8.dp), text = "Cancel")
'''
        changed, calls = UI.migrate(source, ROWS)
        self.assertEqual(['cmux_ui_cancel', 'cmux_ui_done', 'cmux_ui_cancel'], calls)
        self.assertIn('contentDescription = "Cancel"', changed)
        self.assertEqual(1, changed.count('import androidx.compose.ui.res.stringResource'))
        self.assertEqual((changed, []), UI.migrate(changed, ROWS))

    def test_dynamic_content_and_non_material_text_are_preserved(self):
        source = HEADER + 'Text(workspace.title)\nText(terminalOutput)\nText("Unknown label")\n'
        self.assertEqual((source, []), UI.migrate(source, ROWS))
        source = 'package protocol\nval value = Text("Cancel")\n'
        self.assertEqual((source, []), UI.migrate(source, ROWS))

    def test_character_literals_and_escaped_ui_quotes_keep_lexer_boundaries(self):
        source = HEADER + '''
val quote = '\\''
val brace = '{'
Text("He said \\\"Cancel\\\"")
Text("Cancel")
'''
        self.assertEqual(['He said "Cancel"', 'Cancel'], [row[2] for row in UI.static_text_literals(source)])

    def test_xml_quotes_preserve_whitespace_markers_backslashes_and_literal_markup(self):
        value = '  @label? <b>"quote"</b> & it\'s \\path\nnext\tline  '
        translations = {language: value for language in UI.LANGUAGES}
        encoded = UI.resource_xml([{'resource': 'cmux_ui_escape', 'translations': translations}], 'en')
        parsed = ET.fromstring(encoded)
        item = parsed.find('string')
        self.assertEqual('false', item.attrib['formatted'])
        self.assertEqual(0, len(item))  # Markup is text, not Android style elements.
        self.assertEqual('"  @label? <b>\\"quote\\"</b> & it\\\'s \\\\path\\nnext\\tline  "', item.text)
        self.assertIn(b'&lt;b&gt;', encoded)

    def test_unsafe_controls_and_unterminated_source_are_refused(self):
        with self.assertRaises(ValueError): UI.android_literal('unsafe\x00value')
        for source in ['Text("unfinished)', '/* unfinished', 'val x = "${unfinished"']:
            with self.subTest(source=source), self.assertRaises(ValueError): UI.tokens(source)

    def test_cloud_failure_formats_preserve_one_string_argument_in_every_locale(self):
        rows = UI.load_cloud_lifecycle_rows()
        self.assertEqual(8, len(rows))
        for language in UI.LANGUAGES:
            xml = ET.fromstring(UI.resource_xml(rows, language))
            for row, entry in zip(sorted(rows, key=lambda r: r['resource']), xml.findall('string')):
                self.assertEqual(str(row['formatted']).lower(), entry.get('formatted'))
                if row['formatted']:
                    self.assertEqual(1, entry.text.count('%1$s'))
                    self.assertNotIn('%@', entry.text)
                else: self.assertNotIn('%', entry.text)

    def test_cloud_failure_format_rejects_extra_missing_or_wrong_type_placeholders(self):
        data = json.loads(UI.CLOUD_LIFECYCLE_DATA.read_text())
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'labels.json'
            for value in ['No argument', '%@ and %@', '%d', '%@ plus %s', '%1$s']:
                altered = copy.deepcopy(data)
                row = next(row for row in altered['strings'] if row['key'].endswith('FailedFormat'))
                row['translations']['en'] = value
                path.write_text(json.dumps(altered))
                with self.subTest(value=value), self.assertRaises(ValueError): UI.load_cloud_lifecycle_rows(path)

    def test_selected_catalog_has_complete_locales_and_unique_static_labels(self):
        rows = UI.load_rows()
        self.assertTrue(rows)
        self.assertEqual(len(rows), len({row['resource'] for row in rows}))
        for language in UI.LANGUAGES:
            xml = ET.fromstring(UI.resource_xml(rows, language))
            self.assertEqual(len(rows), len(xml.findall('string')))
            self.assertEqual({row['resource'] for row in rows}, {node.attrib['name'] for node in xml.findall('string')})


if __name__ == '__main__':
    unittest.main()
