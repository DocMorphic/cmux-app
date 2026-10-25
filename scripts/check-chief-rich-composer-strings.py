#!/usr/bin/env python3
"""Check copied palette labels against the immutable source catalog.

UIKit catalog contains English/Japanese only for these keys. The other supported
Android locales retain its English fallback. Undo/Redo are Android-authored labels.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PIN = "a5911a1f92f3eeb11c4d7f3e2ef89204718c5009"
FOLDERS = {"en": "values", "ja": "values-ja", "de": "values-de", "es": "values-es",
           "fr": "values-fr", "ar": "values-ar", "ko": "values-ko",
           "zh-Hans": "values-b+zh+Hans", "zh-Hant": "values-b+zh+Hant"}
AUTHORED = {"en": ("Undo", "Redo"), "ja": ("取り消す", "やり直す"), "de": ("Rückgängig", "Wiederholen"),
            "es": ("Deshacer", "Rehacer"), "fr": ("Annuler", "Rétablir"), "ar": ("تراجع", "إعادة"),
            "ko": ("실행 취소", "다시 실행"), "zh-Hans": ("撤销", "重做"), "zh-Hant": ("復原", "重做")}


def check(upstream, root=ROOT):
    binding = json.loads((root / "third_party/cmux-chief/manifest.json").read_text())["richComposerStrings"]
    raw = subprocess.check_output(["git", "-C", str(upstream), "show", PIN + ":" + binding["path"]])
    assert hashlib.sha256(raw).hexdigest() == binding["sha256"], "Catalog source identity"
    source = json.loads(raw)["strings"]
    count = 0
    for locale, folder in FOLDERS.items():
        rows = ET.parse(root / "app/src/main/res" / folder / "chief_rich_composer.xml").getroot()
        actual = {row.attrib["name"]: row.text for row in rows}
        expected = {}
        for key, name in binding["keys"].items():
            localized = source[key]["localizations"]
            expected[name] = localized.get(locale, localized["en"])["stringUnit"]["value"]
        expected.update(zip(("chief_rich_undo", "chief_rich_redo"), AUTHORED[locale]))
        assert actual == expected and len(rows) == len(expected), (folder, actual, expected)
        count += len(expected)
    return count


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    args = parser.parse_args()
    print("Checked", check(args.upstream), "palette/action labels in nine resource folders")
